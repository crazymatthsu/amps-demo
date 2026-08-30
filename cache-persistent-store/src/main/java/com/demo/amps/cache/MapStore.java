package com.demo.amps.cache;

import java.util.Map;

/**
 * The write side of the persistence SPI: where cache mutations go.
 *
 * <p>{@link PersistentCacheMap} calls the store BEFORE touching its local map,
 * so a store failure leaves local and remote state agreeing (on the old
 * value) rather than diverging. Implementations are therefore expected to
 * throw on failure, not swallow.
 *
 * <p>Contract points implementations rely on:
 *
 * <ul>
 *   <li>{@code store} is an upsert: last write wins, which is exactly the
 *       semantics an AMPS SOW topic provides per key;</li>
 *   <li>{@code delete} of an absent key is a no-op, not an error;</li>
 *   <li>values are never {@code null} -- the cache rejects them first, same
 *       as {@link java.util.concurrent.ConcurrentHashMap}.</li>
 * </ul>
 *
 * @param <V> the value type; must survive a JSON round trip
 */
public interface MapStore<V> extends MapLoader<V> {

    /** Persists one entry, replacing any previous value for {@code key}. */
    void store(String key, V value);

    /** Persists every entry in {@code entries}; more efficient than one-by-one. */
    void storeAll(Map<String, V> entries);

    /** Removes the entry for {@code key}; absent keys are a no-op. */
    void delete(String key);

    /** Removes every entry in the store (backs {@link java.util.Map#clear()}). */
    void deleteAll();
}
