package com.demo.amps.hazelcast;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A {@link TierStore} on plain maps, with call counters so tests can assert
 * WHICH operations the adapter routed -- e.g. that a bulk load arrived as one
 * {@code loadAll}, not a storm of {@code load}s.
 */
final class InMemoryTierStore<V> implements TierStore<V> {

    final Map<String, Map<String, V>> backing = new LinkedHashMap<>();
    int loads;
    int loadKeysCalls;
    int loadAlls;
    int stores;
    int storeAlls;
    int deletes;
    int deleteAlls;

    private Map<String, V> map(String map) {
        return backing.computeIfAbsent(map, name -> new LinkedHashMap<>());
    }

    @Override
    public V load(String map, String key) {
        loads++;
        return map(map).get(key);
    }

    @Override
    public Set<String> loadKeys(String map) {
        loadKeysCalls++;
        return new LinkedHashSet<>(map(map).keySet());
    }

    @Override
    public Map<String, V> loadAll(String map, Collection<String> keys) {
        loadAlls++;
        Map<String, V> found = new LinkedHashMap<>();
        for (String key : keys) {
            V value = map(map).get(key);
            if (value != null) {
                found.put(key, value);
            }
        }
        return found;
    }

    @Override
    public void store(String map, String key, V value) {
        stores++;
        map(map).put(key, value);
    }

    @Override
    public void storeAll(String map, Map<String, V> entries) {
        storeAlls++;
        map(map).putAll(entries);
    }

    @Override
    public void delete(String map, String key) {
        deletes++;
        map(map).remove(key);
    }

    @Override
    public void deleteAll(String map, Collection<String> keys) {
        deleteAlls++;
        map(map).keySet().removeAll(Set.copyOf(keys));
    }
}
