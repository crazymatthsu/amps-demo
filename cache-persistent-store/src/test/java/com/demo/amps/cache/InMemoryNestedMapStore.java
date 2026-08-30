package com.demo.amps.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link NestedMapStore} on plain maps, tracking entry-level calls so tests
 * can assert the cache used the fine-grained operations (one record per
 * change) rather than rewriting outer maps.
 */
final class InMemoryNestedMapStore<V> implements NestedMapStore<V> {

    final Map<String, Map<String, V>> backing = new LinkedHashMap<>();
    int loadOuters;
    int entryStores;
    int entryDeletes;

    @Override
    public Map<String, V> loadOuter(String outerKey) {
        loadOuters++;
        Map<String, V> inner = backing.get(outerKey);
        return inner == null ? Map.of() : new LinkedHashMap<>(inner);
    }

    @Override
    public Map<String, Map<String, V>> loadAll() {
        Map<String, Map<String, V>> copy = new LinkedHashMap<>();
        backing.forEach((outer, inner) -> copy.put(outer, new LinkedHashMap<>(inner)));
        return copy;
    }

    @Override
    public void storeEntry(String outerKey, String innerKey, V value) {
        entryStores++;
        backing.computeIfAbsent(outerKey, key -> new LinkedHashMap<>()).put(innerKey, value);
    }

    @Override
    public void storeEntries(String outerKey, Map<String, V> entries) {
        entries.forEach((innerKey, value) -> storeEntry(outerKey, innerKey, value));
    }

    @Override
    public void deleteEntry(String outerKey, String innerKey) {
        entryDeletes++;
        Map<String, V> inner = backing.get(outerKey);
        if (inner != null) {
            inner.remove(innerKey);
            if (inner.isEmpty()) {
                backing.remove(outerKey);
            }
        }
    }

    @Override
    public void deleteOuter(String outerKey) {
        backing.remove(outerKey);
    }

    @Override
    public void deleteAll() {
        backing.clear();
    }
}
