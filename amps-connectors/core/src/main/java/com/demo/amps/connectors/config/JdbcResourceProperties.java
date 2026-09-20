package com.demo.amps.connectors.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A reference table loaded from a database: one query, the columns that key its rows, and how
 * often it is re-read.
 *
 * <p>Lives under {@code resources[].jdbc}; its presence is what selects the JDBC resource
 * ({@link ResourceProperties}), which {@code :amps-connectors:resource-jdbc} builds as an
 * in-memory lookup table.
 *
 * <p>This is deliberately the same shape as {@link JdbcSourceProperties} minus the polling
 * modes, because it answers a different question. A source turns rows into <em>records</em>
 * that flow to a topic; a resource turns them into a <em>table</em> that a transform looks
 * up by key while a record from some other feed goes past. So there is no watermark and no
 * delete: the whole query is the table, it is swapped atomically on every reload, and a
 * reload that fails keeps the last good copy rather than emptying the table under the
 * transforms that read it.
 */
public class JdbcResourceProperties {

    /** JDBC URL of the database to dial. The driver it names has to be on the classpath. */
    @NotBlank
    private String url;

    /** Database user. Blank leaves authentication to whatever the URL itself carries. */
    private String username;

    /**
     * Database password.
     *
     * <p>Arrives from the environment, never as a literal: the config tree is plaintext in git,
     * so a credential key there carries a single {@code ${VAR:}} placeholder and nothing else.
     */
    private String password;

    /** The query every load runs, verbatim; its result set is the whole table. */
    @NotBlank
    private String query;

    /**
     * The columns whose values, joined by {@link #getKeySeparator()}, identify a row -- the
     * key a transform looks the row up by. Required: a table nobody can address is a table
     * nobody can use, and a row with a NULL key column is skipped (and counted) on every load.
     */
    @NotNull
    private List<String> keyColumns = new ArrayList<>();

    /** What joins {@link #getKeyColumns()} into one key string. */
    @NotBlank
    private String keySeparator = "|";

    /**
     * How often the table is re-read on its own. {@code 0} turns the timer off, leaving
     * reloads to the control channel -- the right setting for reference data that changes
     * when someone says so rather than on a schedule.
     */
    @NotNull
    private Duration reloadInterval = Duration.ofMinutes(5);

    /** How long to wait before dialling again after a failed load. */
    @NotNull
    private Duration reconnectDelay = Duration.ofSeconds(5);

    /**
     * Rows the driver fetches per round trip. Positive rather than JDBC's {@code 0}, which
     * means "the driver's default" -- and several drivers default to materialising the entire
     * result set, the one thing this knob exists to bound.
     */
    @Min(1)
    private int fetchSize = 1_000;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<String> getKeyColumns() {
        return keyColumns;
    }

    public void setKeyColumns(List<String> keyColumns) {
        this.keyColumns = keyColumns == null ? new ArrayList<>() : keyColumns;
    }

    public String getKeySeparator() {
        return keySeparator;
    }

    public void setKeySeparator(String keySeparator) {
        this.keySeparator = keySeparator;
    }

    public Duration getReloadInterval() {
        return reloadInterval;
    }

    public void setReloadInterval(Duration reloadInterval) {
        this.reloadInterval = reloadInterval;
    }

    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(Duration reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }
}
