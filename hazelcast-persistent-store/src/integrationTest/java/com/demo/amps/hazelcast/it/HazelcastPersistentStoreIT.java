package com.demo.amps.hazelcast.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.hazelcast.AmpsHazelcastMapStore;
import com.demo.amps.hazelcast.AmpsMapStoreFactory;
import com.demo.amps.hazelcast.HazelcastTiers;
import com.demo.amps.hazelcast.TierStore;
import com.demo.amps.hazelcast.ValueCodec;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapStoreConfig;
import com.hazelcast.config.MapStoreConfig.InitialLoadMode;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.MapLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The TODO's demo-testing list, with nothing faked on either side: a real
 * AMPS instance on the {@code hazelcast} flow and real embedded Hazelcast
 * members. IMap writes are read back by querying AMPS directly; members are
 * shut down and replaced to prove rehydration; two members share the load;
 * one map's {@code clear()} must not touch a tier-mate; and -- last, because
 * it severs every connection -- the AMPS container restarts and the durable
 * tier comes back while the volatile tier correctly does not.
 *
 * <p>Store traffic is recorded through a decorating factory, which is how the
 * suite asserts "stores happen on partition owners only": in a
 * migration-quiet cluster, each put must store its entry exactly once, with
 * no duplicates from the second member. (During an in-flight migration a
 * retried put may legitimately store twice -- at-least-once, not
 * exactly-once -- so the two-member test waits for the cluster to be safe
 * before counting.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class HazelcastPersistentStoreIT {

    private static final long TIMEOUT_MS = 10_000;
    /** Every entry stored through any member, as "map/key", in arrival order. */
    private static final List<String> STORE_LOG =
            java.util.Collections.synchronizedList(new ArrayList<>());

    private AmpsTestServer server;
    private Client inspect;
    private String clusterName;
    private HazelcastInstance member;

    @BeforeAll
    void startServer() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.HAZELCAST);
        assumeTrue(unavailable.isEmpty(),
                () -> "integration test prerequisites: " + unavailable.orElse(""));

        System.setProperty("hazelcast.phone.home.enabled", "false");
        System.setProperty("hazelcast.logging.type", "slf4j");

        server = AmpsTestServer.start(AmpsFlow.HAZELCAST);
        inspect = connect("hz-it-inspect");
        clusterName = "hz-it-" + ProcessHandle.current().pid();
        member = Hazelcast.newHazelcastInstance(memberConfig());
    }

    @AfterAll
    void tearDown() {
        Hazelcast.shutdownAll();
        if (inspect != null) {
            inspect.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("IMap writes land in the tier topics as {map, key, value} records")
    void writesLandInTierTopics() throws Exception {
        IMap<String, Object> orders = member.getMap("orders");
        IMap<String, Object> audit = member.getMap("audit");
        IMap<String, Object> sessions = member.getMap("sessions");

        orders.put("ord-1", Map.of("symbol", "AAPL", "qty", 250L));
        orders.put("ord-2", Map.of("symbol", "MSFT", "qty", 100L));
        orders.put("ord-3", Map.of("symbol", "TSLA", "qty", 75L));
        audit.put("evt-1", "ord-1 accepted");
        sessions.put("sess-1", Map.of("user", "ada"));
        sessions.put("sess-2", Map.of("user", "lin"));
        sessions.flush(); // write-behind: force the queued stores now

        Map<String, JsonObject> persistent = recordsByMapAndKey(HazelcastTiers.PERSISTENT);
        assertEquals(4, persistent.size(), "orders x3 + audit x1 share the persistent tier");
        assertEquals(250L, persistent.get("orders/ord-1").get("value")
                .getAsJsonObject().get("qty").getAsLong());
        assertEquals("ord-1 accepted",
                persistent.get("audit/evt-1").get("value").getAsString());
        assertEquals(2, recordsByMapAndKey(HazelcastTiers.VOLATILE).size());
        assertEquals(6, STORE_LOG.size(),
                "every put stored exactly once -- no duplicate writers: " + STORE_LOG);
    }

    @Test
    @Order(2)
    @DisplayName("a replacement member hydrates every map from AMPS alone")
    void memberRestartRehydrates() {
        member.shutdown();
        member = Hazelcast.newHazelcastInstance(memberConfig());

        IMap<String, Object> orders = member.getMap("orders");
        assertEquals(3, orders.size(), "EAGER initial load must bring all orders back");
        assertEquals(Map.of("symbol", "AAPL", "qty", 250L), orders.get("ord-1"));
        assertEquals("ord-1 accepted", member.getMap("audit").get("evt-1"));
        assertEquals(2, member.getMap("sessions").size());
    }

    @Test
    @Order(3)
    @DisplayName("a second member joins: loads are partition-scoped, stores stay single-writer")
    void twoMembersShareTheWork() throws Exception {
        HazelcastInstance second = Hazelcast.newHazelcastInstance(memberConfig());
        try {
            assertEquals(2, second.getCluster().getMembers().size());
            // Joining repartitions the cluster, and a put retried across an
            // in-flight migration may store twice. The exactly-once claim is
            // about a QUIET cluster, so get quiet first.
            awaitMigrationsSettled(second);
            int mark = STORE_LOG.size();

            IMap<String, Object> orders = member.getMap("orders");
            for (int i = 1; i <= 20; i++) {
                orders.put("bulk-" + i, Map.of("n", (long) i));
            }

            List<String> storedNow = List.copyOf(STORE_LOG).subList(mark, STORE_LOG.size());
            assertEquals(20, storedNow.size(),
                    "20 puts across 2 members must store exactly 20 entries: " + storedNow);
            assertEquals(20, Set.copyOf(storedNow).size(),
                    "no entry may be stored twice: " + storedNow);
            IMap<String, Object> viaSecond = second.getMap("orders");
            assertEquals(23, viaSecond.size());
            assertEquals(Map.of("n", 7L), viaSecond.get("bulk-7"));
        } finally {
            second.shutdown();
        }
    }

    private static void awaitMigrationsSettled(HazelcastInstance instance)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (!instance.getPartitionService().isClusterSafe()) {
            assertTrue(System.nanoTime() < deadline, "cluster never became migration-safe");
            Thread.sleep(100);
        }
    }

    @Test
    @Order(4)
    @DisplayName("clear() of one map leaves its tier-mates' records untouched")
    void clearIsScopedToItsMap() throws Exception {
        member.getMap("audit").clear();

        assertEquals(0, records(HazelcastTiers.PERSISTENT, "/map = 'audit'").size());
        assertEquals(23, records(HazelcastTiers.PERSISTENT, "/map = 'orders'").size(),
                "orders share the topic and must survive audit's clear()");
    }

    @Test
    @Order(5)
    @DisplayName("after an AMPS restart the durable tier hydrates; the volatile tier is gone")
    void serverRestartSortsTheTiers() throws Exception {
        member.shutdown();
        server.restart();
        inspect.close();
        inspect = connect("hz-it-inspect-2");
        member = Hazelcast.newHazelcastInstance(memberConfig());

        IMap<String, Object> orders = member.getMap("orders");
        assertEquals(23, orders.size(), "hz.persistent survived the broker restart");
        assertNotNull(orders.get("bulk-7"));
        assertEquals(0, member.getMap("sessions").size(),
                "hz.volatile is transient: nothing to recover, by design");
        assertEquals(0, member.getMap("audit").size(), "cleared stays cleared");
    }

    // ------------------------------------------------------------------
    // Member configuration: three maps, two tiers, counting factory.
    // ------------------------------------------------------------------

    private Config memberConfig() {
        Config config = new Config();
        config.setClusterName(clusterName);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig()
                .setEnabled(true).addMember("127.0.0.1");

        config.addMapConfig(mapConfig("orders", HazelcastTiers.PERSISTENT, 0));
        config.addMapConfig(mapConfig("audit", HazelcastTiers.PERSISTENT, 0));
        config.addMapConfig(mapConfig("sessions", HazelcastTiers.VOLATILE, 1));
        return config;
    }

    private MapConfig mapConfig(String name, String tier, int writeDelaySeconds) {
        Properties properties = new Properties();
        properties.setProperty("amps.topic", tier);
        properties.setProperty("amps.uri", server.uri());
        properties.setProperty("amps.timeoutMs", String.valueOf(TIMEOUT_MS));

        MapStoreConfig storeConfig = new MapStoreConfig()
                .setEnabled(true)
                .setInitialLoadMode(InitialLoadMode.EAGER)
                .setFactoryImplementation(new CountingFactory())
                .setWriteDelaySeconds(writeDelaySeconds)
                .setProperties(properties);
        return new MapConfig(name).setMapStoreConfig(storeConfig);
    }

    /** Counts entries stored through any member, via the adapter's decorate seam. */
    public static class CountingFactory extends AmpsMapStoreFactory {
        @Override
        public MapLoader<String, Object> newMapStore(String mapName, Properties properties) {
            super.newMapStore(mapName, properties); // keep the fail-fast validation
            return new AmpsHazelcastMapStore<>(codecFor(mapName, properties)) {
                @Override
                protected TierStore<Object> decorate(TierStore<Object> tier) {
                    return new CountingTierStore(tier);
                }
            };
        }
    }

    private record CountingTierStore(TierStore<Object> delegate) implements TierStore<Object> {
        @Override
        public Object load(String map, String key) {
            return delegate.load(map, key);
        }

        @Override
        public Set<String> loadKeys(String map) {
            return delegate.loadKeys(map);
        }

        @Override
        public Map<String, Object> loadAll(String map, Collection<String> keys) {
            return delegate.loadAll(map, keys);
        }

        @Override
        public void store(String map, String key, Object value) {
            STORE_LOG.add(map + "/" + key);
            delegate.store(map, key, value);
        }

        @Override
        public void storeAll(String map, Map<String, Object> entries) {
            entries.keySet().forEach(key -> STORE_LOG.add(map + "/" + key));
            delegate.storeAll(map, entries);
        }

        @Override
        public void delete(String map, String key) {
            delegate.delete(map, key);
        }

        @Override
        public void deleteAll(String map, Collection<String> keys) {
            delegate.deleteAll(map, keys);
        }
    }

    // ------------------------------------------------------------------
    // Reading AMPS directly: the other half of every assertion.
    // ------------------------------------------------------------------

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

    private List<JsonObject> records(String topic, String filter) throws Exception {
        List<JsonObject> found = new ArrayList<>();
        Command command = new Command("sow").setTopic(topic).setTimeout(TIMEOUT_MS);
        if (filter != null) {
            command.setFilter(filter);
        }
        try (MessageStream stream = inspect.execute(command)) {
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

    private Map<String, JsonObject> recordsByMapAndKey(String topic) throws Exception {
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonObject record : records(topic, null)) {
            assertTrue(record.has("map") && record.has("key") && record.has("value"),
                    "tier records must carry map/key/value: " + record);
            byId.put(record.get("map").getAsString() + "/"
                    + record.get("key").getAsString(), record);
        }
        return byId;
    }
}
