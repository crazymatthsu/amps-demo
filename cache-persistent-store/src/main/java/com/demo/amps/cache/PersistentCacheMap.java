package com.demo.amps.cache;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@code java.util.Map<String, V>} whose contents survive the process: a
 * local {@link ConcurrentHashMap} in front of a {@link MapStore}.
 *
 * <p>The rules are small and worth stating exactly:
 *
 * <ul>
 *   <li><b>Hydration.</b> {@link #hydrate} loads every stored entry into the
 *       local map before returning -- so a restarted (or failed-over) process
 *       starts where the last one left off.</li>
 *   <li><b>Write-through.</b> Every mutation goes to the store FIRST, then to
 *       the local map. If the store throws, the local map is untouched and
 *       the exception propagates -- local and remote never disagree about a
 *       write that "worked".</li>
 *   <li><b>Read-through.</b> {@link #get} is the one operation that reaches
 *       past the local map: on a local miss it asks the store, caches a hit,
 *       and returns it. Everything else -- {@code size}, {@code containsKey},
 *       iteration -- describes the LOCAL replica only, so the Map view stays
 *       cheap and never blocks on the network.</li>
 *   <li><b>No nulls.</b> Like the {@code ConcurrentHashMap} underneath,
 *       {@code null} keys and values are rejected. {@code null} from
 *       {@code get} always means absent.</li>
 *   <li><b>Views are read-only.</b> {@code keySet}, {@code values} and
 *       {@code entrySet} cannot mutate the cache -- a mutation smuggled
 *       through an iterator would bypass write-through and silently fork
 *       local from remote.</li>
 * </ul>
 *
 * <p>What this deliberately is not: a coherence protocol. Another process's
 * later writes become visible here on {@link #get} of an absent key, on
 * {@link #refresh}, or on restart -- not spontaneously. AMPS can push them
 * ({@code sow_and_subscribe} delivers the snapshot plus every subsequent
 * change on one command); wiring that in is the natural next step and is left
 * out to keep this the smallest honest version of the idea.
 *
 * @param <V> the value type; must survive a JSON round trip
 */
public final class PersistentCacheMap<V> implements Map<String, V> {

    private final ConcurrentHashMap<String, V> local = new ConcurrentHashMap<>();
    /** Read-only lens over {@code local}; what every view method hands out. */
    private final Map<String, V> readOnly = Collections.unmodifiableMap(local);
    private final MapStore<V> store;

    private PersistentCacheMap(MapStore<V> store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Creates a cache over {@code store} and hydrates it: the MapLoader half
     * of the SPI, run once at startup.
     */
    public static <V> PersistentCacheMap<V> hydrate(MapStore<V> store) {
        PersistentCacheMap<V> cache = new PersistentCacheMap<>(store);
        cache.local.putAll(store.loadAll());
        return cache;
    }

    // ------------------------------------------------------------------
    // Reads. get() is read-through; everything else is the local replica.
    // ------------------------------------------------------------------

    @Override
    public V get(Object key) {
        if (!(key instanceof String k)) {
            return null;
        }
        V value = local.get(k);
        if (value != null) {
            return value;
        }
        V loaded = store.load(k);
        if (loaded == null) {
            return null;
        }
        // A concurrent put may have beaten us; the local value is newer than
        // what we just loaded, so it wins.
        V raced = local.putIfAbsent(k, loaded);
        return raced != null ? raced : loaded;
    }

    @Override
    public int size() {
        return local.size();
    }

    @Override
    public boolean isEmpty() {
        return local.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return local.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return local.containsValue(value);
    }

    // ------------------------------------------------------------------
    // Writes: store first, local second.
    // ------------------------------------------------------------------

    @Override
    public V put(String key, V value) {
        Objects.requireNonNull(key, "cache keys must not be null");
        Objects.requireNonNull(value, "cache values must not be null");
        store.store(key, value);
        return local.put(key, value);
    }

    @Override
    public V remove(Object key) {
        if (!(key instanceof String k)) {
            return null;
        }
        // Delete remotely even when absent locally: the entry may live only
        // in the store (evicted here, or written by another process).
        store.delete(k);
        return local.remove(k);
    }

    @Override
    public void putAll(Map<? extends String, ? extends V> entries) {
        Map<String, V> copy = Map.copyOf(entries);
        store.storeAll(copy);
        local.putAll(copy);
    }

    @Override
    public void clear() {
        store.deleteAll();
        local.clear();
    }

    // ------------------------------------------------------------------
    // Cache-specific operations.
    // ------------------------------------------------------------------

    /**
     * Drops {@code key} from the LOCAL map only; the store keeps it. The next
     * {@link #get} fetches it back -- this is eviction, and it is also how the
     * demo proves read-through is real.
     */
    public void evictLocal(String key) {
        local.remove(key);
    }

    /**
     * Re-reads the whole store, replacing the local contents -- picks up other
     * processes' writes and deletes without a restart. Not atomic: a
     * concurrent reader can observe the union mid-refresh.
     */
    public void refresh() {
        Map<String, V> fresh = store.loadAll();
        local.putAll(fresh);
        local.keySet().retainAll(fresh.keySet());
    }

    // ------------------------------------------------------------------
    // Views: read-only, local.
    // ------------------------------------------------------------------

    @Override
    public Set<String> keySet() {
        return readOnly.keySet();
    }

    @Override
    public Collection<V> values() {
        return readOnly.values();
    }

    @Override
    public Set<Entry<String, V>> entrySet() {
        return readOnly.entrySet();
    }

    @Override
    public boolean equals(Object other) {
        return local.equals(other);
    }

    @Override
    public int hashCode() {
        return local.hashCode();
    }

    @Override
    public String toString() {
        return local.toString();
    }
}
