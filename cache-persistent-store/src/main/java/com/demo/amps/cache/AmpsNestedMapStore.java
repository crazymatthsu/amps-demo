package com.demo.amps.cache;

import com.crankuptheamps.client.Client;
import com.demo.amps.common.DemoConfig;
import com.google.gson.JsonObject;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link NestedMapStore} backed by one AMPS SOW topic with a composite key:
 * the proposed representation for {@code Map<String, Map<String, ?>>}.
 *
 * <p>The nested map is FLATTENED -- one SOW record per
 * {@code (outerKey, innerKey)} pair:
 *
 * <pre>{"outerKey": "portfolio-1", "innerKey": "AAPL", "value": {"qty": 250, "px": 187.5}}</pre>
 *
 * <p>with the topic declared as
 * {@code <Key>/outerKey</Key><Key>/innerKey</Key>} (see
 * {@code server/config/flows/cache/amps-config.xml}). The pair IS the record's
 * identity, so:
 *
 * <ul>
 *   <li>updating one inner entry publishes one small record -- no
 *       read-modify-write of the outer map, and no lost update when two
 *       processes touch different inner entries of the same outer key;</li>
 *   <li>the journal records the entry that changed, not the whole outer map
 *       re-serialized around it;</li>
 *   <li>one inner map is an ordinary server-side query,
 *       {@code /outerKey = 'portfolio-1'}, and one entry of it is
 *       {@code load} on both fields -- either way the server does the
 *       finding;</li>
 *   <li>content filters see inner values at a fixed depth
 *       ({@code /value/qty > 1000}) instead of under a path that embeds the
 *       inner key.</li>
 * </ul>
 *
 * <p>The trade: an outer map is no longer one atomic record. Replacing a whole
 * outer map is several publishes and deletes, and a concurrent reader can see
 * it mid-replace. When whole-map atomicity matters more than fine-grained
 * updates, store the inner map as ONE value instead --
 * {@link AmpsMapStore#ofMaps} does exactly that on the plain topic. The README
 * lays out both, plus the alternatives considered.
 *
 * @param <V> the inner value type. Use {@link #untyped} for plain JSON values.
 */
public final class AmpsNestedMapStore<V> implements NestedMapStore<V> {

    /** Wire field for the outer map key; first SOW {@code <Key>} in the flow config. */
    public static final String OUTER_FIELD = "outerKey";
    /** Wire field for the inner map key; second SOW {@code <Key>}. */
    public static final String INNER_FIELD = "innerKey";
    /** Wire field holding the inner value. */
    public static final String VALUE_FIELD = "value";

    private static final Logger log = LoggerFactory.getLogger(AmpsNestedMapStore.class);

    private final SowOps sow;
    private final Type valueType;

    public AmpsNestedMapStore(Client client, String topic, Type valueType) {
        this(client, topic, valueType, DemoConfig.timeoutMillis());
    }

    public AmpsNestedMapStore(Client client, String topic, Type valueType, long timeoutMillis) {
        this.sow = new SowOps(Objects.requireNonNull(client, "client"),
                Objects.requireNonNull(topic, "topic"), timeoutMillis);
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    /** A store for untyped JSON inner values -- the {@code Map<String, Map<String, ?>>} shape. */
    public static AmpsNestedMapStore<Object> untyped(Client client, String topic) {
        return new AmpsNestedMapStore<>(client, topic, Object.class);
    }

    @Override
    public Map<String, V> loadOuter(String outerKey) {
        AmpsFilters.checkKey(outerKey);
        Map<String, V> inner = new LinkedHashMap<>();
        sow.query(AmpsFilters.equalTo(OUTER_FIELD, outerKey),
                record -> inner.put(record.get(INNER_FIELD).getAsString(), valueOf(record)));
        return inner;
    }

    @Override
    public Map<String, Map<String, V>> loadAll() {
        Map<String, Map<String, V>> all = new LinkedHashMap<>();
        sow.query(null, record -> {
            if (record.has(OUTER_FIELD) && record.has(INNER_FIELD) && record.has(VALUE_FIELD)) {
                all.computeIfAbsent(record.get(OUTER_FIELD).getAsString(),
                                outer -> new LinkedHashMap<>())
                        .put(record.get(INNER_FIELD).getAsString(), valueOf(record));
            } else {
                log.warn("skipping record without {}/{}/{} fields on topic '{}': {}",
                        OUTER_FIELD, INNER_FIELD, VALUE_FIELD, sow.topic(), record);
            }
        });
        return all;
    }

    @Override
    public void storeEntry(String outerKey, String innerKey, V value) {
        sow.publish(envelope(outerKey, innerKey, value));
    }

    @Override
    public void storeEntries(String outerKey, Map<String, V> entries) {
        List<JsonObject> records = new ArrayList<>(entries.size());
        for (Map.Entry<String, V> entry : entries.entrySet()) {
            records.add(envelope(outerKey, entry.getKey(), entry.getValue()));
        }
        sow.publishAll(records);
    }

    @Override
    public void deleteEntry(String outerKey, String innerKey) {
        JsonObject keyOnly = new JsonObject();
        keyOnly.addProperty(OUTER_FIELD, AmpsFilters.checkKey(outerKey));
        keyOnly.addProperty(INNER_FIELD, AmpsFilters.checkKey(innerKey));
        sow.deleteByData(keyOnly);
    }

    @Override
    public void deleteOuter(String outerKey) {
        AmpsFilters.checkKey(outerKey);
        sow.deleteByFilter(AmpsFilters.equalTo(OUTER_FIELD, outerKey));
    }

    @Override
    public void deleteAll() {
        sow.deleteByFilter(AmpsFilters.MATCH_EVERYTHING);
    }

    private JsonObject envelope(String outerKey, String innerKey, V value) {
        AmpsFilters.checkKey(outerKey);
        AmpsFilters.checkKey(innerKey);
        Objects.requireNonNull(value, "cache values must not be null");
        JsonObject record = new JsonObject();
        record.addProperty(OUTER_FIELD, outerKey);
        record.addProperty(INNER_FIELD, innerKey);
        record.add(VALUE_FIELD, JsonValues.GSON.toJsonTree(value));
        return record;
    }

    private V valueOf(JsonObject record) {
        return JsonValues.GSON.fromJson(record.get(VALUE_FIELD), valueType);
    }
}
