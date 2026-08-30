package com.demo.amps.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The map-of-maps contract against an in-memory store, with the counters
 * proving the property the flattened representation exists for: entry-level
 * changes cost entry-level store calls, never an outer-map rewrite.
 */
class NestedCacheMapTest {

    private InMemoryNestedMapStore<String> store;

    @BeforeEach
    void newStore() {
        store = new InMemoryNestedMapStore<>();
    }

    @Test
    @DisplayName("putEntry() touches one (outer, inner) pair in the store")
    void putEntryIsFineGrained() {
        NestedCacheMap<String> cache = NestedCacheMap.hydrate(store);

        cache.putEntry("p1", "AAPL", "250");
        cache.putEntry("p1", "MSFT", "100");
        assertNull(cache.putEntry("p2", "AAPL", "75"));

        assertEquals(3, store.entryStores);
        assertEquals(Map.of("AAPL", "250", "MSFT", "100"), cache.get("p1"));
        assertEquals("250", cache.getEntry("p1", "AAPL"));

        assertEquals("250", cache.putEntry("p1", "AAPL", "300"),
                "putEntry returns the replaced inner value");
        assertEquals("300", store.backing.get("p1").get("AAPL"));
        assertEquals("100", store.backing.get("p1").get("MSFT"), "siblings stay untouched");
    }

    @Test
    @DisplayName("removing the last inner entry removes the outer key on both sides")
    void emptyMeansAbsent() {
        NestedCacheMap<String> cache = NestedCacheMap.hydrate(store);
        cache.putEntry("p1", "AAPL", "250");

        assertEquals("250", cache.removeEntry("p1", "AAPL"));

        assertFalse(cache.containsKey("p1"));
        assertNull(cache.get("p1"), "get never returns an empty inner map");
        assertFalse(store.backing.containsKey("p1"));
    }

    @Test
    @DisplayName("put() of a whole inner map deletes the stale inner keys")
    void putReplacesIncludingDeletes() {
        NestedCacheMap<String> cache = NestedCacheMap.hydrate(store);
        cache.putEntry("p1", "AAPL", "250");
        cache.putEntry("p1", "MSFT", "100");

        cache.put("p1", Map.of("AAPL", "300", "TSLA", "50"));

        assertEquals(Map.of("AAPL", "300", "TSLA", "50"), cache.get("p1"));
        assertEquals(Map.of("AAPL", "300", "TSLA", "50"), store.backing.get("p1"));
        assertEquals(1, store.entryDeletes, "exactly the stale MSFT record is deleted");
    }

    @Test
    @DisplayName("put() consults the store for stale keys when the outer key is not local")
    void putAfterEvictionStillDeletesStaleKeys() {
        NestedCacheMap<String> cache = NestedCacheMap.hydrate(store);
        cache.putEntry("p1", "AAPL", "250");
        cache.putEntry("p1", "MSFT", "100");
        cache.evictLocal("p1");
        int loadsBefore = store.loadOuters;

        cache.put("p1", Map.of("TSLA", "50"));

        assertEquals(loadsBefore + 1, store.loadOuters,
                "the stale set must come from the store when local does not know the key");
        assertEquals(Map.of("TSLA", "50"), store.backing.get("p1"),
                "AAPL and MSFT must be gone from the store, not just replaced locally");
    }

    @Test
    @DisplayName("put() of an empty map is remove()")
    void putEmptyIsRemove() {
        NestedCacheMap<String> cache = NestedCacheMap.hydrate(store);
        cache.putEntry("p1", "AAPL", "250");

        cache.put("p1", Map.of());

        assertFalse(cache.containsKey("p1"));
        assertFalse(store.backing.containsKey("p1"));
    }

    @Test
    @DisplayName("get() reads a missing outer key through from the store")
    void readThroughOnOuterMiss() {
        store.storeEntry("remote", "k", "v");
        NestedCacheMap<String> cache = NestedCacheMap.hydrate(store);
        cache.evictLocal("remote");
        int loadsBefore = store.loadOuters;

        assertEquals(Map.of("k", "v"), cache.get("remote"));
        assertEquals(loadsBefore + 1, store.loadOuters);
        assertEquals(Map.of("k", "v"), cache.get("remote"));
        assertEquals(loadsBefore + 1, store.loadOuters, "second get must be local");
    }

    @Test
    @DisplayName("hydrate() recovers exactly what a previous instance stored")
    void restartRecoversState() {
        NestedCacheMap<String> original = NestedCacheMap.hydrate(store);
        original.putEntry("p1", "AAPL", "250");
        original.putEntry("p1", "MSFT", "100");
        original.putEntry("p2", "AAPL", "75");
        original.removeEntry("p2", "AAPL");

        NestedCacheMap<String> restarted = NestedCacheMap.hydrate(store);

        assertEquals(original, restarted);
        assertFalse(restarted.containsKey("p2"));
    }

    @Test
    @DisplayName("inner maps handed out are immutable snapshots")
    void innerMapsAreImmutable() {
        NestedCacheMap<String> cache = NestedCacheMap.hydrate(store);
        cache.putEntry("p1", "AAPL", "250");

        Map<String, String> inner = cache.get("p1");
        assertThrows(UnsupportedOperationException.class, () -> inner.put("MSFT", "smuggled"));
        assertThrows(UnsupportedOperationException.class, () -> inner.remove("AAPL"));

        assertEquals("250", store.backing.get("p1").get("AAPL"));
        assertTrue(store.backing.get("p1").size() == 1);
    }

    @Test
    @DisplayName("clear() empties both sides; views are read-only")
    void clearAndViews() {
        NestedCacheMap<String> cache = NestedCacheMap.hydrate(store);
        cache.putEntry("p1", "AAPL", "250");

        assertThrows(UnsupportedOperationException.class, () -> cache.keySet().remove("p1"));

        cache.clear();
        assertTrue(cache.isEmpty());
        assertTrue(store.backing.isEmpty());
    }
}
