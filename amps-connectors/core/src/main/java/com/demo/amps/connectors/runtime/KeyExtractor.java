package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.config.KeyProperties;
import com.demo.amps.connectors.decode.Fields;
import com.demo.amps.connectors.source.SourceRecord;
import java.util.List;
import java.util.Map;

/**
 * Works out a record's SOW key, and refuses to guess.
 *
 * <p>Which end computes the key is {@link KeyProperties.Mode}'s business, and this class is
 * where that choice becomes two very different jobs:
 *
 * <ul>
 *   <li>{@link KeyProperties.Mode#SERVER} -- the topic's own {@code <Key>} derives the key from
 *       the payload, so there is no key to send. What there <em>is</em> to do is check: a
 *       payload missing one of the key fields is a record AMPS cannot key, and it is rejected
 *       here rather than published under whatever the server makes of it.</li>
 *   <li>{@link KeyProperties.Mode#PUBLISHER} -- the connector sends the key as the SowKey
 *       header. The configured fields joined by the separator are the key; with no fields
 *       configured the source's own key is used, which is what a Kafka message key or a JDBC
 *       key-column set is for.</li>
 * </ul>
 *
 * <p>Both paths throw rather than fall back, and that is the important behaviour. A SOW topic
 * declared without a {@code <Key>} does <em>not</em> reject a publish that carries no SowKey:
 * AMPS files it under a sentinel key ({@code 18446744073709551615}) and every subsequent
 * keyless publish overwrites that same record. A connector that quietly published unkeyed
 * records would therefore look healthy while collapsing the whole feed onto one row. So an
 * undeterminable key is an {@link IllegalArgumentException} naming the field, the pipeline
 * counts the record as rejected, and nothing is sent.
 */
public final class KeyExtractor {

    private final KeyProperties properties;

    /**
     * @param properties the connector's {@code amps.key} block
     */
    public KeyExtractor(KeyProperties properties) {
        this.properties = properties;
    }

    /** Which end computes the key. */
    public KeyProperties.Mode mode() {
        return properties.getMode();
    }

    /** Whether the key is built from payload fields rather than from the source's own key. */
    public boolean hasFields() {
        return !properties.getFields().isEmpty();
    }

    /**
     * The SowKey header to send with the record, checking on the way that the key can be
     * determined at all.
     *
     * @param record the source record, for its own key
     * @param fields the fields as the transforms left them
     * @return the key to send, or {@code null} in {@link KeyProperties.Mode#SERVER} -- where
     *     the return value is not the point, the check is
     * @throws IllegalArgumentException naming the field the record does not carry, or saying
     *     that the source supplied no key
     */
    public String key(SourceRecord record, Map<String, Object> fields) {
        List<String> names = properties.getFields();
        if (properties.getMode() == KeyProperties.Mode.SERVER) {
            for (String name : names) {
                if (!Fields.contains(fields, name)) {
                    throw new IllegalArgumentException("key field '" + name
                            + "' is missing, so the topic's <Key> cannot derive a SOW key");
                }
            }
            return null;
        }
        if (names.isEmpty()) {
            String sourceKey = record.key();
            if (sourceKey == null || sourceKey.isEmpty()) {
                throw new IllegalArgumentException("the source supplied no key and amps.key.fields "
                        + "is empty, so there is no SowKey to publish with");
            }
            return sourceKey;
        }
        return join(fields, names, "SowKey");
    }

    /**
     * The AMPS filter that identifies the record to delete on a server-keyed topic.
     *
     * <p>{@code sow_delete} by key is not available there -- the key is the server's, derived
     * from a payload the delete may not carry -- so the removal is expressed the only other way
     * AMPS offers: a filter over the key fields, {@code /11 = 'ORD-1' AND /55 = 'AAPL'}. Single
     * quotes in a value are doubled, the SQL-92 escaping AMPS' filter syntax uses.
     *
     * @param fields the fields the delete decoded to
     * @return the filter, or {@code null} when a key field is absent and the delete cannot be
     *     addressed -- the pipeline counts that and drops it
     */
    public String deleteFilter(Map<String, Object> fields) {
        List<String> names = properties.getFields();
        if (names.isEmpty()) {
            return null;
        }
        StringBuilder filter = new StringBuilder(32);
        for (String name : names) {
            String value = Fields.text(Fields.get(fields, name));
            if (value == null) {
                return null;
            }
            if (filter.length() > 0) {
                filter.append(" AND ");
            }
            filter.append('/').append(name).append(" = '")
                    .append(value.replace("'", "''")).append('\'');
        }
        return filter.toString();
    }

    /** The key fields joined by the configured separator, or a complaint about the first gap. */
    private String join(Map<String, Object> fields, List<String> names, String what) {
        StringBuilder key = new StringBuilder(24);
        for (String name : names) {
            String value = Fields.text(Fields.get(fields, name));
            if (value == null) {
                throw new IllegalArgumentException(
                        "key field '" + name + "' is missing, so there is no " + what
                                + " to publish with");
            }
            if (key.length() > 0) {
                key.append(properties.getSeparator());
            }
            key.append(value);
        }
        return key.toString();
    }
}
