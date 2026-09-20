package com.demo.amps.connectors.enricher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The example's own {@code sql/instruments.sql}, loaded into an in-memory H2 database for
 * the tests -- the same file a developer runs against PostgreSQL, so what the tests enrich
 * from is what the README describes.
 *
 * <p>Two questions every test would otherwise answer on its own. <em>Where is the module?</em>
 * Gradle runs a {@code Test} task from the module directory and the {@code integrationTest}
 * task from the repository root, and an IDE picks whichever it likes, so the file is looked
 * for from both. <em>Which database?</em> {@code DB_CLOSE_DELAY=-1} keeps an in-memory H2
 * alive between the test's connections and the lookup table's, which open and close
 * independently; a fresh name per call keeps one test's inserts out of another's table.
 */
final class InstrumentsDatabase {

    /** The seed file, relative to the module directory. */
    static final Path SEED = Path.of("sql", "instruments.sql");

    /** The symbols the seed file holds, with their SEDOL and currency. */
    static final List<Instrument> SEEDED = List.of(
            new Instrument("K-0", "B0YQ5W0", "GBP"),
            new Instrument("K-1", "B1YQ5W1", "USD"),
            new Instrument("K-2", "B2YQ5W2", "EUR"),
            new Instrument("K-3", "B3YQ5W3", "JPY"),
            new Instrument("K-4", "B4YQ5W4", "GBP"));

    /** A seeded row, as the tests expect to find it in a record. */
    record Instrument(String symbol, String sedol, String currency) {
    }

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private InstrumentsDatabase() {
    }

    /**
     * The module directory, found from wherever the test happens to run.
     *
     * @return {@code amps-connectors/apps/instrument-enricher}, absolute
     * @throws IllegalStateException if neither the module nor the repository root is the
     *     working directory
     */
    static Path moduleDir() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : List.of(
                cwd,                                                          // the module
                cwd.resolve(Path.of("amps-connectors", "apps", "instrument-enricher")))) {
            if (Files.isRegularFile(candidate.resolve(SEED))) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("cannot find " + SEED + " from " + cwd
                + ": run from the module directory or the repository root");
    }

    /** The seed file, absolute. */
    static Path seedFile() {
        return moduleDir().resolve(SEED);
    }

    /**
     * A fresh, seeded database.
     *
     * @return its JDBC URL, for a {@code resources[].jdbc.url}
     * @throws SQLException if H2 refused the script
     */
    static String seeded() throws SQLException {
        return seeded("jdbc:h2:mem:instruments-" + DATABASES.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1");
    }

    /**
     * Run the seed file against a database, once, as H2's default user. Re-runnable: the
     * file inserts each row only when its symbol is absent, so a URL seeded twice holds one
     * copy of each.
     *
     * @param url the database
     * @return {@code url}, for chaining into a property
     * @throws SQLException if H2 refused the script
     */
    static String seeded(String url) throws SQLException {
        return seeded(url, null, null);
    }

    /**
     * Run the seed file against a database, once, as a named user.
     *
     * <p>The user matters for an in-memory H2: the first connection creates the database
     * and its user, and a later connection as somebody else is refused. So whatever the
     * lookup table will connect as (the instance file says {@code refdata_ro}) is what the
     * seed connects as.
     *
     * @param url the database
     * @param username the user, or {@code null} for H2's default
     * @param password the password, or {@code null} for none
     * @return {@code url}, for chaining into a property
     * @throws SQLException if H2 refused the script
     */
    static String seeded(String url, String username, String password) throws SQLException {
        executeAs(url, username, password,
                "RUNSCRIPT FROM '" + seedFile().toString().replace("'", "''") + "'");
        return url;
    }

    /** Execute statements against a database, on a connection of their own. */
    static void execute(String url, String... statements) throws SQLException {
        executeAs(url, null, null, statements);
    }

    private static void executeAs(
            String url, String username, String password, String... statements)
            throws SQLException {
        try (Connection connection = username == null
                        ? DriverManager.getConnection(url)
                        : DriverManager.getConnection(url, username, password == null ? "" : password);
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /**
     * The insert for one more instrument, in the seed file's own dialect-neutral shape.
     *
     * @param symbol the new symbol
     * @param sedol its SEDOL
     * @param currency its currency
     * @return the statement
     */
    static String insert(String symbol, String sedol, String currency) {
        return "INSERT INTO instruments (symbol, sedol, isin, currency, ric, updated_at) "
                + "SELECT '" + symbol + "', '" + sedol + "', 'XX00" + sedol + "0', '" + currency
                + "', '" + symbol + ".X', TIMESTAMP '2026-09-19 09:00:00' "
                + "WHERE NOT EXISTS (SELECT 1 FROM instruments WHERE symbol = '" + symbol + "')";
    }
}
