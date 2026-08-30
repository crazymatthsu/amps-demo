package com.demo.amps.hazelcast;

import com.crankuptheamps.client.Client;
import com.demo.amps.cache.AmpsFilters;
import com.demo.amps.cache.SowOps;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.YamlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The whole story with a real Hazelcast member: IMap puts land in AMPS tier
 * topics, a NEW member hydrates from nothing but AMPS (the restart/failover
 * case), a local miss reads through, and one map's clear() leaves its
 * tier-mates alone.
 *
 * <pre>
 *   AMPS_FLOW=hazelcast ./server/scripts/amps.sh start   # or scripts/amps-hazelcast.sh start
 *   ./gradlew :hazelcast-persistent-store:run
 * </pre>
 */
public final class HazelcastCacheDemo {

    private HazelcastCacheDemo() {
    }

    public static void main(String[] args) throws Exception {
        // Keep member logs out of the demo narrative; errors still surface.
        System.setProperty("org.slf4j.simpleLogger.log.com.hazelcast", "warn");

        Console.title("Hazelcast open source persisting its IMaps in AMPS");

        try (Client inspect = connectOrExplain()) {
            SowOps persistent = new SowOps(inspect, HazelcastTiers.PERSISTENT,
                    DemoConfig.timeoutMillis());
            SowOps volatileTier = new SowOps(inspect, HazelcastTiers.VOLATILE,
                    DemoConfig.timeoutMillis());

            Console.step("Starting from empty tier topics, so re-runs tell one story");
            persistent.deleteByFilter(AmpsFilters.MATCH_EVERYTHING);
            volatileTier.deleteByFilter(AmpsFilters.MATCH_EVERYTHING);

            Console.step("Member 1 up, three maps on two tiers "
                    + "(src/main/resources/hazelcast-example.yaml)");
            HazelcastInstance member1 = Hazelcast.newHazelcastInstance(memberConfig());
            try {
                IMap<String, Object> orders = member1.getMap("orders");
                IMap<String, Object> audit = member1.getMap("audit");
                IMap<String, Object> sessions = member1.getMap("sessions");

                orders.put("ord-1", order("AAPL", 250, "187.50"));
                orders.put("ord-2", order("MSFT", 100, "402.10"));
                audit.put("evt-1", "ord-1 accepted");
                sessions.put("sess-9", Map.of("user", "ada", "ttl", "short"));
                sessions.flush(); // write-behind: force the queued store now
                Console.kv("orders / audit / sessions",
                        orders.size() + " / " + audit.size() + " / " + sessions.size());

                Console.step("The tier topics hold them -- note /map scoping the shared topic");
                print(persistent);
                print(volatileTier);
            } finally {
                Console.step("Member 1 shuts down (graceful: write-behind queues drain)");
                member1.shutdown();
            }

            Console.step("Member 2 starts with empty memory and EAGER map stores");
            HazelcastInstance member2 = Hazelcast.newHazelcastInstance(memberConfig());
            try {
                IMap<String, Object> orders = member2.getMap("orders");
                IMap<String, Object> audit = member2.getMap("audit");
                IMap<String, Object> sessions = member2.getMap("sessions");
                Console.kv("recovered orders", orders.entrySet());
                Console.kv("recovered audit", audit.entrySet());
                Console.kv("recovered sessions", sessions.entrySet());
                Console.note("Nothing was copied between members -- member 1 was gone before "
                        + "member 2 existed. Hazelcast called loadAllKeys()/loadAll() on the "
                        + "MapStore and AMPS answered. That is the restart/failover story.");

                Console.step("Read-through: evict locally, get() asks AMPS");
                orders.evict("ord-1");
                Console.kv("orders.get(\"ord-1\") after evict", orders.get("ord-1"));

                Console.step("clear() is scoped by /map, not by topic");
                audit.clear();
                Console.kv("audit records left", count(persistent, "audit"));
                Console.kv("orders records left (same topic!)", count(persistent, "orders"));
            } finally {
                member2.shutdown();
            }

            Console.step("Done");
            Console.note("hz.volatile is transient with a 60s <Expiration>: sessions entries "
                    + "evaporate server-side, matching the map's TTL -- Hazelcast never "
                    + "deletes expired entries from a MapStore, so the tier does it. "
                    + "hz.persistent is journalled: restart AMPS "
                    + "(scripts/amps-hazelcast.sh restart) and rerun to watch orders survive "
                    + "the broker too.");
        } finally {
            Hazelcast.shutdownAll();
        }
    }

    private static Config memberConfig() {
        InputStream yaml = HazelcastCacheDemo.class.getResourceAsStream("/hazelcast-example.yaml");
        Config config = new YamlConfigBuilder(yaml).build();
        // Unique per process so a demo cannot join some other cluster by accident.
        config.setClusterName(config.getClusterName() + "-" + ProcessHandle.current().pid());
        return config;
    }

    private static Map<String, Object> order(String symbol, long quantity, String price) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("symbol", symbol);
        order.put("quantity", quantity);
        order.put("price", price);
        return order;
    }

    private static void print(SowOps tier) {
        Console.info("      sow query on '%s':", tier.topic());
        tier.query(null, record -> Console.info("        %s", record));
    }

    private static int count(SowOps tier, String map) {
        int[] count = {0};
        tier.query(AmpsFilters.equalTo(AmpsTierStore.MAP_FIELD, map), record -> count[0]++);
        return count[0];
    }

    private static Client connectOrExplain() throws Exception {
        try {
            return AmpsConnections.connect("hz-demo-inspect");
        } catch (Exception e) {
            Console.note("Could not connect to AMPS at " + DemoConfig.uri() + " -- is the "
                    + "hazelcast flow running? Start it with:");
            Console.info("    AMPS_FLOW=hazelcast ./server/scripts/amps.sh start");
            throw e;
        }
    }
}
