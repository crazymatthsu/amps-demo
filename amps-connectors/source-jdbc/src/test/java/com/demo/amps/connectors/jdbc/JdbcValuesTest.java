package com.demo.amps.connectors.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conversion table, spelled out once -- because two readers (the JDBC source's JSON and
 * the JDBC resource's lookup table) depend on it being exactly this, and a change to it is a
 * change to what every filter, key and lookup sees.
 */
class JdbcValuesTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    // ---- convert() ---------------------------------------------------------------------

    @Test
    @DisplayName("null stays null, and the JSON-native types are kept as they are")
    void keepsNullBooleansAndTheJsonNativeNumbers() {
        assertThat(JdbcValues.convert(null)).isNull();
        assertThat(JdbcValues.convert(Boolean.TRUE)).isEqualTo(Boolean.TRUE);
        assertThat(JdbcValues.convert(new BigDecimal("101.2500")))
                .isEqualTo(new BigDecimal("101.2500"));
        assertThat(JdbcValues.convert(new BigInteger("123456789012345678901234567890")))
                .isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(JdbcValues.convert(250)).isEqualTo(250);
        assertThat(JdbcValues.convert(250L)).isEqualTo(250L);
        assertThat(JdbcValues.convert(1.5f)).isEqualTo(1.5f);
        assertThat(JdbcValues.convert(1.5d)).isEqualTo(1.5d);
        assertThat(JdbcValues.convert("text")).isEqualTo("text");
    }

    @Test
    @DisplayName("a Byte or a Short widens to Integer, and any other Number becomes a Double")
    void widensTheSmallAndTheVendorNumbers() {
        assertThat(JdbcValues.convert((byte) 7)).isEqualTo(7).isInstanceOf(Integer.class);
        assertThat(JdbcValues.convert((short) 7)).isEqualTo(7).isInstanceOf(Integer.class);
        // Neither a boxed primitive nor a BigDecimal: the branch a driver's own numeric
        // type takes.
        assertThat(JdbcValues.convert(new AtomicLong(42))).isEqualTo(42.0d);
        assertThat(JdbcValues.convert(new AtomicInteger(3))).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("SQL timestamps, dates and times become ISO-8601 text")
    void convertsTheSqlTemporalsToIsoText() {
        Timestamp timestamp = Timestamp.valueOf("2026-09-18 12:34:56.789");
        // The JVM's zone, which is what Timestamp.toInstant() reads a zoneless value in.
        assertThat(JdbcValues.convert(timestamp)).isEqualTo(timestamp.toInstant().toString());
        assertThat(JdbcValues.convert(java.sql.Date.valueOf("2026-09-18")))
                .isEqualTo("2026-09-18");
        assertThat(JdbcValues.convert(Time.valueOf("12:34:56"))).isEqualTo("12:34:56");
    }

    @Test
    @DisplayName("java.time values become ISO-8601 text, zoned ones as the instant in UTC")
    void convertsTheJavaTimeValuesToIsoText() {
        Instant instant = Instant.parse("2026-09-18T12:34:56.123Z");
        assertThat(JdbcValues.convert(instant)).isEqualTo("2026-09-18T12:34:56.123Z");
        assertThat(JdbcValues.convert(OffsetDateTime.of(2026, 9, 18, 14, 34, 56, 0,
                ZoneOffset.ofHours(2)))).isEqualTo("2026-09-18T12:34:56Z");
        LocalDateTime local = LocalDateTime.of(2026, 9, 18, 12, 34, 56);
        assertThat(JdbcValues.convert(local))
                .isEqualTo(local.atZone(ZoneId.systemDefault()).toInstant().toString());
        assertThat(JdbcValues.convert(LocalDate.of(2026, 9, 18))).isEqualTo("2026-09-18");
        assertThat(JdbcValues.convert(LocalTime.of(12, 34, 56))).isEqualTo("12:34:56");
    }

    @Test
    @DisplayName("anything else gets its string form")
    void fallsBackToTheStringForm() {
        UUID id = UUID.fromString("8a6f4d4e-2b1c-4e0f-9f2b-1c2d3e4f5a6b");
        assertThat(JdbcValues.convert(id)).isEqualTo("8a6f4d4e-2b1c-4e0f-9f2b-1c2d3e4f5a6b");
        assertThat(JdbcValues.convert(new StringBuilder("built"))).isEqualTo("built");
    }

    // ---- row() ---------------------------------------------------------------------------

    @Test
    @DisplayName("a row is keyed by column label, in column order, with every value converted")
    void readsARowByLabelInColumnOrder() throws SQLException {
        String url = "jdbc:h2:mem:jdbc-values-" + DATABASES.incrementAndGet();
        try (Connection connection = JdbcValues.connect(url, null, null);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE positions (\"account\" VARCHAR(16), "
                    + "\"quantity\" INTEGER, \"avg_cost\" DECIMAL(12,4), \"active\" BOOLEAN, "
                    + "\"updated_at\" TIMESTAMP, \"note\" VARCHAR(32))");
            statement.execute("INSERT INTO positions VALUES ('ACC-1', 250, 101.2500, TRUE, "
                    + "TIMESTAMP '2026-09-18 12:34:56', NULL)");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT \"account\" AS \"acct\", \"quantity\", \"avg_cost\", \"active\", "
                            + "\"updated_at\", \"note\" FROM positions")) {
                assertThat(rows.next()).isTrue();
                LinkedHashMap<String, Object> row = JdbcValues.row(rows, rows.getMetaData());

                // The alias is the label, and the label is the key.
                assertThat(row.keySet()).containsExactly(
                        "acct", "quantity", "avg_cost", "active", "updated_at", "note");
                assertThat(row.get("acct")).isEqualTo("ACC-1");
                assertThat(row.get("quantity")).isEqualTo(250);
                assertThat(row.get("avg_cost")).isInstanceOf(BigDecimal.class);
                assertThat((BigDecimal) row.get("avg_cost")).isEqualByComparingTo("101.2500");
                assertThat(row.get("active")).isEqualTo(Boolean.TRUE);
                assertThat(row.get("updated_at"))
                        .isEqualTo(Timestamp.valueOf("2026-09-18 12:34:56").toInstant().toString());
                // Present and null: a cleared field, not an absent one.
                assertThat(row).containsKey("note");
                assertThat(row.get("note")).isNull();
            }
        }
    }

    // ---- connect() -----------------------------------------------------------------------

    @Test
    @DisplayName("a blank username dials the URL alone; a user dials with credentials")
    void connectsWithOrWithoutCredentials() throws SQLException {
        String url = "jdbc:h2:mem:jdbc-values-" + DATABASES.incrementAndGet();
        try (Connection connection = JdbcValues.connect(url, "", "ignored")) {
            assertThat(connection.isValid(1)).isTrue();
        }
        try (Connection connection = JdbcValues.connect(url, null, null)) {
            assertThat(connection.isValid(1)).isTrue();
        }
        // H2 accepts any user for a fresh in-memory database, which is enough to show the
        // credentials went down the other path.
        try (Connection connection = JdbcValues.connect(url, "sa", "")) {
            assertThat(connection.getMetaData().getUserName()).isEqualToIgnoringCase("sa");
        }
    }

    @Test
    @DisplayName("a URL nothing on the classpath can drive fails with the driver's own message")
    void refusesAUrlNoDriverClaims() {
        assertThatThrownBy(() -> JdbcValues.connect("jdbc:nowhere://host/db", null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("jdbc:nowhere://host/db");
    }
}
