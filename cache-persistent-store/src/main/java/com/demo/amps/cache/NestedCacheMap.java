package com.demo.amps.cache;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@code Map<String, Map<String, V>>} -- the map-of-maps cache -- persisted
 * through a {@link NestedMapStore}.
 *
 * <p>Same contract as {@link PersistentCacheMap} (hydrate at startup,
 * write-through before local, read-through {@code get}, no nulls, read-only
 * views), with the two-level structure adding its own rules:
 *
 * <ul>
 *   <li><b>The fine-grained operations are the point.</b>
 *       {@link #putEntry} and {@link #removeEntry} touch one
 *       {@code (outerKey, innerKey)} pair -- one small record in AMPS, no
 *       read-modify-write of the outer map. Prefer them over {@code put} of a
 *       whole inner map whenever you hold one entry's change.</li>
 *   <li><b>Inner maps are immutable snapshots.</b> What {@code get} returns
 *       cannot be mutated in place -- there would be no way to write such a
 *       mutation through. All changes go through this class's methods.</li>
 *   <li><b>Empty means absent.</b> An outer key with no inner entries has no
 *       records in AMPS, so it does not exist: {@code put(outer, Map.of())}
 *       is {@code remove(outer)}, {@code get} never returns an empty map, and
 *       removing the last inner entry removes the outer key.</li>
 *   <li><b>Replacing a whole outer map is not atomic.</b> {@link #put} writes
 *       the new entries, then deletes the stale ones; a concurrent reader can
 *       briefly see both. This is the honest cost of the flattened
 *       representation -- see the README for the alternative that trades the
 *       other way.</li>
 * </ul>
 *
 * @param <V> the inner value type; must survive a JSON round trip
 */
public final class NestedCacheMap<V> implements Map<String, Map<String, V>> {

    /** Outer key to an immutable inner-map snapshot, replaced wholesale on write. */
    private final ConcurrentHashMap<String, Map<String, V>> local = new ConcurrentHashMap<>();
    private final Map<String, Map<String, V>> readOnly = Collections.unmodifiableMap(local);
    private final NestedMapStore<V> store;

    private NestedCacheMap(NestedMapStore<V> store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Creates a cache over {@code store} and hydrates every outer key from it. */
    public static <V> NestedCacheMap<V> hydrate(NestedMapStore<V> store) {
        NestedCacheMap<V> cache = new NestedCacheMap<>(store);
        store.loadAll().forEach((outerKey, inner) -> {
            if (!inner.isEmpty()) {
                cache.local.put(outerKey, snapshot(inner));
            }
        });
        return cache;
    }

    // ------------------------------------------------------------------
    // Entry-level operations: one record each, the common path.
    // ------------------------------------------------------------------

    /**
     * Sets one inner entry, creating the outer key if needed. One record
     * published to AMPS; the rest of the inner map is not rewritten.
     *
     * @return the previous inner value in the local replica, or {@code null}
     */
    public V putEntry(String outerKey, String innerKey, V value) {
        Objects.requireNonNull(outerKey, "cache keys must not be null");
        Objects.requireNonNull(innerKey, "cache keys must not be null");
        Objects.requireNonNull(value, "cache values must not be null");
        store.storeEntry(outerKey, innerKey, value);

        AtomicReference<V> previous = new AtomicReference<>();
        local.compute(outerKey, (key, current) -> {
            Map<String, V> next = current == null
                    ? new LinkedHashMap<String, V>() : new LinkedHashMap<>(current);
            previous.set(next.put(innerKey, value));
            return Collections.unmodifiableMap(next);
        });
        return previous.get();
    }

    /**
     * Removes one inner entry; removing the last one removes the outer key.
     *
     * @return the previous inner value in the local replica, or {@code null}
     */
    public V removeEntry(String outerKey, String innerKey) {
        Objects.requireNonNull(outerKey, "cache keys must not be null");
        Objects.requireNonNull(innerKey, "cache keys must not be null");
        store.deleteEntry(outerKey, innerKey);

        AtomicReference<V> previous = new AtomicReference<>();
        local.computeIfPresent(outerKey, (key, current) -> {
            if (!current.containsKey(innerKey)) {
                return current;
            }
            Map<String, V> next = new LinkedHashMap<>(current);
            previous.set(next.remove(innerKey));
            return next.isEmpty() ? null : Collections.unmodifiableMap(next);
        });
        return previous.get();
    }

    /** One inner value, read through the outer map ({@code get(outerKey)} semantics). */
    public V getEntry(String outerKey, String innerKey) {
        Map<String, V> inner = get(outerKey);
        return inner == null ? null : inner.get(innerKey);
    }

    // ------------------------------------------------------------------
    // Map surface. get() is read-through; other reads are the local replica.
    // ------------------------------------------------------------------

    @Override
    public Map<String, V> get(Object key) {
        if (!(key instanceof String outerKey)) {
            return null;
        }
        Map<String, V> inner = local.get(outerKey);
        if (inner != null) {
            return inner;
        }
        Map<String, V> loaded = store.loadOuter(outerKey);
        if (loaded.isEmpty()) {
            return null;
        }
        Map<String, V> snap = snapshot(loaded);
        Map<String, V> raced = local.putIfAbsent(outerKey, snap);
        return raced != null ? raced : snap;
    }

    /**
     * Replaces the whole inner map for {@code outerKey}: writes the new
     * entries, then deletes whichever previously-stored inner keys the new map
     * no longer has. The stale set comes from the local replica when it knows
     * this outer key, and from the store when it does not (this instance may
     * have evicted it, or never seen it).
     */
    @Override
    public Map<String, V> put(String outerKey, Map<String, V> inner) {
        Objects.requireNonNull(outerKey, "cache keys must not be null");
        Objects.requireNonNull(inner, "cache values must not be null");
        if (inner.isEmpty()) {
            return remove(outerKey);
        }
        Map<String, V> next = snapshot(inner);
        Map<String, V> previous = local.get(outerKey);

        store.storeEntries(outerKey, next);
        Map<String, V> stale = previous != null ? previous : store.loadOuter(outerKey);
        for (String innerKey : stale.keySet()) {
            if (!next.containsKey(innerKey)) {
                store.deleteEntry(outerKey, innerKey);
            }
        }
        return local.put(outerKey, next);
    }

    @Override
    public Map<String, V> remove(Object key) {
        if (!(key instanceof String outerKey)) {
            return null;
        }
        store.deleteOuter(outerKey);
        return local.remove(outerKey);
    }

    @Override
    public void putAll(Map<? extends String, ? extends Map<String, V>> entries) {
        entries.forEach(this::put);
    }

    @Override
    public void clear() {
        store.deleteAll();
        local.clear();
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
    // Cache-specific operations.
    // ------------------------------------------------------------------

    /** Drops {@code outerKey} from the LOCAL replica only; the store keeps it. */
    public void evictLocal(String outerKey) {
        local.remove(outerKey);
    }

    /** Re-reads the whole store, replacing local contents. Not atomic. */
    public void refresh() {
        Map<String, Map<String, V>> fresh = store.loadAll();
        fresh.forEach((outerKey, inner) -> {
            if (!inner.isEmpty()) {
                local.put(outerKey, snapshot(inner));
            }
        });
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
    public Collection<Map<String, V>> values() {
        return readOnly.values();
    }

    @Override
    public Set<Entry<String, Map<String, V>>> entrySet() {
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

    /** An insertion-ordered immutable copy; also rejects null inner keys/values. */
    private static <V> Map<String, V> snapshot(Map<String, V> inner) {
        Map<String, V> copy = new LinkedHashMap<>(inner.size());
        inner.forEach((key, value) -> {
            Objects.requireNonNull(key, "cache keys must not be null");
            Objects.requireNonNull(value, "cache values must not be null");
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }
}
