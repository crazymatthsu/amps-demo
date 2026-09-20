package com.demo.amps.connectors.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;

/**
 * How a JDBC result set becomes the framework's values: one conversion, shared by everything
 * that reads a database.
 *
 * <p>Two things in {@code amps-connectors} read rows. {@link JdbcRecordSource} turns each one
 * into a JSON record that flows to a topic; a JDBC <em>resource</em> turns the whole result
 * set into a table that a transform looks up by key while a record from some other feed goes
 * past. If the two converted a {@code DECIMAL} or a {@code TIMESTAMP} differently, a filter
 * written against a published row and a lookup written against the table would disagree about
 * the same column -- so the conversion lives here, once, and both call it. It lives in this
 * module rather than in core because core must stay transport-free, and {@code java.sql} is a
 * transport like the others.
 *
 * <p>The rule of the mapping: <strong>keep the meaning, not the class.</strong> Numbers and
 * booleans stay themselves, so a filter has a number to compare (a {@code BigDecimal} stays
 * exact; a {@code Byte} or {@code Short} widens to {@code Integer}, a vendor number to
 * {@code Double}). Dates and times become ISO-8601 text, because JSON has no date type and
 * AMPS reads instants from text; a timestamp without a zone is read in the JVM's zone, which
 * is what {@link Timestamp#toInstant()} does and what the database round-tripped it through.
 * SQL {@code NULL} stays {@code null} -- an explicit clear, not an absent field. Everything
 * else (a BLOB, a driver's own vendor type) gets its string form, which is as much as this
 * module can honestly claim to know about it.
 *
 * <table border="1">
 *   <caption>The mapping</caption>
 *   <tr><th>from the driver</th><th>to the framework</th></tr>
 *   <tr><td>{@code null}</td><td>{@code null}</td></tr>
 *   <tr><td>{@code Boolean}, {@code BigDecimal}, {@code BigInteger}, {@code Integer},
 *       {@code Long}, {@code Float}, {@code Double}</td><td>unchanged</td></tr>
 *   <tr><td>{@code Byte}, {@code Short}</td><td>{@code Integer}</td></tr>
 *   <tr><td>any other {@code Number}</td><td>{@code Double}</td></tr>
 *   <tr><td>{@code java.sql.Timestamp}</td><td>{@code toInstant().toString()}</td></tr>
 *   <tr><td>{@code java.sql.Date}</td><td>{@code toLocalDate().toString()}</td></tr>
 *   <tr><td>{@code java.sql.Time}</td><td>{@code toLocalTime().toString()}</td></tr>
 *   <tr><td>{@code Instant}, {@code OffsetDateTime}, {@code LocalDateTime}</td>
 *       <td>the instant, ISO-8601 in UTC ({@code LocalDateTime} via the JVM zone)</td></tr>
 *   <tr><td>{@code LocalDate}, {@code LocalTime}</td><td>ISO-8601 text</td></tr>
 *   <tr><td>anything else</td><td>{@code String.valueOf}</td></tr>
 * </table>
 */
public final class JdbcValues {

    private JdbcValues() {
    }

    /**
     * One column's value, in the type that keeps its meaning.
     *
     * @param value what {@link ResultSet#getObject} returned, {@code null} included
     * @return {@code null}, a {@code Boolean}, a {@code Number} of one of the kept classes,
     *     or a {@code String}
     */
    public static Object convert(Object value) {
        return switch (value) {
            case null -> null;
            case Boolean v -> v;
            case BigDecimal v -> v;
            case BigInteger v -> v;
            case Byte v -> v.intValue();
            case Short v -> v.intValue();
            case Integer v -> v;
            case Long v -> v;
            case Float v -> v;
            case Double v -> v;
            case Number v -> v.doubleValue();
            case Timestamp v -> v.toInstant().toString();
            case java.sql.Date v -> v.toLocalDate().toString();
            case Time v -> v.toLocalTime().toString();
            case Instant v -> v.toString();
            case OffsetDateTime v -> v.toInstant().toString();
            case LocalDateTime v -> v.atZone(ZoneId.systemDefault()).toInstant().toString();
            case LocalDate v -> v.toString();
            case LocalTime v -> v.toString();
            default -> String.valueOf(value);
        };
    }

    /**
     * The current row as an ordered map, keyed by result-set column <em>label</em>.
     *
     * <p>Label rather than name, so an {@code AS} alias is what the row carries and what a
     * filter, a key or a lookup addresses; insertion order is column order, so the JSON a
     * source writes from this map reads the way the query was written.
     *
     * @param rows positioned on the row to read
     * @param meta that result set's metadata
     * @return a new, mutable map of label to {@link #convert converted} value
     * @throws SQLException if the driver could not read a column
     */
    public static LinkedHashMap<String, Object> row(ResultSet rows, ResultSetMetaData meta)
            throws SQLException {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int column = 1; column <= meta.getColumnCount(); column++) {
            row.put(meta.getColumnLabel(column), convert(rows.getObject(column)));
        }
        return row;
    }

    /**
     * Open a connection.
     *
     * <p>A blank username hands the URL the whole job, which is what an embedded or
     * trust-authenticated database expects; passing {@code ("", "")} to those is not the same
     * thing and is rejected by some drivers.
     *
     * @param url the JDBC URL; the driver it names has to be on the classpath
     * @param username the user, or blank to leave authentication to the URL
     * @param password the password; ignored when the username is blank
     * @return a new connection, the caller's to close
     * @throws SQLException when the database cannot be reached or refuses the credentials
     */
    public static Connection connect(String url, String username, String password)
            throws SQLException {
        if (username == null || username.isBlank()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }
}
