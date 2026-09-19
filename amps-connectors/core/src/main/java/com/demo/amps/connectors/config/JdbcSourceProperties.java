package com.demo.amps.connectors.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The JDBC side of one connector: a database, one query, and how that query's result set is
 * read as a feed.
 *
 * <p>Lives under {@code source.jdbc}; its presence is what selects the JDBC source
 * ({@link SourceProperties}).
 *
 * <p>A query is a snapshot, not a stream, so this transport <em>polls</em>, and {@link #getMode()}
 * says what a poll means. {@link Mode#SNAPSHOT} re-runs the whole query every time and upserts
 * every row -- the result set <em>is</em> the state -- and with {@link #getKeyColumns()} set it
 * also reports the keys that stopped appearing, which is this transport's spelling of a delete.
 * {@link Mode#INCREMENTAL} reads forward from a watermark, which is a journal, and persists
 * that watermark to {@link #getStateFile()} when a batch is acknowledged.
 *
 * <p>A result-set row has no wire format of its own, so the source synthesises one: each row
 * becomes a flat JSON object keyed by result-set column <em>label</em>, with numbers left as
 * numbers, booleans as booleans, dates as ISO-8601 and SQL NULL as {@code null}. That is why a
 * connector on this transport must declare {@code format: JSON} (the validator enforces it).
 */
public class JdbcSourceProperties {

    /** Which of the query's rows a poll emits. */
    public enum Mode {
        /** Every poll runs the full query and upserts every row. */
        SNAPSHOT,
        /** Every poll emits only the rows past the watermark left by the polls before it. */
        INCREMENTAL
    }

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

    /**
     * The query every poll runs, verbatim: the source never rewrites it, not even to apply the
     * {@link Mode#INCREMENTAL} watermark. What the database is asked is exactly what is written
     * here, so an expensive query is expensive once per {@link #getPollInterval()}.
     */
    @NotBlank
    private String query;

    /** Whether a poll re-reads everything or only what is new. */
    @NotNull
    private Mode mode = Mode.SNAPSHOT;

    /**
     * {@link Mode#SNAPSHOT} only: the columns whose values, joined by {@link #getKeySeparator()},
     * identify a row.
     *
     * <p>Setting them is what lets a snapshot express a <em>removal</em> -- the source remembers
     * the key set between polls and emits a DELETE, carrying just those columns as JSON, for
     * every key that vanished. Left empty there are no deletes at all and a row deleted in the
     * database lingers in the SOW: the configured trade-off for a query with no stable key.
     */
    @NotNull
    private List<String> keyColumns = new ArrayList<>();

    /** What joins {@link #getKeyColumns()} into one key string. */
    @NotBlank
    private String keySeparator = "|";

    /**
     * {@link Mode#INCREMENTAL} only: a monotonically increasing column (a timestamp, a
     * sequence) that says what is new.
     */
    private String incrementalColumn;

    /**
     * {@link Mode#INCREMENTAL} only: where the watermark is persisted, written when a batch is
     * acknowledged. Unset keeps it in memory, so a restart re-reads the query from the
     * beginning -- harmless for an idempotent keyed topic, duplicate history for a journal one.
     */
    private String stateFile;

    /** How long a poll waits before running the query again. */
    @NotNull
    private Duration pollInterval = Duration.ofSeconds(5);

    /** How long to wait before redialling after a failed connection or a failed poll. */
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

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
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

    public String getIncrementalColumn() {
        return incrementalColumn;
    }

    public void setIncrementalColumn(String incrementalColumn) {
        this.incrementalColumn = incrementalColumn;
    }

    public String getStateFile() {
        return stateFile;
    }

    public void setStateFile(String stateFile) {
        this.stateFile = stateFile;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
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
