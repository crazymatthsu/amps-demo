package com.demo.amps.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cache contract, pinned against an in-memory store: hydration,
 * write-through ordering, read-through on miss, and the view rules. Every
 * behavior here is one the AMPS integration relies on but does not need a
 * server to prove.
 */
class PersistentCacheMapTest {

    private InMemoryMapStore<String> store;

    @BeforeEach
    void newStore() {
        store = new InMemoryMapStore<>();
    }

    @Test
    @DisplayName("hydrate() loads every stored entry before returning")
    void hydrationLoadsEverything() {
        store.backing.put("a", "1");
        store.backing.put("b", "2");

        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);

        assertEquals(2, cache.size());
        assertEquals("1", cache.get("a"));
        assertEquals(1, store.loadAlls);
        assertEquals(0, store.loads, "hydrated entries must be served locally, not re-loaded");
    }

    @Test
    @DisplayName("get() on a local miss reads through and caches the hit")
    void readThroughCachesTheHit() {
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);
        store.backing.put("late", "arrival"); // written by "another process"

        assertFalse(cache.containsKey("late"), "containsKey describes the local replica");
        assertEquals("arrival", cache.get("late"));
        assertEquals(1, store.loads);

        assertEquals("arrival", cache.get("late"));
        assertEquals(1, store.loads, "the second get must be a local hit");
        assertTrue(cache.containsKey("late"));
    }

    @Test
    @DisplayName("get() of a key nobody has returns null and caches nothing")
    void missOnBothSidesIsNull() {
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);

        assertNull(cache.get("ghost"));
        assertNull(cache.get("ghost"));
        assertEquals(2, store.loads, "absent keys are asked again -- there is no negative cache");
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("put() stores remotely, then locally")
    void putWritesThrough() {
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);

        assertNull(cache.put("k", "v"));

        assertEquals("v", store.backing.get("k"));
        assertEquals("v", cache.get("k"));
        assertEquals(1, store.stores);
    }

    @Test
    @DisplayName("a store failure leaves the local map untouched")
    void storeFailureLeavesLocalAlone() {
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);
        cache.put("k", "old");
        store.failWith = new CacheStoreException("amps is down");

        assertThrows(CacheStoreException.class, () -> cache.put("k", "new"));

        assertEquals("old", cache.get("k"), "local must still agree with the store");
        assertThrows(CacheStoreException.class, () -> cache.remove("k"));
        assertEquals("old", cache.get("k"));
    }

    @Test
    @DisplayName("remove() deletes from the store even when the key is not local")
    void removeReachesTheStoreOnLocalMiss() {
        store.backing.put("remote-only", "v");
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);
        cache.evictLocal("remote-only");

        cache.remove("remote-only");

        assertEquals(1, store.deletes);
        assertFalse(store.backing.containsKey("remote-only"));
        assertNull(cache.get("remote-only"));
    }

    @Test
    @DisplayName("putAll() batches to the store and clear() empties it")
    void putAllAndClear() {
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);

        cache.putAll(Map.of("a", "1", "b", "2"));
        assertEquals(2, store.backing.size());

        cache.clear();
        assertTrue(cache.isEmpty());
        assertTrue(store.backing.isEmpty());
    }

    @Test
    @DisplayName("evictLocal() keeps the store; refresh() re-syncs from it")
    void evictionAndRefresh() {
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);
        cache.put("keep", "v");

        cache.evictLocal("keep");
        assertEquals(0, cache.size());
        assertEquals("v", store.backing.get("keep"), "eviction must not delete remotely");

        store.backing.put("added", "elsewhere");
        store.backing.remove("keep");
        cache.refresh();

        assertEquals(Map.of("added", "elsewhere"), cache);
    }

    @Test
    @DisplayName("views cannot mutate the cache behind the store's back")
    void viewsAreReadOnly() {
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);
        cache.put("k", "v");

        assertThrows(UnsupportedOperationException.class, () -> cache.keySet().remove("k"));
        assertThrows(UnsupportedOperationException.class, () -> cache.values().clear());
        Map.Entry<String, String> entry = cache.entrySet().iterator().next();
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue("smuggled"));
        Iterator<Map.Entry<String, String>> iterator = cache.entrySet().iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);

        assertEquals("v", store.backing.get("k"));
    }

    @Test
    @DisplayName("null keys and values are rejected up front")
    void nullsAreRejected() {
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);

        assertThrows(NullPointerException.class, () -> cache.put(null, "v"));
        assertThrows(NullPointerException.class, () -> cache.put("k", null));
        assertEquals(0, store.stores);
    }

    @Test
    @DisplayName("equals() follows the Map contract against ordinary maps")
    void equalsIsTheMapContract() {
        PersistentCacheMap<String> cache = PersistentCacheMap.hydrate(store);
        cache.put("a", "1");

        assertEquals(Map.of("a", "1"), cache);
        assertTrue(cache.equals(Map.of("a", "1")));
        assertFalse(cache.equals(Map.of("a", "2")));
    }

    @Test
    @DisplayName("a fresh instance over the same store recovers the same contents")
    void restartRecoversState() {
        PersistentCacheMap<String> original = PersistentCacheMap.hydrate(store);
        original.put("a", "1");
        original.put("b", "2");
        original.remove("a");

        PersistentCacheMap<String> restarted = PersistentCacheMap.hydrate(store);

        assertEquals(original, restarted);
        assertNotSameContentsByAccident(original, restarted);
    }

    private static void assertNotSameContentsByAccident(PersistentCacheMap<String> original,
                                                        PersistentCacheMap<String> restarted) {
        // The recovered map must be equal but independent: mutating one local
        // replica must not touch the other except through the store.
        restarted.evictLocal("b");
        assertEquals(1, original.size());
        assertEquals(0, restarted.size());
    }
}
