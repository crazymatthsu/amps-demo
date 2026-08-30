package com.demo.amps.cache;

import java.util.Map;

/**
 * The read side of the persistence SPI: where cache entries come from when the
 * local map does not have them.
 *
 * <p>A {@link PersistentCacheMap} uses this in exactly two places:
 *
 * <ul>
 *   <li>{@link #loadAll()} once, at construction, to hydrate the local map --
 *       which is what makes a process restart or a failover to another machine
 *       recoverable: the new process starts empty and pulls the whole cache
 *       back from the store;</li>
 *   <li>{@link #load(String)} on a local miss, so a key another process wrote
 *       (or one this process evicted) is fetched on demand rather than being
 *       invisible until the next restart.</li>
 * </ul>
 *
 * <p>The shape deliberately mirrors the MapLoader SPI familiar from
 * distributed-cache libraries such as Hazelcast, so the AMPS-backed store
 * reads as "the same idea, with a message broker as the backing store".
 *
 * @param <V> the value type; must survive a JSON round trip
 */
public interface MapLoader<V> {

    /** The stored value for {@code key}, or {@code null} when the store has none. */
    V load(String key);

    /** Every stored entry, for hydrating a cache at startup. */
    Map<String, V> loadAll();
}
