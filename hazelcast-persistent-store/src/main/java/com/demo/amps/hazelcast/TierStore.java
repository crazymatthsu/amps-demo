package com.demo.amps.hazelcast;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * The persistence operations one Hazelcast map needs from its tier: every
 * method takes the MAP NAME, because a tier topic is shared by any number of
 * maps and the {@code (map, key)} pair -- not the key alone -- is a record's
 * identity there.
 *
 * <p>This is deliberately shaped after Hazelcast's partitioned load protocol
 * rather than after {@code java.util.Map}: {@code loadKeys} exists because
 * Hazelcast asks for the key universe once and then bulk-loads per partition
 * owner via {@code loadAll(keys)} -- two calls a flat load-everything API
 * cannot serve efficiently.
 *
 * <p>An interface so the adapter is testable against an in-memory fake; the
 * real implementation is {@link AmpsTierStore}.
 *
 * @param <V> the value type; must survive the codec's round trip
 */
public interface TierStore<V> {

    /** The stored value for {@code (map, key)}, or {@code null} when absent. */
    V load(String map, String key);

    /** Every key stored for {@code map} -- values are not decoded. */
    Set<String> loadKeys(String map);

    /** The stored entries of {@code map} for exactly the given keys; absent keys are omitted. */
    Map<String, V> loadAll(String map, Collection<String> keys);

    /** Persists one entry, replacing any previous value for the pair. */
    void store(String map, String key, V value);

    /** Persists a batch under one map, paying any write barrier once. */
    void storeAll(String map, Map<String, V> entries);

    /** Removes one entry; an absent pair is a no-op. */
    void delete(String map, String key);

    /** Removes exactly the given keys of {@code map}; other maps in the tier are untouched. */
    void deleteAll(String map, Collection<String> keys);
}
