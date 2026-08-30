package com.demo.amps.hazelcast;

import com.crankuptheamps.client.Client;
import com.demo.amps.cache.AmpsFilters;
import com.demo.amps.cache.SowOps;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link TierStore} on one AMPS tier topic: records shaped
 *
 * <pre>{"map": "orders", "key": "user-42", "value": {...}}</pre>
 *
 * <p>on a topic declared with {@code <Key>/map</Key><Key>/key</Key>} (see
 * {@code server/config/flows/hazelcast/amps-config.xml}). The composite key
 * makes {@code (map, key)} the record identity, which is the whole grouping
 * design: any number of Hazelcast maps share the topic without colliding,
 * and everything one map does is scoped by a {@code /map} filter or by the
 * key fields of a delete-by-data.
 *
 * <p>Load strategy follows the TODO's analysis. Small key sets use chunked
 * {@code OR} filters ({@value #FILTER_CHUNK} keys per query); a set larger
 * than {@value #FULL_SCAN_THRESHOLD} switches to one {@code /map} query with
 * client-side intersection, because an EAGER cluster hydrate asks for every
 * key anyway and thousands of per-key filters would be slower than one scan.
 * Keys a filter cannot express (both quote characters -- rejected at store
 * time) are treated as absent on load, never an error.
 *
 * @param <V> the value type
 */
public final class AmpsTierStore<V> implements TierStore<V> {

    /** Wire field naming the Hazelcast map; first SOW {@code <Key>} in the flow config. */
    public static final String MAP_FIELD = "map";
    /** Wire field holding the cache key; second SOW {@code <Key>}. */
    public static final String KEY_FIELD = "key";
    /** Wire field holding the encoded value. */
    public static final String VALUE_FIELD = "value";

    /** Keys per OR-filter chunk, for bulk loads and deletes. */
    static final int FILTER_CHUNK = 32;
    /** Above this many requested keys, one /map scan beats chunked filters. */
    static final int FULL_SCAN_THRESHOLD = 256;

    private final SowOps sow;
    private final ValueCodec<V> codec;
    private final boolean flushPerWrite;

    /**
     * @param flushPerWrite when true (write-through mode), every single-entry
     *        {@code store} ends with a {@code publishFlush} round trip so a
     *        completed put is visible to any other process; when false
     *        (write-behind mode), single stores skip the barrier -- Hazelcast
     *        has already decoupled the caller -- and only batches flush.
     */
    public AmpsTierStore(Client client, String topic, ValueCodec<V> codec,
                         long timeoutMillis, boolean flushPerWrite) {
        this.sow = new SowOps(Objects.requireNonNull(client, "client"),
                Objects.requireNonNull(topic, "topic"), timeoutMillis);
        this.codec = Objects.requireNonNull(codec, "codec");
        this.flushPerWrite = flushPerWrite;
    }

    @Override
    public V load(String map, String key) {
        if (!AmpsFilters.expressible(key)) {
            return null; // could never have been stored
        }
        List<V> found = new ArrayList<>(1);
        sow.query(mapFilter(map) + " AND " + AmpsFilters.equalTo(KEY_FIELD, key),
                record -> found.add(codec.decode(record.get(VALUE_FIELD))));
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public Set<String> loadKeys(String map) {
        Set<String> keys = new LinkedHashSet<>();
        sow.query(mapFilter(map), record -> keys.add(record.get(KEY_FIELD).getAsString()));
        return keys;
    }

    @Override
    public Map<String, V> loadAll(String map, Collection<String> keys) {
        List<String> wanted = keys.stream().filter(AmpsFilters::expressible).toList();
        Map<String, V> loaded = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            return loaded;
        }
        if (wanted.size() > FULL_SCAN_THRESHOLD) {
            Set<String> requested = new LinkedHashSet<>(wanted);
            sow.query(mapFilter(map), record -> {
                String key = record.get(KEY_FIELD).getAsString();
                if (requested.contains(key)) {
                    loaded.put(key, codec.decode(record.get(VALUE_FIELD)));
                }
            });
            return loaded;
        }
        for (List<String> chunk : chunks(wanted, FILTER_CHUNK)) {
            sow.query(keysFilter(map, chunk), record ->
                    loaded.put(record.get(KEY_FIELD).getAsString(),
                            codec.decode(record.get(VALUE_FIELD))));
        }
        return loaded;
    }

    @Override
    public void store(String map, String key, V value) {
        JsonObject record = envelope(map, key, value);
        if (flushPerWrite) {
            sow.publish(record);
        } else {
            sow.publishWithoutBarrier(record);
        }
    }

    @Override
    public void storeAll(String map, Map<String, V> entries) {
        List<JsonObject> records = new ArrayList<>(entries.size());
        for (Map.Entry<String, V> entry : entries.entrySet()) {
            records.add(envelope(map, entry.getKey(), entry.getValue()));
        }
        sow.publishAll(records);
    }

    @Override
    public void delete(String map, String key) {
        JsonObject keyFields = new JsonObject();
        keyFields.addProperty(MAP_FIELD, AmpsFilters.checkKey(map));
        keyFields.addProperty(KEY_FIELD, Objects.requireNonNull(key, "key"));
        sow.deleteByData(keyFields);
    }

    @Override
    public void deleteAll(String map, Collection<String> keys) {
        // Every stored key is expressible (store() enforced it), so chunked
        // filter deletes are both valid and far fewer round trips than
        // per-key deletes when Hazelcast clears a large map.
        List<String> deletable = keys.stream().filter(AmpsFilters::expressible).toList();
        for (List<String> chunk : chunks(deletable, FILTER_CHUNK)) {
            sow.deleteByFilter(keysFilter(map, chunk));
        }
    }

    private JsonObject envelope(String map, String key, V value) {
        AmpsFilters.checkKey(map);
        AmpsFilters.checkKey(key);
        Objects.requireNonNull(value, "IMap values must not be null");
        JsonObject record = new JsonObject();
        record.addProperty(MAP_FIELD, map);
        record.addProperty(KEY_FIELD, key);
        record.add(VALUE_FIELD, codec.encode(value));
        return record;
    }

    private static String mapFilter(String map) {
        AmpsFilters.checkKey(map);
        return AmpsFilters.equalTo(MAP_FIELD, map);
    }

    /** {@code /map = 'm' AND (/key = 'a' OR /key = 'b' ...)} for one chunk. */
    static String keysFilter(String map, List<String> chunk) {
        StringBuilder filter = new StringBuilder(AmpsFilters.equalTo(MAP_FIELD, map))
                .append(" AND (");
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
                filter.append(" OR ");
            }
            filter.append(AmpsFilters.equalTo(KEY_FIELD, chunk.get(i)));
        }
        return filter.append(')').toString();
    }

    static <T> List<List<T>> chunks(List<T> all, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += size) {
            chunks.add(all.subList(from, Math.min(from + size, all.size())));
        }
        return chunks;
    }
}
