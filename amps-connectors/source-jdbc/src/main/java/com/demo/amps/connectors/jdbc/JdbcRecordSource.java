package com.demo.amps.connectors.jdbc;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.JdbcSourceProperties;
import com.demo.amps.connectors.source.Acknowledgment;
import com.demo.amps.connectors.source.RecordHandler;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordSource} over a database query.
 *
 * <p>Reads its endpoint, its query and its poll mode from {@code source.jdbc}. A query is a
 * <strong>snapshot, not a stream</strong>: there is nothing to subscribe to, so this source
 * polls, and {@code source.jdbc.mode} decides what each poll emits.
 *
 * <table border="1">
 *   <caption>Poll modes</caption>
 *   <tr><th>{@code mode}</th><th>each poll emits</th><th>the AMPS analog</th></tr>
 *   <tr><td>{@code SNAPSHOT}</td>
 *       <td>every row of the query, as an {@code UPSERT}; with {@code key-columns} set, also a
 *           {@code DELETE} for every key that appeared last poll and not this one</td>
 *       <td>a SOW replay, and its out-of-focus message</td></tr>
 *   <tr><td>{@code INCREMENTAL}</td>
 *       <td>only the rows whose {@code incremental-column} is above the high-water mark of
 *           the polls before it, as {@code UPSERT}s</td>
 *       <td>a journal topic read forward from a bookmark</td></tr>
 * </table>
 *
 * <h2>The rows are turned into JSON here</h2>
 *
 * <p>A result-set row has no wire format, so this source synthesises one: each row becomes a
 * flat JSON object keyed by result-set column <em>label</em>, which is why the validator
 * requires {@code format: JSON} and why filters, transforms and keys address columns by label
 * (an {@code AS} alias included). Numbers stay numbers and booleans stay booleans, dates and
 * times become ISO-8601 text -- a timestamp in its {@code Instant} form -- and SQL
 * {@code NULL} becomes an explicit JSON {@code null}, which is a cleared field rather than an
 * absent one.
 *
 * <p>{@code key-columns} is a list, and the record's key is those columns' values joined by
 * {@code key-separator}: a position is an account and a symbol far more often than it is one
 * column, and the alternative -- making the operator concatenate them in SQL -- puts a
 * synthetic column in the payload that AMPS then has to carry. A row with a NULL key column
 * has no key, so it is published unkeyed and left out of the delete diff.
 *
 * <h2>A delete has to carry its key columns</h2>
 *
 * <p>When a key stops appearing, the {@code DELETE} this source emits carries <em>a JSON
 * object of just the key columns and their values</em>, not an empty payload. That is an AMPS
 * requirement rather than a nicety: a connector whose topic derives its key server-side
 * ({@code key.mode: SERVER}) expresses a removal as {@code sowDelete(topic, "/account = 'ACC-1'
 * AND /symbol = 'MSFT'")}, and that filter can only be built from a payload that still has the
 * key fields in it. {@code PUBLISHER} mode uses the joined key, which rides along on the same
 * record.
 *
 * <h2>The query is never rewritten, and the watermark is persisted on acknowledgment</h2>
 *
 * <p>{@code INCREMENTAL} filters in this process rather than wrapping the configured SQL: what
 * the database is asked is exactly what the operator wrote, so a query that is already correct
 * cannot be broken by string surgery, and an expensive one is visibly expensive.
 *
 * <p>Two marks are kept, and the difference between them is the framework's at-least-once
 * contract:
 *
 * <table border="1">
 *   <caption>The two marks</caption>
 *   <tr><th>mark</th><th>moves when</th><th>what it is for</th></tr>
 *   <tr><td>in-memory watermark</td><td>a row is <em>read</em></td>
 *       <td>stops this process re-emitting the same rows on the next poll</td></tr>
 *   <tr><td>persisted watermark</td><td>a row is <em>acknowledged</em></td>
 *       <td>where a restarted connector resumes -- so it is only ever past what AMPS has
 *           actually confirmed</td></tr>
 * </table>
 *
 * <p>The persisted one is written by the poll thread, after each poll, to {@code state-file}
 * (a temp file and an atomic move, so a crash mid-write leaves the previous mark rather than
 * half a line). Persisting on <em>acknowledgment</em> rather than on read is the whole point:
 * a mark saved when the row was read would skip rows whose batch never reached AMPS. The
 * reverse is possible and deliberate -- a row acknowledged after the poll that wrote the file,
 * or during a crash, is read again on the next start, and a keyed topic upserts it while a
 * journal topic gets a duplicate. At-least-once, as everywhere else in this framework.
 * Without a {@code state-file} the mark is memory only, so a restart re-reads the whole query.
 *
 * <p>{@link #start} returns as soon as the poll thread is running, rather than connecting on
 * the caller's thread. For a transport whose normal state includes "the database is not up
 * yet", a failed first connect is the same event as a dropped one and both belong in the same
 * backoff -- so {@link #isConnected()}, not a thrown {@code start}, is what reports it.
 */
public class JdbcRecordSource implements RecordSource {

    private static final Logger log = LoggerFactory.getLogger(JdbcRecordSource.class);

    /** Attribute carrying the number of the poll a record came from, counting from one. */
    public static final String ATTRIBUTE_POLL = "poll";

    /** How long {@link #close()} waits for the poll thread before giving up on it. */
    private static final long CLOSE_JOIN_MILLIS = 5_000;

    private final ConnectorProperties connector;
    private final JdbcSourceProperties source;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** Monitor the poll interval and the reconnect backoff wait on, so a close cuts them short. */
    private final Object idle = new Object();

    /** Numbers the polls for the {@code poll} attribute; read by the poll thread only. */
    private final AtomicLong polls = new AtomicLong();

    /**
     * Keys seen by the previous {@code SNAPSHOT} poll, mapped to the JSON object of their key
     * columns -- kept because that object is what a {@code DELETE} has to carry, and the row
     * it came from is gone by the time the delete is emitted.
     *
     * <p>Touched only by the poll thread, and replaced rather than mutated, so a failed poll
     * leaves the last good key set standing: a query that threw halfway is not "everything
     * vanished", and emitting deletes for it would empty the SOW.
     */
    private Map<String, String> previousKeys = Map.of();

    /**
     * The {@code INCREMENTAL} high-water mark of what has been <em>read</em>, normalised by
     * {@link #mark(Object)}. Touched only by the poll thread.
     */
    private Object watermark;

    /**
     * The high-water mark of what has been <em>acknowledged</em> -- written by whichever
     * thread flushed the batch, read by the poll thread when it persists the mark.
     */
    private final AtomicReference<Object> acknowledged = new AtomicReference<>();

    /** What the state file already says, so an unchanged mark is not rewritten every poll. */
    private Object persisted;

    private volatile Connection connection;
    private volatile Thread thread;

    public JdbcRecordSource(ConnectorProperties connector) {
        this.connector = connector;
        this.source = connector.getSource().getJdbc();
    }

    @Override
    public void start(RecordHandler handler) {
        log.info("[{}] starting JDBC source: {} ({} poll every {})", connector.getName(),
                source.getUrl(), source.getMode(), source.getPollInterval());
        Thread runner = new Thread(() -> run(handler), connector.getName() + "-jdbc");
        runner.setDaemon(true);
        this.thread = runner;
        runner.start();
    }

    /**
     * Connect, poll until the connection or {@link #close()} ends it, back off, connect again.
     * One iteration of the outer loop is one connection's lifetime.
     */
    private void run(RecordHandler handler) {
        // Before the first poll, so a restarted connector's first query already resumes past
        // what AMPS confirmed rather than re-reading the table into the journal.
        restoreWatermark();
        while (!closed.get()) {
            Connection open = null;
            try {
                open = connect();
                this.connection = open;
                if (closed.get()) {
                    // close() publishes `closed` and then reads `connection`; this reads them
                    // the other way round, so between the two at least one of us sees the
                    // other. Without the check, a close landing in this window would leave a
                    // connection nobody closes.
                    break;
                }
                connected.set(true);
                log.info("[{}] connected to {}", connector.getName(), source.getUrl());
                while (!closed.get()) {
                    poll(open, handler);
                    // After the poll, and on the poll thread: the state file is this source's
                    // own, and a second writer would be a second truth.
                    persistWatermark();
                    if (!sleep(source.getPollInterval())) {
                        break;
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (!closed.get()) {
                    log.error("[{}] JDBC source failed on {}",
                            connector.getName(), source.getUrl(), e);
                }
            } finally {
                connected.set(false);
                this.connection = null;
                closeQuietly(open);
            }
            if (!closed.get()) {
                log.info("[{}] reconnecting to {} in {}", connector.getName(), source.getUrl(),
                        source.getReconnectDelay());
                if (!sleep(source.getReconnectDelay())) {
                    break;
                }
            }
        }
        connected.set(false);
        log.info("[{}] JDBC source stopped", connector.getName());
    }

    /**
     * Open the configured connection.
     *
     * <p>A blank username hands the URL the whole job, which is what an embedded or
     * trust-authenticated database expects; passing {@code ("", "")} to those is not the same
     * thing and is rejected by some drivers.
     *
     * @return a new connection
     * @throws SQLException when the database cannot be reached or refuses the credentials
     */
    private Connection connect() throws SQLException {
        String username = source.getUsername();
        if (username == null || username.isBlank()) {
            return DriverManager.getConnection(source.getUrl());
        }
        return DriverManager.getConnection(source.getUrl(), username, source.getPassword());
    }

    /**
     * Run the query once and emit what it says.
     *
     * @param open the connection to query
     * @param handler where the records go
     * @throws SQLException when the query fails, which ends this connection's lifetime
     */
    private void poll(Connection open, RecordHandler handler) throws SQLException {
        Map<String, String> attributes =
                Map.of(ATTRIBUTE_POLL, Long.toString(polls.incrementAndGet()));
        boolean keyed = !source.getKeyColumns().isEmpty();
        boolean tracking = snapshot() && keyed;
        Map<String, String> keys = tracking ? new LinkedHashMap<>() : Map.of();
        // Fixed for the whole poll: a result set has no order unless the query gave it one,
        // so every row is compared against the mark the PREVIOUS polls left, never against a
        // mark this poll has already moved.
        Object mark = watermark;
        // One WARN per poll for unkeyable rows: a table with a nullable key column would
        // otherwise log a line per row per poll, forever.
        boolean warnedAboutNullKey = false;

        try (Statement statement = open.createStatement()) {
            statement.setFetchSize(source.getFetchSize());
            try (ResultSet rows = statement.executeQuery(source.getQuery())) {
                ResultSetMetaData meta = rows.getMetaData();
                while (rows.next() && !closed.get()) {
                    Object rowMark = null;
                    if (!snapshot()) {
                        rowMark = mark(rows.getObject(source.getIncrementalColumn()));
                        if (rowMark == null) {
                            // A null in the column that orders the feed cannot be placed
                            // against the mark, and emitting it would re-emit it every poll
                            // for as long as it sits in the table.
                            log.warn("[{}] skipping a row whose incremental column '{}' is null",
                                    connector.getName(), source.getIncrementalColumn());
                            continue;
                        }
                        if (!above(rowMark, mark)) {
                            continue;
                        }
                        if (above(rowMark, watermark)) {
                            watermark = rowMark;
                        }
                    }
                    ObjectNode keyFields = mapper.createObjectNode();
                    String key = keyed ? keyOf(rows, keyFields) : null;
                    if (keyed && key == null && !warnedAboutNullKey) {
                        warnedAboutNullKey = true;
                        // One unkeyable row, not a broken feed: it is published without a key
                        // (so it is never a candidate for a vanish-delete either).
                        log.warn("[{}] row with a null value among key columns {}: published "
                                        + "without a source key, and not tracked for deletes",
                                connector.getName(), source.getKeyColumns());
                    }
                    if (tracking && key != null) {
                        keys.put(key, keyFields.toString());
                    }
                    // Only an incremental row can be acknowledged: a snapshot has no position
                    // to remember -- the next poll re-reads it whatever AMPS said.
                    Object rowWatermark = rowMark;
                    Acknowledgment ack = snapshot() ? null : () -> acknowledge(rowWatermark);
                    emit(json(rows, meta), key, SourceRecord.Action.UPSERT, attributes, ack,
                            handler);
                }
            }
        }

        if (tracking) {
            for (Map.Entry<String, String> gone : previousKeys.entrySet()) {
                if (!keys.containsKey(gone.getKey())) {
                    // The JDBC spelling of an out-of-focus message. The payload is the key
                    // columns and nothing else, so a SERVER-keyed topic can still turn the
                    // removal into a filter.
                    emit(gone.getValue(), gone.getKey(), SourceRecord.Action.DELETE, attributes,
                            null, handler);
                }
            }
            previousKeys = keys;
        }
    }

    /** Whether this source re-reads the whole query every poll. */
    private boolean snapshot() {
        return source.getMode() == JdbcSourceProperties.Mode.SNAPSHOT;
    }

    /**
     * The current row's key: the key columns' values joined by {@code key-separator}.
     *
     * @param rows positioned on the row to key
     * @param keyFields collects those columns as JSON, which is what a DELETE carries
     * @return the joined key, or {@code null} when any key column is NULL
     */
    private String keyOf(ResultSet rows, ObjectNode keyFields) throws SQLException {
        StringBuilder key = new StringBuilder();
        boolean first = true;
        for (String column : source.getKeyColumns()) {
            Object value = rows.getObject(column);
            if (value == null) {
                return null;
            }
            if (!first) {
                key.append(source.getKeySeparator());
            }
            first = false;
            key.append(value);
            put(keyFields, column, value);
        }
        return key.toString();
    }

    private void emit(String data, String key, SourceRecord.Action action,
            Map<String, String> attributes, Acknowledgment ack, RecordHandler handler) {
        try {
            SourceRecord record = action == SourceRecord.Action.DELETE
                    ? SourceRecord.delete(data, key)
                    // The key rides along on upserts too, the way a Kafka message key does,
                    // so a PUBLISHER-keyed connector has one on every record and not just on
                    // the ones that leave.
                    : SourceRecord.of(data, key);
            record = record.withAttributes(attributes);
            handler.onRecord(ack == null ? record : record.withAck(ack));
        } catch (RuntimeException e) {
            // One bad row is not a reason to drop the feed: the pipeline counts it, we keep
            // polling -- and because it was never acknowledged, the watermark does not move
            // past it either.
            log.error("[{}] failed to handle a JDBC row", connector.getName(), e);
        }
    }

    /**
     * Serialise the current row as a flat JSON object keyed by column label.
     *
     * @param rows positioned on the row to serialise
     * @param meta that result set's metadata
     * @return the row's JSON text
     */
    private String json(ResultSet rows, ResultSetMetaData meta) throws SQLException {
        ObjectNode row = mapper.createObjectNode();
        for (int column = 1; column <= meta.getColumnCount(); column++) {
            put(row, meta.getColumnLabel(column), rows.getObject(column));
        }
        return row.toString();
    }

    /**
     * One column's value, in the JSON type that keeps its meaning.
     *
     * <p>Numbers and booleans stay themselves, so a filter or a transform has a number to
     * compare. Dates and times become ISO-8601 text, because JSON has no date type and AMPS
     * reads instants from text; a timestamp without a zone is read in the JVM's zone, which is
     * what {@link Timestamp#toInstant()} does and what the database round-tripped it through.
     * A SQL {@code NULL} becomes an explicit JSON null -- an explicit clear, not an absent
     * field. Everything else (a BLOB, a driver's own vendor type) gets its string form, which
     * is as much as this source can honestly claim to know about it.
     */
    private static void put(ObjectNode row, String label, Object value) {
        switch (value) {
            case null -> row.putNull(label);
            case Boolean v -> row.put(label, v);
            case BigDecimal v -> row.put(label, v);
            case BigInteger v -> row.put(label, v);
            case Byte v -> row.put(label, v.intValue());
            case Short v -> row.put(label, v.intValue());
            case Integer v -> row.put(label, v);
            case Long v -> row.put(label, v);
            case Float v -> row.put(label, v);
            case Double v -> row.put(label, v);
            case Number v -> row.put(label, v.doubleValue());
            case Timestamp v -> row.put(label, v.toInstant().toString());
            case java.sql.Date v -> row.put(label, v.toLocalDate().toString());
            case Time v -> row.put(label, v.toLocalTime().toString());
            case Instant v -> row.put(label, v.toString());
            case OffsetDateTime v -> row.put(label, v.toInstant().toString());
            case LocalDateTime v -> row.put(label, v.atZone(ZoneId.systemDefault())
                    .toInstant().toString());
            case LocalDate v -> row.put(label, v.toString());
            case LocalTime v -> row.put(label, v.toString());
            default -> row.put(label, String.valueOf(value));
        }
    }

    // ---- the watermark -------------------------------------------------------------------

    /**
     * Record that one incremental row reached AMPS.
     *
     * <p>Called by the batch publisher, on whichever thread flushed. It moves a number and
     * nothing else -- the poll thread is what turns it into a file.
     *
     * @param mark the normalised mark of the acknowledged row
     */
    private void acknowledge(Object mark) {
        acknowledged.accumulateAndGet(mark,
                (current, candidate) -> above(candidate, current) ? candidate : current);
    }

    /**
     * Write the acknowledged mark to {@code state-file}, if it has moved.
     *
     * <p>Temp file plus an atomic move rather than a plain write: a connector killed mid-write
     * would otherwise leave a truncated line, and an unreadable mark means re-reading the
     * whole query on the next start.
     */
    private void persistWatermark() {
        String stateFile = source.getStateFile();
        if (stateFile == null || stateFile.isBlank()) {
            return;
        }
        Object mark = acknowledged.get();
        if (mark == null || mark.equals(persisted)) {
            return;
        }
        Path target = Path.of(stateFile).toAbsolutePath();
        Path directory = target.getParent();
        try {
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
            Files.writeString(temp, mark + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems (a bind-mounted volume, a network share) cannot do it;
                // a plain replace is still better than a partial write.
                log.debug("[{}] atomic move unavailable for {}", connector.getName(), target, e);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            persisted = mark;
            log.debug("[{}] persisted watermark {} to {}", connector.getName(), mark, target);
        } catch (IOException e) {
            // Not fatal: the in-memory mark still works, and the cost of losing this file is
            // re-reading rows AMPS already has after a restart.
            log.warn("[{}] could not write the watermark to {}: {}",
                    connector.getName(), target, e.toString());
        }
    }

    /** Read {@code state-file} back, so a restart resumes past what AMPS already confirmed. */
    private void restoreWatermark() {
        String stateFile = source.getStateFile();
        if (snapshot() || stateFile == null || stateFile.isBlank()) {
            return;
        }
        Path target = Path.of(stateFile).toAbsolutePath();
        if (!Files.isRegularFile(target)) {
            log.info("[{}] no watermark at {} yet: reading the query from the beginning",
                    connector.getName(), target);
            return;
        }
        try {
            String text = Files.readString(target, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                return;
            }
            Object mark = parse(text);
            watermark = mark;
            acknowledged.set(mark);
            persisted = mark;
            log.info("[{}] resuming past watermark {} (from {})",
                    connector.getName(), mark, target);
        } catch (IOException e) {
            log.warn("[{}] could not read the watermark from {}: reading the query from the "
                    + "beginning ({})", connector.getName(), target, e.toString());
        }
    }

    /**
     * Normalise a watermark value so two polls compare like with like.
     *
     * <p>Numbers collapse to {@code BigDecimal} and timestamps to {@code Instant}, so a
     * {@code BIGINT} sequence and a {@code NUMERIC} one order the same way and a driver that
     * hands back {@code LocalDateTime} on one call and {@code Timestamp} on another still
     * makes progress.
     *
     * @param value the raw column value
     * @return the comparable form, or {@code null} for SQL {@code NULL}
     */
    private static Object mark(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal v -> v;
            case Number v -> new BigDecimal(v.toString());
            case Timestamp v -> v.toInstant();
            case OffsetDateTime v -> v.toInstant();
            case LocalDateTime v -> v.atZone(ZoneId.systemDefault()).toInstant();
            default -> value;
        };
    }

    /**
     * The inverse of {@link #mark(Object)} for a persisted line: the same two normalised forms
     * it can produce, then the text as itself.
     *
     * @param text one line of a state file
     * @return the comparable form
     */
    private static Object parse(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException notANumber) {
            log.trace("watermark '{}' is not a number", text);
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            log.trace("watermark '{}' is not an instant", text);
        }
        return text;
    }

    /**
     * Whether {@code candidate} is past the mark. An unset mark is past by definition -- the
     * first poll of an incremental feed with no state file reads everything.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean above(Object candidate, Object mark) {
        if (mark == null) {
            return true;
        }
        if (candidate instanceof Comparable && candidate.getClass() == mark.getClass()) {
            return ((Comparable) candidate).compareTo(mark) > 0;
        }
        // Mixed types out of one column is already odd; string order is the only ordering
        // left that cannot throw.
        return String.valueOf(candidate).compareTo(String.valueOf(mark)) > 0;
    }

    // ---- lifecycle ---------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        closed.set(true);
        connected.set(false);
        Connection open = this.connection;
        this.connection = null;
        if (open != null) {
            try {
                // A query in flight has no timeout; closing the connection under it is what
                // unblocks it, the way closing the socket unblocks the TCP source's read.
                open.close();
            } catch (SQLException e) {
                log.debug("[{}] JDBC connection close failed", connector.getName(), e);
            }
        }
        synchronized (idle) {
            idle.notifyAll();
        }
        Thread runner = this.thread;
        this.thread = null;
        if (runner == null || runner == Thread.currentThread()) {
            return;
        }
        try {
            runner.join(CLOSE_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (runner.isAlive()) {
            log.warn("[{}] JDBC poll thread did not stop within {}ms",
                    connector.getName(), CLOSE_JOIN_MILLIS);
        }
    }

    /** The connection belongs to its poll thread, so only that thread ever closes it here. */
    private void closeQuietly(Connection open) {
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (SQLException e) {
            log.debug("[{}] JDBC connection close failed", connector.getName(), e);
        }
    }

    /**
     * Wait out the poll interval or the reconnect backoff, returning early when
     * {@link #close()} rings the monitor.
     *
     * @param delay how long to wait
     * @return {@code false} if the source should stop instead of carrying on
     */
    private boolean sleep(Duration delay) {
        synchronized (idle) {
            if (closed.get()) {
                return false;
            }
            try {
                idle.wait(Math.max(1L, delay.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !closed.get();
    }
}
