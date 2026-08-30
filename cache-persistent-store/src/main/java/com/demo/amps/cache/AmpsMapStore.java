package com.demo.amps.cache;

import com.crankuptheamps.client.Client;
import com.demo.amps.common.DemoConfig;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MapStore} backed by one AMPS SOW topic: the flat cache's remote store.
 *
 * <p>Wire format is one JSON record per cache entry:
 *
 * <pre>{"key": "user-42", "value": {"name": "Ada", "level": 7}}</pre>
 *
 * <p>The topic is declared with {@code <Key>/key</Key>} (see
 * {@code server/config/flows/cache/amps-config.xml}), so the SOW is exactly
 * this map: last value per key, kept by the server, queryable at any time.
 * The {@code value} member carries anything JSON can -- an object, a string,
 * a number -- which is why the entry is an envelope rather than the value's
 * own fields at top level: a bare string or number has no fields to hold a
 * key, and the envelope gives every value shape the same address,
 * {@code /value}, for content filters.
 *
 * <p>Operations map one-to-one onto AMPS commands: {@code store} is a
 * {@code publish} (a SOW upsert), {@code load} is a {@code sow} query on
 * {@code /key}, {@code delete} is a {@code sow_delete} by data (the server
 * recomputes the key from the message, so no filter quoting is involved), and
 * {@code deleteAll} is a {@code sow_delete} by match-everything filter.
 *
 * @param <V> the value type. Use {@link #untyped} for plain JSON values, or
 *            the constructor with a Gson {@link Type} for typed values.
 */
public final class AmpsMapStore<V> implements MapStore<V> {

    /** Wire field holding the cache key; also the SOW {@code <Key>} in the flow config. */
    public static final String KEY_FIELD = "key";
    /** Wire field holding the cache value. */
    public static final String VALUE_FIELD = "value";

    private static final Logger log = LoggerFactory.getLogger(AmpsMapStore.class);

    private final SowOps sow;
    private final Type valueType;

    /**
     * A store speaking to {@code topic} through {@code client}, deserializing
     * values as {@code valueType}, with the repo-standard command timeout.
     */
    public AmpsMapStore(Client client, String topic, Type valueType) {
        this(client, topic, valueType, DemoConfig.timeoutMillis());
    }

    public AmpsMapStore(Client client, String topic, Type valueType, long timeoutMillis) {
        this.sow = new SowOps(Objects.requireNonNull(client, "client"),
                Objects.requireNonNull(topic, "topic"), timeoutMillis);
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    /**
     * A store for untyped JSON values: {@code String}, {@code Long},
     * {@code Double}, {@code Boolean}, {@code List}, or {@code Map} -- which
     * is the {@code Map<String, ?>} shape from the requirements, including
     * map-valued entries.
     */
    public static AmpsMapStore<Object> untyped(Client client, String topic) {
        return new AmpsMapStore<>(client, topic, Object.class);
    }

    /**
     * A store whose every value is a {@code Map<String, Object>} -- one JSON
     * object per entry. This is the "nested single record" way to hold a
     * {@code Map<String, Map<String, ?>>}: the whole inner map is one value.
     * See {@link AmpsNestedMapStore} for the flattened alternative and the
     * README for when to choose which.
     */
    public static AmpsMapStore<Map<String, Object>> ofMaps(Client client, String topic) {
        return new AmpsMapStore<>(client, topic,
                new TypeToken<Map<String, Object>>() { }.getType());
    }

    @Override
    public V load(String key) {
        AmpsFilters.checkKey(key);
        List<V> found = new ArrayList<>(1);
        sow.query(AmpsFilters.equalTo(KEY_FIELD, key), record -> found.add(valueOf(record)));
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public Map<String, V> loadAll() {
        Map<String, V> all = new LinkedHashMap<>();
        sow.query(null, record -> {
            if (record.has(KEY_FIELD) && record.has(VALUE_FIELD)) {
                all.put(record.get(KEY_FIELD).getAsString(), valueOf(record));
            } else {
                // Not ours: someone published a differently-shaped record to
                // the cache topic. Skipping beats poisoning hydration.
                log.warn("skipping record without {}/{} fields on topic '{}': {}",
                        KEY_FIELD, VALUE_FIELD, sow.topic(), record);
            }
        });
        return all;
    }

    @Override
    public void store(String key, V value) {
        sow.publish(envelope(key, value));
    }

    @Override
    public void storeAll(Map<String, V> entries) {
        List<JsonObject> records = new ArrayList<>(entries.size());
        for (Map.Entry<String, V> entry : entries.entrySet()) {
            records.add(envelope(entry.getKey(), entry.getValue()));
        }
        sow.publishAll(records);
    }

    @Override
    public void delete(String key) {
        JsonObject keyOnly = new JsonObject();
        keyOnly.addProperty(KEY_FIELD, AmpsFilters.checkKey(key));
        sow.deleteByData(keyOnly);
    }

    @Override
    public void deleteAll() {
        sow.deleteByFilter(AmpsFilters.MATCH_EVERYTHING);
    }

    private JsonObject envelope(String key, V value) {
        AmpsFilters.checkKey(key);
        Objects.requireNonNull(value, "cache values must not be null");
        JsonObject record = new JsonObject();
        record.addProperty(KEY_FIELD, key);
        record.add(VALUE_FIELD, JsonValues.GSON.toJsonTree(value));
        return record;
    }

    private V valueOf(JsonObject record) {
        return JsonValues.GSON.fromJson(record.get(VALUE_FIELD), valueType);
    }
}
