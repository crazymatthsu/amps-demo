package com.demo.amps.connectors.resource.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.config.JdbcResourceProperties;
import com.demo.amps.connectors.config.ResourceProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lookup table against a real in-memory H2 database -- no mock driver, no containers.
 *
 * <p>What is worth asserting here is everything the resource decides for itself: what a load
 * keeps and how it keys it, that a reload swaps the whole table and a failed one swaps
 * nothing, what the timer does after a failure, and what the status line and the alerts say
 * about all of it. The driver's own behaviour is H2's to test.
 *
 * <p>Identifiers are quoted in the DDL because H2 folds unquoted ones to upper case: a
 * transform addresses columns by the label {@code ResultSetMetaData} reports, so the tests
 * spell them the way the row will carry them.
 */
class JdbcLookupTableTest {

    private static final String NAME = "instruments";

    /** Fast enough that "the next reload" is not a wait; slow enough not to spin the CPU. */
    private static final Duration TICK = Duration.ofMillis(50);

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private static final String INSTRUMENTS = "CREATE TABLE instruments (\"symbol\" VARCHAR(16), "
            + "\"sedol\" VARCHAR(7), \"currency\" VARCHAR(3), \"lot_size\" INTEGER)";
    private static final String AAPL =
            "INSERT INTO instruments VALUES ('AAPL', '2046251', 'USD', 100)";
    private static final String VOD =
            "INSERT INTO instruments VALUES ('VOD', 'BH4HKS3', 'GBP', 1)";
    private static final String MSFT =
            "INSERT INTO instruments VALUES ('MSFT', '2588173', 'USD', 100)";
    private static final String SELECT_ALL = "SELECT * FROM instruments";

    /** One database per test, so nothing leaks between them. */
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final List<Alert> alerts = new CopyOnWriteArrayList<>();

    // ---- fixtures ------------------------------------------------------------------

    /**
     * A fresh in-memory database. {@code DB_CLOSE_DELAY=-1} keeps it alive between the test's
     * own connections and the table's, which open and close independently.
     */
    private static String database() {
        return "jdbc:h2:mem:lookup-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
    }

    private static String seeded(String... statements) throws SQLException {
        String url = database();
        execute(url, statements);
        return url;
    }

