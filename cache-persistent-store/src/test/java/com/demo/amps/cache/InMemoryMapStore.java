package com.demo.amps.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link MapStore} on a plain map, with call counters so tests can assert
 * not just what the cache returned but which side answered -- read-through is
 * only proven by a {@code load} that actually happened.
 */
final class InMemoryMapStore<V> implements MapStore<V> {

    final Map<String, V> backing = new LinkedHashMap<>();
    int loads;
    int loadAlls;
    int stores;
    int deletes;
    /** When set, every mutating call throws -- for write-through failure tests. */
    RuntimeException failWith;

    @Override
    public V load(String key) {
        loads++;
        return backing.get(key);
    }

    @Override
    public Map<String, V> loadAll() {
        loadAlls++;
        return new LinkedHashMap<>(backing);
    }

    @Override
    public void store(String key, V value) {
        failFast();
        stores++;
        backing.put(key, value);
    }

    @Override
    public void storeAll(Map<String, V> entries) {
        failFast();
        stores += entries.size();
        backing.putAll(entries);
    }

    @Override
    public void delete(String key) {
        failFast();
        deletes++;
        backing.remove(key);
    }

    @Override
    public void deleteAll() {
        failFast();
        backing.clear();
    }

    private void failFast() {
        if (failWith != null) {
            throw failWith;
        }
    }
}
