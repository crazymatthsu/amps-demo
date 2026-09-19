package com.demo.amps.connectors.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.JdbcSourceProperties;
import com.demo.amps.connectors.source.SourceRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JDBC source against a real in-memory H2 database -- no mock driver, no containers.
 *
 * <p>What is worth asserting here is everything the source decides for itself: the JSON it
 * synthesises from a result set and the types it keeps, what a key that stopped appearing
 * turns into (and that the removal still carries the columns a SERVER-keyed topic needs to
 * build its filter), and where the incremental mark leaves off -- on disk, only once AMPS has
 * confirmed the row. The driver's own behaviour is H2's to test.
 *
 * <p>Identifiers are quoted in the DDL because H2 folds unquoted ones to upper case: a
 * connector addresses columns by the label {@code ResultSetMetaData} reports, so the tests
 * spell them the way the payload will carry them.
 */
class JdbcRecordSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The connector name every test uses; also the prefix of the thread it must not leak. */
    private static final String NAME = "positions-jdbc";

    /** Fast enough that "the next poll" is not a wait; slow enough not to spin the CPU. */
    private static final Duration POLL = Duration.ofMillis(50);

    /** One database per test, so nothing leaks between them. */
    private static final AtomicInteger DATABASES = new AtomicInteger();

    // ---- fixtures ------------------------------------------------------------------

    /**
     * A fresh in-memory database. {@code DB_CLOSE_DELAY=-1} keeps it alive between the test's
     * own connections and the source's, which open and close independently.
     */
    private static String database() {
        return "jdbc:h2:mem:jdbc-source-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
    }

    private static void execute(String url, String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** Production polls and backs off in seconds; a test that waited that out would be slow. */
    private static ConnectorProperties connector(String url, String query) {
        ConnectorProperties connector = TestConnectors.jdbc(NAME, url, query);
        JdbcSourceProperties jdbc = connector.getSource().getJdbc();
        jdbc.setPollInterval(POLL);
        jdbc.setReconnectDelay(POLL);
        return connector;
    }

    /** The same connector, keyed on {@code columns} joined by the default separator. */
    private static ConnectorProperties keyedOn(
            ConnectorProperties connector, String... columns) {
        connector.getSource().getJdbc().setKeyColumns(List.of(columns));
        return connector;
    }

    /** The same connector, reading forward from {@code column} instead of re-reading. */
    private static ConnectorProperties incremental(
            ConnectorProperties connector, String column) {
        JdbcSourceProperties jdbc = connector.getSource().getJdbc();
        jdbc.setMode(JdbcSourceProperties.Mode.INCREMENTAL);
        jdbc.setIncrementalColumn(column);
        return connector;
    }

    private static JdbcRecordSource started(
            ConnectorProperties connector, List<SourceRecord> received) {
        JdbcRecordSource source = new JdbcRecordSource(connector);
        source.start(received::add);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(source::isConnected);
        return source;
    }

    private static void awaitRecords(List<SourceRecord> received, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= count);
    }

    private static JsonNode payload(SourceRecord record) {
        try {
            return MAPPER.readTree(record.data());
        } catch (IOException e) {
            throw new AssertionError("the source emitted something that is not JSON", e);
        }
    }

    private static String text(SourceRecord record, String field) {
        return payload(record).get(field).asText();
    }

    /** Let several more polls run, for the assertions about what does NOT arrive. */
    private static void severalMorePolls() throws InterruptedException {
        Thread.sleep(POLL.toMillis() * 6);
    }

    private static boolean pollThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals(NAME + "-jdbc") && thread.isAlive());
    }

    private static List<SourceRecord> deletes(List<SourceRecord> received) {
        return received.stream()
                .filter(record -> record.action() == SourceRecord.Action.DELETE)
                .toList();
    }

    // ---- the row, as JSON ------------------------------------------------------------

    @Test
    @DisplayName("a snapshot poll emits every row as JSON, each column in its own type")
    void snapshotEmitsEveryRowWithItsTypes() throws Exception {
        String url = database();
        execute(url,
                "CREATE TABLE positions (\"account\" VARCHAR(16), \"quantity\" INTEGER, "
                        + "\"avg_cost\" DECIMAL(12,4), \"active\" BOOLEAN, "
                        + "\"updated_at\" TIMESTAMP, \"note\" VARCHAR(32))",
                "INSERT INTO positions VALUES ('ACC-1', 250, 101.2500, TRUE, "
                        + "TIMESTAMP '2026-09-18 12:34:56', NULL)");

        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (JdbcRecordSource source =
                new JdbcRecordSource(connector(url, "SELECT * FROM positions"))) {
            source.start(received::add);
            awaitRecords(received, 1);

            SourceRecord record = received.get(0);
            assertThat(record.action()).isEqualTo(SourceRecord.Action.UPSERT);
            assertThat(record.key()).as("no key-columns configured").isNull();
            // A snapshot has no position to remember: the next poll re-reads the row whatever
            // AMPS said about this one, so there is nothing an acknowledgment could advance.
            assertThat(record.ack()).isNull();
            // Which poll a row came from is the only transport metadata a query has.
            assertThat(Long.parseLong(record.attributes().get("poll"))).isGreaterThanOrEqualTo(1);

            JsonNode row = payload(record);
            assertThat(row.get("account").asText()).isEqualTo("ACC-1");
            assertThat(row.get("quantity").isInt()).as("a number, not its text").isTrue();
            assertThat(row.get("quantity").intValue()).isEqualTo(250);
            assertThat(row.get("avg_cost").isNumber()).isTrue();
            assertThat(row.get("avg_cost").decimalValue()).isEqualByComparingTo("101.2500");
            assertThat(row.get("active").isBoolean()).isTrue();
            assertThat(row.get("active").booleanValue()).isTrue();
            // ISO-8601 in its Instant form, resolved through the zone the database
            // round-tripped the value in -- which is what Timestamp.toInstant() uses.
            assertThat(row.get("updated_at").asText())
                    .isEqualTo(Timestamp.valueOf("2026-09-18 12:34:56").toInstant().toString());
            // An explicit null, which is "present, and cleared" -- not an absent field, which
            // would leave the field untouched in a delta publish.
            assertThat(row.get("note").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("a composite key is the key columns joined by the separator")
    void compositeKeysAreJoinedWithTheSeparator() throws Exception {
        String url = database();
        execute(url,
                "CREATE TABLE positions (\"account\" VARCHAR(16), \"symbol\" VARCHAR(16), "
                        + "\"quantity\" INTEGER)",
                "INSERT INTO positions VALUES ('ACC-1', 'AAPL', 250)",
                "INSERT INTO positions VALUES ('ACC-1', 'MSFT', 100)");

        ConnectorProperties connector = keyedOn(
                connector(url, "SELECT * FROM positions ORDER BY \"symbol\""),
                "account", "symbol");

        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (JdbcRecordSource source = new JdbcRecordSource(connector)) {
            source.start(received::add);
            awaitRecords(received, 2);

            // Joined here rather than in the operator's SQL, so the payload carries the
            // columns themselves and not a synthetic key column AMPS would have to store.
            // A copy, because the poll thread keeps appending to `received` and a subList
            // view of a live CopyOnWriteArrayList throws on the next append.
            List<SourceRecord> firstPoll = List.copyOf(received).subList(0, 2);
            assertThat(firstPoll).extracting(SourceRecord::key)
                    .containsExactly("ACC-1|AAPL", "ACC-1|MSFT");
            assertThat(firstPoll).extracting(SourceRecord::action)
                    .containsOnly(SourceRecord.Action.UPSERT);
            assertThat(payload(firstPoll.get(0)).has("account")).isTrue();
        }
    }

    @Test
    @DisplayName("a row whose key column is NULL is published unkeyed rather than dropped")
    void aNullKeyColumnIsPublishedUnkeyed() throws Exception {
        String url = database();
        execute(url,
                "CREATE TABLE positions (\"account\" VARCHAR(16), \"symbol\" VARCHAR(16), "
                        + "\"quantity\" INTEGER)",
                "INSERT INTO positions VALUES ('ACC-1', 'AAPL', 250)",
                "INSERT INTO positions VALUES ('ACC-2', NULL, 100)");

        ConnectorProperties connector = keyedOn(
                connector(url, "SELECT * FROM positions ORDER BY \"account\""),
                "account", "symbol");

        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (JdbcRecordSource source = new JdbcRecordSource(connector)) {
            source.start(received::add);
            awaitRecords(received, 2);

            // Published, because a bridge that silently swallowed a row would be worse than
            // one that publishes it unkeyed and lets the connector's key mode decide.
            assertThat(received.get(0).key()).isEqualTo("ACC-1|AAPL");
            assertThat(received.get(1).key()).isNull();
            assertThat(text(received.get(1), "account")).isEqualTo("ACC-2");
        }
    }

    // ---- what a snapshot can say about a row that left -------------------------------

    @Test
    @DisplayName("a row deleted between polls arrives as a DELETE carrying just its key columns")
    void aVanishedKeyBecomesADeleteCarryingItsKeyColumns() throws Exception {
        String url = database();
        execute(url,
                "CREATE TABLE positions (\"account\" VARCHAR(16), \"symbol\" VARCHAR(16), "
                        + "\"quantity\" INTEGER)",
                "INSERT INTO positions VALUES ('ACC-1', 'AAPL', 250)",
                "INSERT INTO positions VALUES ('ACC-1', 'MSFT', 100)");

        ConnectorProperties connector = keyedOn(
                connector(url, "SELECT * FROM positions ORDER BY \"symbol\""),
                "account", "symbol");

        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (JdbcRecordSource source = new JdbcRecordSource(connector)) {
            source.start(received::add);
            awaitRecords(received, 2);
            execute(url, "DELETE FROM positions WHERE \"symbol\" = 'MSFT'");

            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> !deletes(received).isEmpty());

            SourceRecord delete = deletes(received).get(0);
            assertThat(delete.key()).isEqualTo("ACC-1|MSFT");
            // The key columns and nothing else: a PUBLISHER-keyed connector deletes by the
            // key above, but a SERVER-keyed one has to build "/account = 'ACC-1' AND
            // /symbol = 'MSFT'" -- and it can only do that from a payload that still has them.
            JsonNode removed = payload(delete);
            assertThat(removed.size()).as("the key columns, and nothing else").isEqualTo(2);
            assertThat(removed.get("account").asText()).isEqualTo("ACC-1");
            assertThat(removed.get("symbol").asText()).isEqualTo("MSFT");
            assertThat(removed.has("quantity")).as("the row is gone; only its key survives")
                    .isFalse();

            // Said once, not once per poll: the key set is replaced after every poll.
            severalMorePolls();
            assertThat(deletes(received)).hasSize(1);
        }
    }

    // ---- incremental -------------------------------------------------------------------

    @Test
    @DisplayName("an incremental poll emits only what is above the mark, and never again")
    void incrementalEmitsOnlyAboveTheMark() throws Exception {
        String url = database();
        execute(url,
                "CREATE TABLE trades (\"trade_id\" VARCHAR(16), \"seq\" BIGINT)",
                "INSERT INTO trades VALUES ('T-1', 1)",
                "INSERT INTO trades VALUES ('T-2', 2)",
                // A row that cannot be placed against the mark at all.
                "INSERT INTO trades VALUES ('T-UNSEQUENCED', NULL)");

        ConnectorProperties connector =
                incremental(connector(url, "SELECT * FROM trades ORDER BY \"seq\""), "seq");

        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (JdbcRecordSource source = new JdbcRecordSource(connector)) {
            source.start(received::add);
            // Poll 1: no mark yet, so everything the column can order.
            awaitRecords(received, 2);
            // Poll 2 and onwards: nothing is above the mark, so nothing is emitted.
            severalMorePolls();
            assertThat(received).as("the rows already read are never re-emitted").hasSize(2);

            execute(url, "INSERT INTO trades VALUES ('T-3', 3)");
            awaitRecords(received, 3);
            severalMorePolls();

            assertThat(received).hasSize(3);
            assertThat(received).extracting(record -> text(record, "trade_id"))
                    // Skipped, and skipped quietly after the first warning: emitting it would
                    // re-emit it on every poll for as long as it sits in the table.
                    .containsExactly("T-1", "T-2", "T-3");
            assertThat(received).extracting(SourceRecord::action)
                    .containsOnly(SourceRecord.Action.UPSERT);
            // Unlike a snapshot row, this one has a position worth remembering.
            assertThat(received).extracting(SourceRecord::ack).doesNotContainNull();
        }
    }

    @Test
    @DisplayName("the watermark is written when a row is acknowledged, and resumed from on restart")
    void theStateFileIsWrittenOnAcknowledgmentAndResumedFrom(@TempDir Path directory)
            throws Exception {
        String url = database();
        execute(url,
                "CREATE TABLE trades (\"trade_id\" VARCHAR(16), \"seq\" BIGINT)",
                "INSERT INTO trades VALUES ('T-1', 1)",
                "INSERT INTO trades VALUES ('T-2', 2)");

        // Under a directory that does not exist yet: a connector's state directory is created
        // by the connector, not by whoever wrote the deployment.
        Path stateFile = directory.resolve("state").resolve("trades.watermark");
        ConnectorProperties connector =
                incremental(connector(url, "SELECT * FROM trades ORDER BY \"seq\""), "seq");
        connector.getSource().getJdbc().setStateFile(stateFile.toString());

        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (JdbcRecordSource source = new JdbcRecordSource(connector)) {
            source.start(received::add);
            awaitRecords(received, 2);
            severalMorePolls();

            // Read is not published: a mark written here would skip rows whose batch never
            // reached AMPS.
            assertThat(stateFile).doesNotExist();

            received.get(0).acknowledge();
            awaitWatermark(stateFile, "1");
            received.get(1).acknowledge();
            awaitWatermark(stateFile, "2");
        }

        execute(url, "INSERT INTO trades VALUES ('T-3', 3)");

        List<SourceRecord> resumed = new CopyOnWriteArrayList<>();
        try (JdbcRecordSource source = new JdbcRecordSource(connector)) {
            source.start(resumed::add);
            awaitRecords(resumed, 1);
            severalMorePolls();

            // The point of the file: a restarted connector reads forward from what AMPS
            // confirmed instead of re-publishing the table into a journal topic.
            assertThat(resumed).hasSize(1);
            assertThat(text(resumed.get(0), "trade_id")).isEqualTo("T-3");
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------

    @Test
    @DisplayName("a poll that fails reconnects on the next backoff, and emits no deletes for it")
    void aFailedPollReconnectsWithoutEmittingDeletes() throws Exception {
        String url = database();
        String create = "CREATE TABLE positions (\"account\" VARCHAR(16), "
                + "\"symbol\" VARCHAR(16), \"quantity\" INTEGER)";
        String fill = "INSERT INTO positions VALUES ('ACC-1', 'AAPL', 250)";
        execute(url, create, fill);

        ConnectorProperties connector = keyedOn(
                connector(url, "SELECT * FROM positions"), "account", "symbol");

        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (JdbcRecordSource source = new JdbcRecordSource(connector)) {
            source.start(received::add);
            awaitRecords(received, 1);

            // The query stops working under the source: the same event as a database that
            // went away, and it must not be read as "every row was deleted".
            execute(url, "DROP TABLE positions");
            severalMorePolls();
            int beforeRecovery = received.size();

            execute(url, create, fill);
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> received.size() > beforeRecovery);

            assertThat(source.isConnected()).as("reconnected on the backoff").isTrue();
            // The key set is only replaced by a poll that finished, so a failed one leaves
            // the last good one standing -- otherwise a hiccup would empty the SOW.
            assertThat(deletes(received)).isEmpty();
        }
    }

    @Test
    @DisplayName("close() cuts the poll interval short instead of waiting it out")
    void closeStopsThePollThreadMidInterval() throws Exception {
        String url = database();
        execute(url,
                "CREATE TABLE positions (\"account\" VARCHAR(16))",
                "INSERT INTO positions VALUES ('ACC-1')");

        ConnectorProperties connector = connector(url, "SELECT * FROM positions");
        // Long enough that a close waiting out the interval would be obvious.
        connector.getSource().getJdbc().setPollInterval(Duration.ofSeconds(30));

        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        JdbcRecordSource source = started(connector, received);
        awaitRecords(received, 1);

        long startedAt = System.nanoTime();
        source.close();
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(took).as("close() joins within its 5s budget").isLessThan(Duration.ofSeconds(5));
        assertThat(source.isConnected()).isFalse();
        assertThat(pollThreadAlive()).as("no connector thread is left behind").isFalse();
        // Idempotent, the way ConnectorManager calls it.
        source.close();
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Wait for the state file to say exactly {@code mark} -- it is written after a poll. */
    private static void awaitWatermark(Path stateFile, String mark) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> Files.isRegularFile(stateFile)
                && Files.readString(stateFile, StandardCharsets.UTF_8).strip().equals(mark));
    }
}