    private static void execute(String url, String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** Timer off, backoff short: a test that waited production's minutes out would be slow. */
    private static ResourceProperties resource(String url, String query, String... keyColumns) {
        ResourceProperties resource = new ResourceProperties();
        resource.setName(NAME);
        JdbcResourceProperties jdbc = new JdbcResourceProperties();
        jdbc.setUrl(url);
        jdbc.setQuery(query);
        jdbc.setKeyColumns(List.of(keyColumns));
        jdbc.setReloadInterval(Duration.ZERO);
        jdbc.setReconnectDelay(TICK);
        resource.setJdbc(jdbc);
        return resource;
    }

    /** The same entry, reloading on the timer every tick. */
    private static ResourceProperties timed(ResourceProperties resource) {
        resource.getJdbc().setReloadInterval(TICK);
        return resource;
    }

    private JdbcLookupTable started(ResourceProperties resource) {
        JdbcLookupTable table = new JdbcLookupTable(resource, alerts::add);
        table.start();
        return table;
    }

    private static Optional<Thread> reloadThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals(NAME + "-reload") && thread.isAlive())
                .findFirst();
    }

    private List<Alert> alerts(String code) {
        return alerts.stream().filter(alert -> alert.code().equals(code)).toList();
    }

    // ---- loading and looking up ------------------------------------------------------

    @Test
    @DisplayName("start() loads the query and rows are found by key, typed, in column order")
    void loadsOnStartAndFindsRowsByKey() throws Exception {
        String url = seeded(INSTRUMENTS, AAPL, VOD);
        Instant before = Instant.now();
        JdbcLookupTable table = started(resource(url, SELECT_ALL, "symbol"));
        try {
            assertThat(table.name()).isEqualTo(NAME);
            assertThat(table.isAvailable()).isTrue();
            assertThat(table.isReloadable()).isTrue();
            assertThat(table.size()).isEqualTo(2);
            assertThat(table.loadedAt()).isNotNull().isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(Instant.now());
            assertThat(table.reloads()).isZero();
            assertThat(table.failures()).isZero();
            assertThat(alerts).isEmpty();

            Map<String, Object> aapl = table.find("AAPL").orElseThrow();
            // The whole row, key columns included, labels in query order, a number a number.
            assertThat(aapl).containsExactly(entry("symbol", "AAPL"), entry("sedol", "2046251"),
                    entry("currency", "USD"), entry("lot_size", 100));
            assertThat(table.find("VOD").orElseThrow()).containsEntry("currency", "GBP");
            assertThat(table.find("XYZ")).isEmpty();
            assertThat(table.find(null)).isEmpty();
            // A snapshot is immutable all the way down: a transform cannot edit the table
            // for the record behind it.
            assertThatThrownBy(() -> aapl.put("currency", "EUR"))
                    .isInstanceOf(UnsupportedOperationException.class);
        } finally {
            table.stop();
        }
    }

    @Test
    @DisplayName("reload() picks up an insert and counts as a reload")
    void reloadPicksUpAnInsert() throws Exception {
        String url = seeded(INSTRUMENTS, AAPL, VOD);
        JdbcLookupTable table = started(resource(url, SELECT_ALL, "symbol"));
        try {
            execute(url, MSFT);
            assertThat(table.find("MSFT")).as("a snapshot does not see the database").isEmpty();
            Instant first = table.loadedAt();

            table.reload();

            assertThat(table.size()).isEqualTo(3);
            assertThat(table.find("MSFT").orElseThrow()).containsEntry("sedol", "2588173");
            assertThat(table.loadedAt()).isAfterOrEqualTo(first);
            assertThat(table.reloads()).isEqualTo(1);
            assertThat(table.failures()).isZero();
            assertThat(alerts).isEmpty();
        } finally {
            table.stop();
        }
    }

    @Test
    @DisplayName("a failed reload keeps the previous snapshot and raises RESOURCE_RELOAD_FAILED")
    void failedReloadKeepsTheSnapshot() throws Exception {
        String url = seeded(INSTRUMENTS, AAPL, VOD);
        JdbcLookupTable table = started(resource(url, SELECT_ALL, "symbol"));
        try {
            Instant loaded = table.loadedAt();
            execute(url, "DROP TABLE instruments");

            assertThatThrownBy(table::reload).isInstanceOf(SQLException.class);

            // The table the transforms are reading is exactly the one they were reading.
            assertThat(table.isAvailable()).isTrue();
            assertThat(table.size()).isEqualTo(2);
            assertThat(table.find("AAPL")).isPresent();
            assertThat(table.loadedAt()).isEqualTo(loaded);
            assertThat(table.reloads()).isZero();
            assertThat(table.failures()).isEqualTo(1);

            assertThat(alerts).hasSize(1);
            Alert alert = alerts.get(0);
            assertThat(alert.code()).isEqualTo(JdbcLookupTable.RESOURCE_RELOAD_FAILED);
            assertThat(alert.severity()).isEqualTo(Alert.Severity.WARN);
            assertThat(alert.message()).contains("'instruments'").contains("keeping the 2 row(s)");
            assertThat(alert.details())
                    .containsEntry("resource", NAME)
                    .containsEntry("available", true)
                    .containsEntry("rows", 2)
                    .containsEntry("loaded", loaded.toString());
            assertThat(alert.details().get("error").toString()).containsIgnoringCase("instruments");
        } finally {
            table.stop();
        }
    }

    @Test
    @DisplayName("a database that is not there leaves the table unavailable, with RESOURCE_LOAD_FAILED, until a reload succeeds")
    void badUrlLeavesTheTableUnavailable() throws Exception {
        String name = "lookup-missing-" + DATABASES.incrementAndGet();
        // IFEXISTS refuses to create the database, so this dials one that does not exist yet.
        ResourceProperties resource =
                resource("jdbc:h2:mem:" + name + ";IFEXISTS=TRUE", SELECT_ALL, "symbol");
        JdbcLookupTable table = new JdbcLookupTable(resource, alerts::add);
        try {
            // Not thrown: an absent database is a condition, not a construction error.
            table.start();

            assertThat(table.isAvailable()).isFalse();
            assertThat(table.size()).isZero();
            assertThat(table.loadedAt()).isNull();
            assertThat(table.find("AAPL")).isEmpty();
            assertThat(table.failures()).isEqualTo(1);
            assertThat(table.reloads()).isZero();
            assertThat(table.status()).isEqualTo(
                    "instruments UNAVAILABLE rows=0 loaded=never reloads=0 failures=1");
            assertThat(reloadThread()).as("no timer when reload-interval is zero").isEmpty();

            assertThat(alerts).hasSize(1);
            Alert alert = alerts.get(0);
            assertThat(alert.code()).isEqualTo(JdbcLookupTable.RESOURCE_LOAD_FAILED);
            assertThat(alert.severity()).isEqualTo(Alert.Severity.ERROR);
            assertThat(alert.message()).contains("'instruments'");
            assertThat(alert.details()).containsEntry("resource", NAME).containsKey("error");

            // A reload that fails while nothing is loaded says so, at WARN.
            assertThatThrownBy(table::reload).isInstanceOf(SQLException.class);
            assertThat(table.failures()).isEqualTo(2);
            assertThat(alerts(JdbcLookupTable.RESOURCE_RELOAD_FAILED)).singleElement()
                    .satisfies(reload -> {
                        assertThat(reload.severity()).isEqualTo(Alert.Severity.WARN);
                        assertThat(reload.message()).contains("still unavailable");
                        assertThat(reload.details()).containsEntry("available", false)
                                .doesNotContainKey("rows");
                    });

            // The database appears; the next reload is the first load.
            execute("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", INSTRUMENTS, AAPL);
            table.reload();
            assertThat(table.isAvailable()).isTrue();
            assertThat(table.find("AAPL")).isPresent();
            assertThat(table.reloads()).isEqualTo(1);
            assertThat(table.status()).startsWith("instruments AVAILABLE rows=1 loaded=")
                    .endsWith(" reloads=1 failures=2");
        } finally {
            table.stop();
        }
    }

    @Test
    @DisplayName("a key column the query does not return fails the load rather than skipping every row")
    void missingKeyColumnFailsTheLoad() throws Exception {
        String url = seeded(INSTRUMENTS, AAPL);
        JdbcLookupTable table = started(resource(url, SELECT_ALL, "ticker"));
        try {
            assertThat(table.isAvailable()).isFalse();
            assertThat(table.failures()).isEqualTo(1);
            assertThat(alerts(JdbcLookupTable.RESOURCE_LOAD_FAILED)).singleElement()
                    .satisfies(alert -> assertThat(alert.details().get("error").toString())
                            .containsIgnoringCase("ticker"));
        } finally {
            table.stop();
        }
    }

    // ---- keys ----------------------------------------------------------------------------

    @Test
    @DisplayName("a composite key is the key columns joined by the separator, in order")
    void compositeKeyIsJoinedBySeparator() throws Exception {
        String url = seeded(
                "CREATE TABLE limits (\"account\" VARCHAR(16), \"symbol\" VARCHAR(16), "
                        + "\"max_qty\" INTEGER)",
                "INSERT INTO limits VALUES ('ACC-1', 'AAPL', 1000)",
                "INSERT INTO limits VALUES ('ACC-1', 'VOD', 500)",
                "INSERT INTO limits VALUES ('ACC-2', 'AAPL', 50)");
        ResourceProperties resource = resource(url, "SELECT * FROM limits", "account", "symbol");
        resource.getJdbc().setKeySeparator(":");
        JdbcLookupTable table = started(resource);
        try {
            assertThat(table.size()).isEqualTo(3);
            assertThat(table.find("ACC-1:AAPL").orElseThrow()).containsEntry("max_qty", 1000);
            assertThat(table.find("ACC-2:AAPL").orElseThrow()).containsEntry("max_qty", 50);
            assertThat(table.find("AAPL:ACC-1")).as("the configured order, not the query's")
                    .isEmpty();
            assertThat(table.find("ACC-1|AAPL")).as("the configured separator").isEmpty();
        } finally {
            table.stop();
        }
    }

    @Test
    @DisplayName("a row whose key column is NULL is skipped, and the rest of the load stands")
    void nullKeyRowIsSkipped() throws Exception {
        String url = seeded(INSTRUMENTS, AAPL,
                "INSERT INTO instruments VALUES (NULL, '0000000', 'EUR', 1)", VOD);
        JdbcLookupTable table = started(resource(url, SELECT_ALL, "symbol"));
        try {
            assertThat(table.isAvailable()).isTrue();
            assertThat(table.size()).isEqualTo(2);
            assertThat(table.find("AAPL")).isPresent();
            assertThat(table.find("VOD")).isPresent();
            assertThat(table.find("null")).isEmpty();
            assertThat(table.failures()).isZero();
            assertThat(alerts).isEmpty();
        } finally {
            table.stop();
        }
    }

    @Test
    @DisplayName("a key that appears twice keeps the last row, as a SOW would")
    void duplicateKeyKeepsTheLastRow() throws Exception {
        String url = seeded(INSTRUMENTS, AAPL,
                "INSERT INTO instruments VALUES ('AAPL', '2046251', 'EUR', 100)");
        JdbcLookupTable table = started(resource(url, SELECT_ALL, "symbol"));
        try {
            assertThat(table.size()).isEqualTo(1);
            assertThat(table.find("AAPL").orElseThrow()).containsEntry("currency", "EUR");
        } finally {
            table.stop();
        }
    }

    // ---- the status line ---------------------------------------------------------------

    @Test
    @DisplayName("status() is one line: name, availability, rows, load time, reloads, failures")
    void statusLine() throws Exception {
        String url = seeded(INSTRUMENTS, AAPL, VOD);
        JdbcLookupTable table = started(resource(url, SELECT_ALL, "symbol"));
        try {
            String loaded = table.loadedAt().truncatedTo(ChronoUnit.SECONDS).toString();
            assertThat(table.status()).isEqualTo(
                    "instruments AVAILABLE rows=2 loaded=" + loaded + " reloads=0 failures=0");
            assertThat(table.status()).matches(
                    "instruments AVAILABLE rows=2 loaded=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z "
                            + "reloads=0 failures=0");

            execute(url, MSFT);
            table.reload();
            execute(url, "DROP TABLE instruments");
            assertThatThrownBy(table::reload).isInstanceOf(SQLException.class);

            assertThat(table.status()).startsWith("instruments AVAILABLE rows=3 loaded=")
                    .endsWith(" reloads=1 failures=1");
            assertThat(table.toString()).contains(table.status());
        } finally {
            table.stop();
        }
    }

    // ---- the timer ---------------------------------------------------------------------

    @Test
    @DisplayName("the timer reloads every reload-interval on a daemon thread, and stop() ends it")
    void timerReloadsOnTheInterval() throws Exception {
        String url = seeded(INSTRUMENTS, AAPL, VOD);
        JdbcLookupTable table = started(timed(resource(url, SELECT_ALL, "symbol")));
        try {
            Thread timer = reloadThread().orElseThrow();
            assertThat(timer.isDaemon()).as("must not keep the JVM alive").isTrue();

            execute(url, MSFT);
            Awaitility.await("the insert reaches the table").atMost(PATIENCE)
                    .until(() -> table.find("MSFT").isPresent());
            assertThat(table.size()).isEqualTo(3);
            assertThat(table.reloads()).isGreaterThanOrEqualTo(1);
            assertThat(table.failures()).isZero();
        } finally {
            table.stop();
        }
        Awaitility.await("the timer thread ends").atMost(PATIENCE)
                .until(() -> reloadThread().isEmpty());
        long reloads = table.reloads();
        Awaitility.await("no reload after stop").during(TICK.multipliedBy(4)).atMost(PATIENCE)
                .until(() -> table.reloads() == reloads);
    }

    @Test
    @DisplayName("a timer reload that fails keeps the table, backs off, and recovers when the database does")
    void timerBacksOffAfterAFailureAndRecovers() throws Exception {
        String url = seeded(INSTRUMENTS, AAPL, VOD);
        JdbcLookupTable table = started(timed(resource(url, SELECT_ALL, "symbol")));
        try {
            execute(url, "DROP TABLE instruments");
            Awaitility.await("the timer notices").atMost(PATIENCE)
                    .until(() -> table.failures() >= 1);
            // Still the last good copy, the whole time.
            assertThat(table.isAvailable()).isTrue();
            assertThat(table.find("AAPL")).isPresent();
            assertThat(alerts(JdbcLookupTable.RESOURCE_RELOAD_FAILED)).isNotEmpty();
            assertThat(alerts(JdbcLookupTable.RESOURCE_LOAD_FAILED)).isEmpty();

            execute(url, INSTRUMENTS, AAPL, VOD, MSFT);
            Awaitility.await("the timer recovers").atMost(PATIENCE)
                    .until(() -> table.find("MSFT").isPresent());
            assertThat(table.size()).isEqualTo(3);
        } finally {
            table.stop();
        }
    }

    // ---- construction ------------------------------------------------------------------

    @Test
    @DisplayName("an entry without a jdbc block is refused at construction")
    void refusesAnEntryWithoutJdbc() {
        ResourceProperties resource = new ResourceProperties();
        resource.setName("rics");
        assertThatThrownBy(() -> new JdbcLookupTable(resource, Alerts.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rics");
    }
}
