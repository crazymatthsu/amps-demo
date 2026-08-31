package com.demo.amps.cache.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.cache.AmpsMapStore;
import com.demo.amps.cache.AmpsNestedMapStore;
import com.demo.amps.cache.CacheTopics;
import com.demo.amps.cache.NestedCacheMap;
import com.demo.amps.cache.PersistentCacheMap;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The requirements from TODO.md, run against a real AMPS instance on the
 * {@code cache} flow: write through the cache and find the records by querying
 * AMPS directly; hydrate a fresh instance ("process restart") and get the same
 * map back; read through on a local miss; and -- last, because it invalidates
 * every connection -- restart the SERVER and recover everything from the
 * persistent SOW.
 *
 * <p>Tests use disjoint key namespaces so they stay independent on the shared
 * instance; only the restart test is order-sensitive, and it runs last.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class CachePersistentStoreIT {

    private static final long TIMEOUT_MS = 10_000;

    private AmpsTestServer server;
    private Client client;

    @BeforeAll
    void startServer() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.CACHE);
        assumeTrue(unavailable.isEmpty(),
                () -> "integration test prerequisites: " + unavailable.orElse(""));

        server = AmpsTestServer.start(AmpsFlow.CACHE);
        client = connect("cache-it");
    }

    @AfterAll
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    // ------------------------------------------------------------------
    // Flat cache: Map<String, ?> on cache.entries.
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("put() lands in the SOW as one {key, value} record per entry")
    void writesLandInTheSow() throws Exception {
        PersistentCacheMap<Object> cache = flatCache();

        cache.put("sow.string", "plain");
        cache.put("sow.long", 42L);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Ada");
        profile.put("level", 7L);
        cache.put("sow.map", profile);

        Map<String, JsonObject> records = recordsByKey(CacheTopics.ENTRIES, "key");
        assertEquals("plain", records.get("sow.string").get("value").getAsString());
        assertEquals(42L, records.get("sow.long").get("value").getAsLong());
        assertEquals("Ada", records.get("sow.map").get("value").getAsJsonObject()
                .get("name").getAsString());
        assertEquals(7L, records.get("sow.map").get("value").getAsJsonObject()
                .get("level").getAsLong());
    }

    @Test
    @Order(2)
    @DisplayName("a fresh cache instance hydrates to an equal map -- the process-restart story")
    void freshInstanceHydrates() throws Exception {
        PersistentCacheMap<Object> original = flatCache();
        original.put("hydrate.string", "v");
        original.put("hydrate.long", 7L);
        original.put("hydrate.map", Map.of("inner", true));

        PersistentCacheMap<Object> restarted = flatCache();

        for (String key : List.of("hydrate.string", "hydrate.long", "hydrate.map")) {
            assertEquals(original.get(key), restarted.get(key),
                    key + " must recover with the same type and value");
        }
        assertEquals(7L, restarted.get("hydrate.long"),
                "integral values must hydrate as Long, not Double");
    }

    @Test
    @Order(3)
    @DisplayName("get() reads through: a key written by another client is found on demand")
    void readThroughFindsWhatAnotherProcessWrote() throws Exception {
        PersistentCacheMap<Object> cache = flatCache();
        assertNull(cache.get("readthrough.late"), "not there yet");

        try (Client other = connect("cache-it-other")) {
            AmpsMapStore.untyped(other, CacheTopics.ENTRIES)
                    .store("readthrough.late", "from elsewhere");
        }

        assertFalse(cache.containsKey("readthrough.late"), "still a local miss");
        assertEquals("from elsewhere", cache.get("readthrough.late"));
        assertTrue(cache.containsKey("readthrough.late"), "the hit is now cached locally");
    }

    @Test
    @Order(4)
    @DisplayName("remove() deletes exactly its record; absent keys are a quiet no-op")
    void deleteRemovesExactlyOneRecord() throws Exception {
        PersistentCacheMap<Object> cache = flatCache();
        cache.put("delete.a", 1L);
        cache.put("delete.b", 2L);

        cache.remove("delete.a");
        cache.remove("delete.never-existed");

        Map<String, JsonObject> records = recordsByKey(CacheTopics.ENTRIES, "key");
        assertFalse(records.containsKey("delete.a"));
        assertTrue(records.containsKey("delete.b"));
        assertNull(flatCache().get("delete.a"), "a fresh instance agrees it is gone");
    }

    @Test
    @Order(5)
    @DisplayName("awkward keys survive store, load and delete; the impossible one is rejected")
    void awkwardKeysRoundTrip() throws Exception {
        PersistentCacheMap<Object> cache = flatCache();

        for (String key : List.of("quotes.o'brien", "quotes.say \"hi\"",
                "quotes.útf-8 ключ", "quotes.with spaces")) {
            cache.put(key, "v:" + key);
            cache.evictLocal(key);
            assertEquals("v:" + key, cache.get(key), "read-through must find " + key);
            cache.remove(key);
            assertNull(flatCache().get(key), key + " must be gone after remove");
        }

        assertThrows(IllegalArgumentException.class,
                () -> cache.put("both ' and \"", "v"),
                "a key that no filter literal can express must be rejected before storing");
    }

    // ------------------------------------------------------------------
    // Map of maps: one record per (outerKey, innerKey) on cache.nested.entries.
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("each inner entry is its own SOW record, and updating one leaves siblings alone")
    void nestedEntriesAreIndividualRecords() throws Exception {
        NestedCacheMap<Object> cache = nestedCache();
        cache.putEntry("nested.p1", "AAPL", Map.of("qty", 250L));
        cache.putEntry("nested.p1", "MSFT", Map.of("qty", 100L));
        cache.putEntry("nested.p2", "AAPL", Map.of("qty", 75L));

        String siblingBefore = nestedRecord("nested.p1", "MSFT").toString();
        cache.putEntry("nested.p1", "AAPL", Map.of("qty", 300L));

        assertEquals(2, records(CacheTopics.NESTED_ENTRIES,
                "/outerKey = 'nested.p1'").size(), "still one record per inner key");
        assertEquals(300L, nestedRecord("nested.p1", "AAPL").get("value")
                .getAsJsonObject().get("qty").getAsLong());
        assertEquals(siblingBefore, nestedRecord("nested.p1", "MSFT").toString(),
                "the sibling record must be byte-identical -- it was never republished");
    }

    @Test
    @Order(7)
    @DisplayName("outer maps reassemble from the flattened records, hydrated or read through")
    void nestedOuterMapReassembles() throws Exception {
        NestedCacheMap<Object> original = nestedCache();
        original.putEntry("reassemble.p1", "AAPL", Map.of("qty", 1L));
        original.putEntry("reassemble.p1", "MSFT", Map.of("qty", 2L));

        NestedCacheMap<Object> restarted = nestedCache();
        assertEquals(original.get("reassemble.p1"), restarted.get("reassemble.p1"));

        restarted.evictLocal("reassemble.p1");
        assertEquals(Map.of("AAPL", Map.of("qty", 1L), "MSFT", Map.of("qty", 2L)),
                restarted.get("reassemble.p1"), "read-through reassembles the whole inner map");
    }

    @Test
    @Order(8)
    @DisplayName("replacing an outer map deletes its stale inner records from the SOW")
    void nestedReplaceDeletesStale() throws Exception {
        NestedCacheMap<Object> cache = nestedCache();
        cache.putEntry("replace.p1", "AAPL", Map.of("qty", 1L));
        cache.putEntry("replace.p1", "MSFT", Map.of("qty", 2L));

        cache.put("replace.p1", Map.of("TSLA", Map.of("qty", 3L)));

        List<JsonObject> remaining = records(CacheTopics.NESTED_ENTRIES,
                "/outerKey = 'replace.p1'");
        assertEquals(1, remaining.size());
        assertEquals("TSLA", remaining.get(0).get("innerKey").getAsString());
    }

    @Test
    @Order(9)
    @DisplayName("remove(outer) clears every record of that outer key and no others")
    void nestedDeleteOuterIsScoped() throws Exception {
        NestedCacheMap<Object> cache = nestedCache();
        cache.putEntry("scope.p1", "A", 1L);
        cache.putEntry("scope.p1", "B", 2L);
        cache.putEntry("scope.p2", "A", 3L);

        cache.remove("scope.p1");

        assertEquals(0, records(CacheTopics.NESTED_ENTRIES, "/outerKey = 'scope.p1'").size());
        assertEquals(1, records(CacheTopics.NESTED_ENTRIES, "/outerKey = 'scope.p2'").size());
    }

    // ------------------------------------------------------------------
    // Server restart: the store outlives the broker process too.
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("after a server restart, fresh caches hydrate everything from the recovered SOW")
    void serverRestartKeepsTheStore() throws Exception {
        PersistentCacheMap<Object> flat = flatCache();
        flat.put("restart.marker", Map.of("survives", true));
        NestedCacheMap<Object> nested = nestedCache();
        nested.putEntry("restart.p1", "AAPL", Map.of("qty", 250L));
        int flatBefore = records(CacheTopics.ENTRIES, null).size();
        int nestedBefore = records(CacheTopics.NESTED_ENTRIES, null).size();

        server.restart();
        client.close();               // that connection died with the server
        client = connect("cache-it-after-restart");

        PersistentCacheMap<Object> recoveredFlat = flatCache();
        NestedCacheMap<Object> recoveredNested = nestedCache();

        assertEquals(Map.of("survives", true), recoveredFlat.get("restart.marker"));
        assertEquals(Map.of("AAPL", Map.of("qty", 250L)), recoveredNested.get("restart.p1"));
        assertEquals(flatBefore, records(CacheTopics.ENTRIES, null).size(),
                "every flat record must survive the restart");
        assertEquals(nestedBefore, records(CacheTopics.NESTED_ENTRIES, null).size(),
                "every nested record must survive the restart");
    }

    // ------------------------------------------------------------------
    // Plumbing.
    // ------------------------------------------------------------------

    private PersistentCacheMap<Object> flatCache() {
        return PersistentCacheMap.hydrate(
                new AmpsMapStore<>(client, CacheTopics.ENTRIES, Object.class, TIMEOUT_MS));
    }

    private NestedCacheMap<Object> nestedCache() {
        return NestedCacheMap.hydrate(new AmpsNestedMapStore<>(
                client, CacheTopics.NESTED_ENTRIES, Object.class, TIMEOUT_MS));
    }

    private Client connect(String name) throws Exception {
        Client connected = new Client(name);
        try {
            connected.connect(server.uri());
            connected.logon(TIMEOUT_MS);
        } catch (Exception e) {
            connected.close();
            throw e;
        }
        return connected;
    }

    /** Raw SOW records, straight off the wire -- the "query AMPS to get data" check. */
    private List<JsonObject> records(String topic, String filter) throws Exception {
        List<JsonObject> found = new ArrayList<>();
        Command command = new Command("sow").setTopic(topic).setTimeout(TIMEOUT_MS);
        if (filter != null) {
            command.setFilter(filter);
        }
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    found.add(JsonParser.parseString(message.getData()).getAsJsonObject());
                }
            }
        }
        return found;
    }

    private Map<String, JsonObject> recordsByKey(String topic, String keyField) throws Exception {
        Map<String, JsonObject> byKey = new LinkedHashMap<>();
        for (JsonObject record : records(topic, null)) {
            byKey.put(record.get(keyField).getAsString(), record);
        }
        return byKey;
    }

    private JsonObject nestedRecord(String outerKey, String innerKey) throws Exception {
        List<JsonObject> matches = records(CacheTopics.NESTED_ENTRIES,
                "/outerKey = '" + outerKey + "' AND /innerKey = '" + innerKey + "'");
        assertEquals(1, matches.size(), "expected exactly one record for ("
                + outerKey + ", " + innerKey + ")");
        return matches.get(0);
    }
}
