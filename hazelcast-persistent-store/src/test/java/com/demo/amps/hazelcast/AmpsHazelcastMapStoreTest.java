package com.demo.amps.hazelcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adapter is a router: every Hazelcast SPI call must land on the tier
 * store scoped by this map's name, batches must stay batches, and nothing
 * may work before {@code init}. No Hazelcast member is involved -- the SPI
 * interfaces are just interfaces.
 */
class AmpsHazelcastMapStoreTest {

    private InMemoryTierStore<String> tier;
    private AmpsHazelcastMapStore<String> store;

    @BeforeEach
    void newStore() {
        tier = new InMemoryTierStore<>();
        store = new AmpsHazelcastMapStore<>(tier, "orders");
    }

    @Test
    @DisplayName("operations are scoped to this map's name in the shared tier")
    void scopedByMapName() {
        tier.store("other-map", "k", "not ours");

        store.store("k", "ours");

        assertEquals("ours", tier.backing.get("orders").get("k"));
        assertEquals("not ours", tier.backing.get("other-map").get("k"));
        assertEquals("ours", store.load("k"));
        assertEquals(Set.of("k"), Set.copyOf((java.util.Collection<String>) store.loadAllKeys()));
    }

    @Test
    @DisplayName("load of an absent key is null; loadAll omits absent keys")
    void absentKeys() {
        store.store("present", "v");

        assertNull(store.load("ghost"));
        assertEquals(Map.of("present", "v"),
                store.loadAll(List.of("present", "ghost")));
        assertEquals(1, tier.loadAlls, "bulk load must be ONE tier call");
    }

    @Test
    @DisplayName("storeAll and deleteAll stay batched all the way down")
    void batchesStayBatched() {
        store.storeAll(Map.of("a", "1", "b", "2", "c", "3"));
        assertEquals(1, tier.storeAlls);
        assertEquals(0, tier.stores, "a batch must not decay into single stores");

        store.deleteAll(List.of("a", "b"));
        assertEquals(1, tier.deleteAlls);
        assertEquals(Map.of("c", "3"), tier.backing.get("orders"));
    }

    @Test
    @DisplayName("delete of an absent key is a quiet no-op")
    void deleteAbsent() {
        store.delete("never-existed");
        assertEquals(1, tier.deletes);
    }

    @Test
    @DisplayName("an un-initialized adapter fails loudly, naming the map")
    void failsBeforeInit() {
        AmpsHazelcastMapStore<Object> raw =
                new AmpsHazelcastMapStore<>(GsonValueCodec.untyped());

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> raw.load("k"));
        assertTrue(error.getMessage().contains("init()"));
    }
}
