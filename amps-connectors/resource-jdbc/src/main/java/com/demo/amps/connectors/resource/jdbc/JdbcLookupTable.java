package com.demo.amps.connectors.resource.jdbc;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.config.JdbcResourceProperties;
import com.demo.amps.connectors.config.ResourceProperties;
import com.demo.amps.connectors.jdbc.JdbcValues;
import com.demo.amps.connectors.resource.AppResource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A database query held in memory as a table a transform looks rows up in by key.
 *
 * <p>The {@link AppResource} that {@code resources[].jdbc} configures. One query is the
 * whole table: every load runs it, turns each row into the same value map the JDBC source
 * publishes ({@link JdbcValues#row}), keys it by the {@code key-columns} joined with
 * {@code key-separator}, and swaps the result in as one immutable {@link Snapshot}. A lookup
 * reads whichever snapshot is current and nothing else, so a record being enriched on a
 * source's thread never sees a half-loaded table and never waits for a load in progress.
 *
 * <p><strong>A failed reload keeps the last good copy.</strong> A reference table that goes
 * empty because the database blinked would turn every record into a miss, and the miss would
 * look exactly like an unknown symbol; keeping the previous snapshot -- and saying so, as
 * {@code RESOURCE_RELOAD_FAILED} with a {@code failures} count on the status line -- is the
 * honest failure. Only the load in {@link #start()} has nothing to fall back on: it raises
 * {@code RESOURCE_LOAD_FAILED} at ERROR, the table starts {@linkplain #isAvailable()
 * unavailable}, and the reload timer keeps trying on the {@code reconnect-delay}.
 *
 * <p>Reloads come from two places -- a timer every {@code reload-interval} (none when it is
 * zero: reference data that changes when someone says so is reloaded by the control
 * channel's {@code reload} command) and {@link #reload()} on demand -- and they are
 * serialised, so a command landing mid-timer does not run the same query twice at once.
 * Connections are opened per load and closed with it: a table read every five minutes has no
 * use for a connection held open in between, and a dropped one is discovered by the load that
 * dials rather than babysat by a thread.
 *
 * <p>The key is the key columns' values as the driver prints them, joined -- the same rule
 * {@code JdbcRecordSource} keys its records by, so a source and a resource on the same table
 * agree. A row with a {@code NULL} key column cannot be looked up and is skipped, once-warned
 * per load; a key that appears twice keeps the last row, once-warned too, because the query's
 * key columns were meant to be unique and a silent overwrite would be the quiet kind of wrong.
 */
public final class JdbcLookupTable implements AppResource {

    private static final Logger log = LoggerFactory.getLogger(JdbcLookupTable.class);

    /** Raised, at ERROR, when the load in {@link #start()} throws: the table starts unavailable. */
    public static final String RESOURCE_LOAD_FAILED = "RESOURCE_LOAD_FAILED";

    /** Raised, at WARN, when a reload throws: the previous snapshot stays in force. */
    public static final String RESOURCE_RELOAD_FAILED = "RESOURCE_RELOAD_FAILED";

    /** How long {@link #stop()} waits for the reload thread before giving up on it. */
    private static final long STOP_JOIN_MILLIS = 5_000;

    /**
     * The table at one moment: what one query returned, keyed, and when it returned it.
     * Built by one load and never touched again, which is what lets readers hold it lock-free.
     *
     * @param rows key to row, in result-set order; unmodifiable, as is every row
     * @param loadedAt when the query finished
     */
    record Snapshot(Map<String, Map<String, Object>> rows, Instant loadedAt) {
    }

    private final String name;
    private final JdbcResourceProperties jdbc;
    private final Alerts alerts;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final AtomicLong reloads = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /** Serialises loads: a command's reload and the timer's never run the query at once. */
    private final Object loading = new Object();

    /** Monitor the reload thread waits on, so {@link #stop()} cuts an interval short. */
    private final Object idle = new Object();

    private volatile boolean stopped;
    private volatile Thread thread;

    /**
     * @param resource the entry; its {@code jdbc} block is what this table reads
     * @param alerts where a failed load or reload is reported
     * @throws IllegalArgumentException if the entry has no {@code jdbc} block
     */
    public JdbcLookupTable(ResourceProperties resource, Alerts alerts) {
        this.name = Objects.requireNonNull(resource.getName(), "resource name");
        this.jdbc = resource.getJdbc();
        if (jdbc == null) {
            throw new IllegalArgumentException(resource + " has no jdbc block to load from");
        }
        this.alerts = Objects.requireNonNull(alerts, "alerts");
    }

    @Override
    public String name() {
        return name;
    }

    // ---- lifecycle ---------------------------------------------------------------------

    /**
     * Load once, on the caller's thread, then start the reload timer.
     *
     * <p>Synchronous on purpose: the registry starts resources before the connectors, and a
     * transform's first record should find the table there. A failed load is not thrown --
     * it is logged, raised as {@code RESOURCE_LOAD_FAILED} and left to the timer -- because
     * a database that is not up yet is the same event as one that went away, and both belong
     * in the same backoff.
     */
    @Override
    public void start() {
        stopped = false;
        log.info("[{}] loading from {} keyed by {}", name, jdbc.getUrl(), jdbc.getKeyColumns());
        try {
            load();
        } catch (SQLException | RuntimeException e) {
            failures.incrementAndGet();
            log.error("[{}] load failed; the table is unavailable until a reload succeeds",
                    name, e);
            alerts.raise(Alert.of(Alert.Severity.ERROR, RESOURCE_LOAD_FAILED,
                    "resource '" + name + "' failed to load: " + e).withDetails(details(e)));
        }
        startTimer();
    }

    private void startTimer() {
        Duration interval = jdbc.getReloadInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            log.info("[{}] no reload timer (reload-interval is {}): reloads come from the "
                    + "control channel", name, interval);
            return;
        }
        Thread runner = new Thread(this::runReloads, name + "-reload");
        runner.setDaemon(true);
        this.thread = runner;
        runner.start();
    }

    /**
     * Wait, reload, wait again -- {@code reload-interval} after a load that worked,
     * {@code reconnect-delay} after one that did not, until {@link #stop()}.
     */
    private void runReloads() {
        // A table that never loaded is retried on the reconnect delay: the interval is a
        // schedule for a table that exists.
        Duration next = isAvailable() ? jdbc.getReloadInterval() : jdbc.getReconnectDelay();
        while (sleep(next)) {
            next = tryReload() ? jdbc.getReloadInterval() : jdbc.getReconnectDelay();
        }
        log.info("[{}] reload timer stopped", name);
    }

    /** {@link #reload()} has already logged and alerted; the timer only needs to know. */
    private boolean tryReload() {
        try {
            reload();
            return true;
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void stop() {
        synchronized (idle) {
            stopped = true;
            idle.notifyAll();
        }
        Thread runner = this.thread;
        this.thread = null;
        if (runner == null || runner == Thread.currentThread()) {
            return;
        }
        try {
            runner.join(STOP_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (runner.isAlive()) {
            log.warn("[{}] reload thread did not stop within {}ms: a load is still running "
                    + "and ends with its query", name, STOP_JOIN_MILLIS);
        }
    }

    @Override
    public boolean isAvailable() {
        return snapshot.get() != null;
    }

    @Override
    public boolean isReloadable() {
        return true;
    }

    /**
     * Run the query again and swap the table, on the calling thread.
     *
     * @throws SQLException if the load failed; the previous snapshot (if any) stays in
     *     force, the failure is counted and raised as {@code RESOURCE_RELOAD_FAILED}
     */
    @Override
    public void reload() throws SQLException {
        synchronized (loading) {
            Snapshot previous = snapshot.get();
            try {
                load();
                reloads.incrementAndGet();
            } catch (SQLException | RuntimeException e) {
                failures.incrementAndGet();
                String kept = previous == null
                        ? "the table is still unavailable"
                        : "keeping the " + previous.rows().size() + " row(s) loaded at "
                                + previous.loadedAt();
                log.warn("[{}] reload failed; {}", name, kept, e);
                Map<String, Object> details = details(e);
                details.put("available", previous != null);
                if (previous != null) {
                    details.put("rows", previous.rows().size());
                    details.put("loaded", previous.loadedAt().toString());
                }
                alerts.raise(Alert.of(Alert.Severity.WARN, RESOURCE_RELOAD_FAILED,
                        "resource '" + name + "' failed to reload, " + kept + ": " + e)
                        .withDetails(details));
                throw e;
            }
        }
    }

    // ---- lookups -----------------------------------------------------------------------

    /**
     * The row with this key, from the current snapshot.
     *
     * @param key the key columns' values joined by the separator, e.g. {@code AAPL} or
     *     {@code ACC-1|AAPL}; {@code null} finds nothing
     * @return the row -- column label to value, unmodifiable -- or empty when there is no
     *     such row or no snapshot yet
     */
    public Optional<Map<String, Object>> find(String key) {
        Snapshot current = snapshot.get();
        if (current == null || key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(current.rows().get(key));
    }

    /** How many keyed rows the current snapshot holds; {@code 0} before the first load. */
    public int size() {
        Snapshot current = snapshot.get();
        return current == null ? 0 : current.rows().size();
    }

    /** When the current snapshot was loaded, or {@code null} if nothing ever loaded. */
    public Instant loadedAt() {
        Snapshot current = snapshot.get();
        return current == null ? null : current.loadedAt();
    }

    /** Loads after the first that succeeded, from the timer and from {@link #reload()}. */
    public long reloads() {
        return reloads.get();
    }

    /** Loads that threw, the one in {@link #start()} included. */
    public long failures() {
        return failures.get();
    }

    /**
     * @return e.g. {@code instruments AVAILABLE rows=1234 loaded=2026-09-19T14:00:00Z
     *     reloads=3 failures=0}, or {@code instruments UNAVAILABLE rows=0 loaded=never
     *     reloads=0 failures=1}
     */
    @Override
    public String status() {
        Snapshot current = snapshot.get();
        String state = current == null
                ? " UNAVAILABLE rows=0 loaded=never"
                : " AVAILABLE rows=" + current.rows().size()
                        + " loaded=" + current.loadedAt().truncatedTo(ChronoUnit.SECONDS);
        return name + state + " reloads=" + reloads.get() + " failures=" + failures.get();
    }

    // ---- the load ----------------------------------------------------------------------

    /** Run the query and make its result the table. Holds the load lock. */
    private void load() throws SQLException {
        synchronized (loading) {
            long started = System.nanoTime();
            Snapshot loaded = query();
            snapshot.set(loaded);
            log.info("[{}] loaded {} row(s) in {} ms", name, loaded.rows().size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    /**
     * One connection, one statement, one result set, one snapshot.
     *
     * <p>The key is read through {@link ResultSet#getObject(String)} per key column, the way
     * the JDBC source reads it: the driver matches the label case-insensitively, and a key
     * column the query did not return fails the load with the driver's own message rather
     * than quietly skipping every row as "keyless".
     */
    private Snapshot query() throws SQLException {
        List<String> keyColumns = jdbc.getKeyColumns();
        String separator = jdbc.getKeySeparator();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        int nullKeys = 0;
        int duplicates = 0;
        try (Connection connection = JdbcValues.connect(
                        jdbc.getUrl(), jdbc.getUsername(), jdbc.getPassword());
                Statement statement = connection.createStatement()) {
            statement.setFetchSize(jdbc.getFetchSize());
            try (ResultSet results = statement.executeQuery(jdbc.getQuery())) {
                ResultSetMetaData meta = results.getMetaData();
                while (results.next()) {
                    String key = keyOf(results, keyColumns, separator);
                    if (key == null) {
                        nullKeys++;
                        continue;
                    }
                    Map<String, Object> row =
                            Collections.unmodifiableMap(JdbcValues.row(results, meta));
                    if (rows.put(key, row) != null) {
                        duplicates++;
                    }
                }
            }
        }
        if (nullKeys > 0) {
            log.warn("[{}] skipped {} row(s) with a NULL value among key columns {}: a row "
                    + "without a key cannot be looked up", name, nullKeys, keyColumns);
        }
        if (duplicates > 0) {
            log.warn("[{}] {} row(s) shared a key with an earlier row and replaced it: key "
                    + "columns {} are not unique in this query", name, duplicates, keyColumns);
        }
        return new Snapshot(Collections.unmodifiableMap(rows), Instant.now());
    }

    /**
     * The current row's key: the key columns' values, as the driver prints them, joined.
     *
     * @return the key, or {@code null} when any key column is NULL
     */
    private static String keyOf(ResultSet results, List<String> keyColumns, String separator)
            throws SQLException {
        StringBuilder key = new StringBuilder();
        for (int column = 0; column < keyColumns.size(); column++) {
            Object value = results.getObject(keyColumns.get(column));
            if (value == null) {
                return null;
            }
            if (column > 0) {
                key.append(separator);
            }
            key.append(value);
        }
        return key.toString();
    }

    private Map<String, Object> details(Exception e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resource", name);
        details.put("error", e.toString());
        return details;
    }

    /**
     * Wait out an interval, returning early when {@link #stop()} rings the monitor.
     *
     * @param delay how long to wait
     * @return {@code true} to reload now, {@code false} to stop instead
     */
    private boolean sleep(Duration delay) {
        long waitNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, delay.toMillis()));
        long started = System.nanoTime();
        synchronized (idle) {
            while (!stopped) {
                long remaining = waitNanos - (System.nanoTime() - started);
                if (remaining <= 0) {
                    return true;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(idle, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    @Override
    public String toString() {
        return "JdbcLookupTable[" + status() + "]";
    }
}
