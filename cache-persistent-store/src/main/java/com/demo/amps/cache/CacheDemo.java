package com.demo.amps.cache;

import com.crankuptheamps.client.Client;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The whole story against a live instance: write through the cache, see the
 * records in the SOW, "restart" by building a fresh cache from nothing but
 * AMPS, read through on a miss, and do it again for the map-of-maps shape.
 *
 * <pre>
 *   AMPS_FLOW=cache ./server/scripts/amps.sh start     # or scripts/amps-cache.sh start
 *   ./gradlew :cache-persistent-store:run
 * </pre>
 */
public final class CacheDemo {

    private CacheDemo() {
    }

    public static void main(String[] args) throws Exception {
        Console.title("A local Map with AMPS as its persistent store");

        try (Client client = connectOrExplain()) {
            AmpsMapStore<Object> store = AmpsMapStore.untyped(client, CacheTopics.ENTRIES);
            AmpsNestedMapStore<Object> nestedStore =
                    AmpsNestedMapStore.untyped(client, CacheTopics.NESTED_ENTRIES);

            Console.step("Starting from an empty store, so re-runs tell one story");
            store.deleteAll();
            nestedStore.deleteAll();

            flatCache(client, store);
            nestedCache(nestedStore);

            Console.step("Done");
            Console.note("The records are still in the SOW -- rerun this demo, restart the "
                    + "server first, or browse them in the admin console at "
                    + DemoConfig.adminUrl() + ". Recovery is the default, not an event.");
        }
    }

    // ------------------------------------------------------------------
    // Part 1: Map<String, ?> on one SOW topic.
    // ------------------------------------------------------------------

    private static void flatCache(Client client, AmpsMapStore<Object> store) {
        Console.step("Write-through: put() publishes each entry to '" + CacheTopics.ENTRIES + "'");
        PersistentCacheMap<Object> cache = PersistentCacheMap.hydrate(store);
        Console.kv("entries after hydrating an empty store", cache.size());

        cache.put("motd", "cache like nobody's watching");
        cache.put("answer", 42L);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Ada");
        profile.put("level", 7L);
        profile.put("active", true);
        cache.put("user-ada", profile);
        Console.kv("local entries", cache.size());
        Console.note("Values are anything JSON can carry -- a string, a number, and a whole "
                + "map just went through the same put().");

        Console.step("The SOW holds them: a query, not a rebuild");
        SowPrinter.print(client, CacheTopics.ENTRIES);

        Console.step("Process restart: a fresh cache instance, hydrated only from AMPS");
        PersistentCacheMap<Object> restarted = PersistentCacheMap.hydrate(store);
        Console.kv("recovered entries", restarted.size());
        Console.kv("recovered equals original", restarted.equals(cache));
        Console.note("This instance shares no memory with the first one. Everything it "
                + "knows came back from the SOW -- which is exactly what a restarted or "
                + "failed-over process would do.");

        Console.step("Read-through: a local miss asks AMPS");
        restarted.evictLocal("user-ada");
        Console.kv("local entries after evicting 'user-ada'", restarted.size());
        Console.kv("get(\"user-ada\")", restarted.get("user-ada"));
        Console.kv("local entries after the get", restarted.size());

        Console.step("Deletes write through too");
        restarted.remove("motd");
        Console.kv("records left in the SOW", SowPrinter.count(client, CacheTopics.ENTRIES));
    }

    // ------------------------------------------------------------------
    // Part 2: Map<String, Map<String, ?>> flattened onto a composite key.
    // ------------------------------------------------------------------

    private static void nestedCache(AmpsNestedMapStore<Object> nestedStore) {
        Console.step("Map of maps: one record per (outerKey, innerKey) pair on '"
                + CacheTopics.NESTED_ENTRIES + "'");
        NestedCacheMap<Object> positions = NestedCacheMap.hydrate(nestedStore);

        positions.putEntry("portfolio-ada", "AAPL", position(250, "187.50"));
        positions.putEntry("portfolio-ada", "MSFT", position(100, "402.10"));
        positions.putEntry("portfolio-lin", "AAPL", position(75, "187.50"));
        Console.kv("outer keys", positions.keySet());
        Console.kv("portfolio-ada", positions.get("portfolio-ada"));

        Console.step("Updating ONE inner entry publishes one small record");
        positions.putEntry("portfolio-ada", "AAPL", position(300, "188.20"));
        Console.kv("portfolio-ada after the update", positions.get("portfolio-ada"));
        Console.note("No read-modify-write of the outer map, and a second process updating "
                + "portfolio-ada/MSFT at the same moment could not have been overwritten. "
                + "That is what the composite SOW key (/outerKey, /innerKey) buys; the "
                + "alternative -- the whole inner map as one record -- is one put() on the "
                + "flat cache, and the README weighs the two.");

        Console.step("Recovery works the same way: reassembled from the flattened records");
        NestedCacheMap<Object> recovered = NestedCacheMap.hydrate(nestedStore);
        Console.kv("recovered outer keys", recovered.keySet());
        Console.kv("recovered equals original", recovered.equals(positions));

        Console.step("Removing the last inner entry removes the outer key");
        recovered.removeEntry("portfolio-lin", "AAPL");
        Console.kv("outer keys after", recovered.keySet());
    }

    private static Map<String, Object> position(long quantity, String price) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("quantity", quantity);
        position.put("price", price);
        return position;
    }

    private static Client connectOrExplain() throws Exception {
        try {
            return AmpsConnections.connect("cache-demo");
        } catch (Exception e) {
            Console.note("Could not connect to AMPS at " + DemoConfig.uri() + " -- is the "
                    + "cache flow running? Start it with:");
            Console.info("    AMPS_FLOW=cache ./server/scripts/amps.sh start");
            throw e;
        }
    }
}
