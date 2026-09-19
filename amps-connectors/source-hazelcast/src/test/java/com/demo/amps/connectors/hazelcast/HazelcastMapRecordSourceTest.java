package com.demo.amps.connectors.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.demo.amps.connectors.source.SourceRecord;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.cluster.Address;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import java.io.Serializable;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The Hazelcast source reading an {@code IMap}, against a real embedded member.
 *
 * <p>Everything worth asserting here is Hazelcast's behaviour rather than this driver's -- that
 * a put really does raise {@code ADDED} and the next put {@code UPDATED}, that a TTL really
 * does raise {@code EXPIRED} rather than a removal, that {@code clear()} really does report a
 * count and not one key, and that a predicate narrows the live listener the same way it narrows
 * the query. A mock of {@code IMap} would assert only what its author already believed.
 *
 * <p>One member for the whole class, on a cluster name nobody else could be using (this JVM's
 * pid), bound to loopback with every join mechanism off; a map name per test, so no test can
 * inherit another's entries or another's listener. The member's expiry task is turned down from
 * five seconds to one, because the TTL test is about what event a lapsed entry raises and not
 * about how long Hazelcast's housekeeping takes to notice.
 *
 * <p>The sibling {@link HazelcastRecordSourceTest} covers the topic half of the same driver.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastMapRecordSourceTest {

    /** Nobody else's cluster, and not the topic test's either. */
    private static final String CLUSTER = "amps-connectors-map-test-" + ProcessHandle.current().pid();

    /** Away from Hazelcast's default 5701, and from the topic test's member. */
    private static final int BASE_PORT = 5921;

    /** Long enough for a snapshot, a reconnect or an expiry sweep; short enough to fail a hang. */
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    private HazelcastInstance member;

    /** The port the member actually took, which is what the client is pointed at. */
    private int port;

    @BeforeAll
    void startMember() {
        member = Hazelcast.newHazelcastInstance(memberConfig());
        Address address = member.getCluster().getLocalMember().getAddress();
        port = address.getPort();
    }

    @AfterAll
    void stopMember() {
        HazelcastClient.shutdownAll();
        Hazelcast.shutdownAll();
    }

    // ---- fixtures ------------------------------------------------------------------

    /** A single-member cluster that talks to nothing it was not told about. */
    private Config memberConfig() {
        Config config = new Config();
        config.setClusterName(CLUSTER);
        // A unit test is not a telemetry opportunity, and the call slows down startup.
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.logging.type", "slf4j");
        // The test shuts its own member down; a hook would fight the JVM's exit.
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        // Hazelcast sweeps expired entries every five seconds by default and clears a tenth of
        // them at a time. A test that waited that out would be a slow test that still looked
        // like a race when it failed.
        config.setProperty("hazelcast.internal.map.expiration.task.period.seconds", "1");
        config.setProperty("hazelcast.internal.map.expiration.cleanup.percentage", "100");

        NetworkConfig network = config.getNetworkConfig();
        network.setPort(BASE_PORT).setPortAutoIncrement(true);
        network.getInterfaces().setEnabled(true).clear().addInterface("127.0.0.1");

        JoinConfig join = network.getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        return config;
    }

    /** A connector pointed at this test's member, with test-sized timeouts. */
    private ConnectorProperties connector(String name, String map) {
        ConnectorProperties connector = TestConnectors.hazelcastMap(name, map);
        HazelcastSourceProperties hazelcast = connector.getSource().getHazelcast();
        hazelcast.setClusterName(CLUSTER);
        hazelcast.setMembers(List.of("127.0.0.1:" + port));
        hazelcast.setConnectionTimeout(Duration.ofSeconds(2));
        // Production backs off for seconds; a test that waited them out would be a slow test.
        hazelcast.setReconnectDelay(Duration.ofMillis(500));
        return connector;
    }

    private static void awaitRecords(List<SourceRecord> received, int count) {
        Awaitility.await().atMost(PATIENCE).until(() -> received.size() >= count);
    }

    private static void awaitConnected(HazelcastRecordSource source) {
        Awaitility.await().atMost(PATIENCE).until(source::isConnected);
    }

    /** That nothing MORE arrives: the assertion a "this is filtered out" test actually needs. */
    private static void awaitNoMoreThan(List<SourceRecord> received, int count) {
        Awaitility.await().during(Duration.ofMillis(750)).atMost(Duration.ofSeconds(5))
                .until(() -> received.size() == count);
    }

    /** A value the cluster holds as an object rather than as text, and can be queried on. */
    static final class Position implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String account;
        private final String symbol;
        private final int quantity;

        Position(String account, String symbol, int quantity) {
            this.account = account;
            this.symbol = symbol;
            this.quantity = quantity;
        }

        /** Read by Hazelcast's attribute extractor, which is what makes {@code quantity} queryable. */
        public int getQuantity() {
            return quantity;
        }

        public String getAccount() {
            return account;
        }

        public String getSymbol() {
            return symbol;
        }
    }

    // ---- entry events --------------------------------------------------------------------

    @Test
    @DisplayName("a put arrives as an upsert keyed by the entry key, carrying map/event/member")
    void aPutBecomesAKeyedUpsert() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, HazelcastJsonValue> map = member.getMap("added");

        try (HazelcastRecordSource source =
                new HazelcastRecordSource(connector("added-hazelcast", "added"))) {
            source.start(received::add);
            awaitConnected(source);

            map.put("ACC-1|AAPL", new HazelcastJsonValue("{\"quantity\":500}"));
            awaitRecords(received, 1);

            SourceRecord record = received.get(0);
            // The key travels: a map entry has identity, so a PUBLISHER-keyed SOW topic needs
            // nothing from the payload to address the record.
            assertThat(record.key()).isEqualTo("ACC-1|AAPL");
            assertThat(record.data()).isEqualTo("{\"quantity\":500}");
            assertThat(record.action()).isEqualTo(SourceRecord.Action.UPSERT);
            // No ack: an entry event has no position the connector could ask Hazelcast for.
            assertThat(record.ack()).isNull();
            assertThat(record.attributes())
                    .containsEntry("map", "added")
                    .containsEntry("event", "ADDED")
                    .containsEntry("member", "127.0.0.1:" + port);
        }
    }

    @Test
    @DisplayName("a second put on the same key is an UPDATED upsert carrying the new value")
    void aSecondPutIsAnUpdate() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, String> map = member.getMap("updated");

        try (HazelcastRecordSource source =
                new HazelcastRecordSource(connector("updated-hazelcast", "updated"))) {
            source.start(received::add);
            awaitConnected(source);

            map.put("ACC-1", "{\"quantity\":100}");
            map.put("ACC-1", "{\"quantity\":250}");
            awaitRecords(received, 2);

            // Both are upserts as far as AMPS is concerned -- the distinction survives in the
            // attribute, where a transform can still see it.
            assertThat(received).extracting(SourceRecord::data).containsExactly(
                    "{\"quantity\":100}", "{\"quantity\":250}");
            assertThat(received).extracting(record -> record.attributes().get("event"))
                    .containsExactly("ADDED", "UPDATED");
            assertThat(received).extracting(SourceRecord::action)
                    .containsOnly(SourceRecord.Action.UPSERT);
        }
    }

    @Test
    @DisplayName("a remove is a DELETE addressed by key, with no payload at all")
    void aRemoveBecomesAnEmptyDelete() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, String> map = member.getMap("removed");

        try (HazelcastRecordSource source =
                new HazelcastRecordSource(connector("removed-hazelcast", "removed"))) {
            source.start(received::add);
            awaitConnected(source);

            map.put("ACC-1", "{\"quantity\":100}");
            map.remove("ACC-1");
            awaitRecords(received, 2);

            SourceRecord delete = received.get(1);
            assertThat(delete.action()).isEqualTo(SourceRecord.Action.DELETE);
            assertThat(delete.key()).isEqualTo("ACC-1");
            // Empty rather than the old value: the pipeline skips the filter for an empty
            // delete and addresses the record by its key, which is all a removal can be sure of.
            assertThat(delete.data()).isEmpty();
            assertThat(delete.attributes()).containsEntry("event", "REMOVED");
        }
    }

    @Test
    @DisplayName("an entry that lapses raises EXPIRED, and that is a DELETE too")
    void anExpiredEntryBecomesADelete() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, String> map = member.getMap("expiring");

        try (HazelcastRecordSource source =
                new HazelcastRecordSource(connector("expiring-hazelcast", "expiring"))) {
            source.start(received::add);
            awaitConnected(source);

            map.put("ACC-1", "{\"quantity\":100}", 1, TimeUnit.SECONDS);
            awaitRecords(received, 2);

            // Removed, evicted and expired are three reasons and one consequence: the map no
            // longer holds the key, so the SOW should not either.
            SourceRecord expired = received.get(1);
            assertThat(expired.action()).isEqualTo(SourceRecord.Action.DELETE);
            assertThat(expired.key()).isEqualTo("ACC-1");
            assertThat(expired.data()).isEmpty();
            assertThat(expired.attributes()).containsEntry("event", "EXPIRED");
        }
    }

    @Test
    @DisplayName("clear() names no keys, so it is counted and warned about rather than guessed at")
    void aClearedMapIsCountedRatherThanDeleted() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, String> map = member.getMap("cleared");

        try (HazelcastRecordSource source =
                new HazelcastRecordSource(connector("cleared-hazelcast", "cleared"))) {
            source.start(received::add);
            awaitConnected(source);

            map.put("ACC-1", "{\"quantity\":1}");
            map.put("ACC-2", "{\"quantity\":2}");
            awaitRecords(received, 2);

            map.clear();

            // Hazelcast reports how many entries went and not one of their keys, so deleting
            // the SOW records would mean deleting from the connector's own memory of the map --
            // a guess, applied destructively. The counter says the two have diverged.
            Awaitility.await().atMost(PATIENCE).until(() -> source.mapWideEvents() == 1);
            awaitNoMoreThan(received, 2);
            assertThat(received).extracting(SourceRecord::action)
                    .containsOnly(SourceRecord.Action.UPSERT);
        }
    }

    // ---- the snapshot ---------------------------------------------------------------------

    @Test
    @DisplayName("snapshot: true replays what the map already held, as SNAPSHOT upserts")
    void theSnapshotReplaysTheMapOnConnect() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, String> map = member.getMap("snapshot-on");
        map.put("ACC-1", "{\"quantity\":1}");
        map.put("ACC-2", "{\"quantity\":2}");

        try (HazelcastRecordSource source =
                new HazelcastRecordSource(connector("snapshot-on-hazelcast", "snapshot-on"))) {
            source.start(received::add);
            awaitConnected(source);
            awaitRecords(received, 2);

            // Entry events are at-most-once: nothing replays one the connector was not there
            // for, so reading the map on connect is what makes a restart converge at all.
            assertThat(received).extracting(SourceRecord::key, SourceRecord::data)
                    .containsExactlyInAnyOrder(
                            tuple("ACC-1", "{\"quantity\":1}"),
                            tuple("ACC-2", "{\"quantity\":2}"));
            assertThat(received).extracting(record -> record.attributes().get("event"))
                    .containsOnly("SNAPSHOT");
            assertThat(received).extracting(record -> record.attributes().get("member"))
                    .containsOnly("127.0.0.1:" + port);

            // The listener was registered first, so the live feed carries on from there.
            map.put("ACC-3", "{\"quantity\":3}");
            awaitRecords(received, 3);
            assertThat(received.get(2).attributes()).containsEntry("event", "ADDED");
        }
    }

    @Test
    @DisplayName("snapshot: false starts from the live feed, and the map's contents stay unread")
    void snapshotFalseReadsNothing() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, String> map = member.getMap("snapshot-off");
        map.put("ACC-1", "{\"quantity\":1}");
        map.put("ACC-2", "{\"quantity\":2}");

        ConnectorProperties connector = connector("snapshot-off-hazelcast", "snapshot-off");
        connector.getSource().getHazelcast().setSnapshot(false);
        try (HazelcastRecordSource source = new HazelcastRecordSource(connector)) {
            source.start(received::add);
            awaitConnected(source);

            map.put("ACC-3", "{\"quantity\":3}");
            awaitRecords(received, 1);
            awaitNoMoreThan(received, 1);

            // A connector configured this way has given up convergence on purpose: the two
            // entries that predate it reach AMPS when they are next written, and not before.
            assertThat(received).extracting(SourceRecord::key).containsExactly("ACC-3");
            assertThat(received.get(0).attributes()).containsEntry("event", "ADDED");
        }
    }

    @Test
    @DisplayName("a predicate narrows the snapshot and the live listener by the same expression")
    void aPredicateNarrowsBothTheSnapshotAndTheFeed() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, Position> map = member.getMap("predicate");
        map.put("ACC-1", new Position("ACC-1", "AAPL", 500));
        map.put("ACC-2", new Position("ACC-2", "MSFT", 10));
        map.put("ACC-3", new Position("ACC-3", "TSLA", 900));
        map.put("ACC-4", new Position("ACC-4", "AMZN", 5));

        ConnectorProperties connector = connector("predicate-hazelcast", "predicate");
        connector.getSource().getHazelcast().setPredicate("quantity > 100");
        try (HazelcastRecordSource source = new HazelcastRecordSource(connector)) {
            source.start(received::add);
            awaitConnected(source);
            awaitRecords(received, 2);
            awaitNoMoreThan(received, 2);

            // The cluster evaluated it, so the halves that do not match never crossed the wire.
            assertThat(received).extracting(SourceRecord::key)
                    .containsExactlyInAnyOrder("ACC-1", "ACC-3");

            map.put("ACC-5", new Position("ACC-5", "NVDA", 750));
            awaitRecords(received, 3);
            // ...and the same expression narrows the live feed, so the connector's idea of what
            // it is bridging does not change the moment the snapshot finishes.
            map.put("ACC-6", new Position("ACC-6", "META", 1));
            awaitNoMoreThan(received, 3);
            assertThat(received).extracting(SourceRecord::key)
                    .containsExactlyInAnyOrder("ACC-1", "ACC-3", "ACC-5");
        }
    }

    // ---- values ---------------------------------------------------------------------------

    @Test
    @DisplayName("text passes through, and everything else reaches the pipeline as JSON")
    void valuesReachThePipelineAsText() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, Object> map = member.getMap("values");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("account", "ACC-4");
        nested.put("quantity", 40);

        try (HazelcastRecordSource source =
                new HazelcastRecordSource(connector("values-hazelcast", "values"))) {
            source.start(received::add);
            awaitConnected(source);

            // A String is whatever the feed writes -- JSON, FIX, a line of text -- and is not
            // the connector's to reinterpret.
            map.put("string", "35=D|11=ORD-1");
            // A value Hazelcast already knows is JSON: its text, not its toString().
            map.put("json", new HazelcastJsonValue("{\"quantity\":20}"));
            // A Map and a POJO are objects the cluster stores as objects; Gson renders them,
            // which is why a map connector is configured format: JSON.
            map.put("map", new LinkedHashMap<>(nested));
            map.put("pojo", new Position("ACC-5", "AAPL", 50));
            awaitRecords(received, 4);

            assertThat(received).extracting(SourceRecord::key, SourceRecord::data)
                    .containsExactlyInAnyOrder(
                            tuple("string", "35=D|11=ORD-1"),
                            tuple("json", "{\"quantity\":20}"),
                            tuple("map", "{\"account\":\"ACC-4\",\"quantity\":40}"),
                            tuple("pojo",
                                    "{\"account\":\"ACC-5\",\"symbol\":\"AAPL\",\"quantity\":50}"));
        }
    }

    // ---- lifecycle --------------------------------------------------------------------------

    @Test
    @DisplayName("close() removes the listener and shuts the client down")
    void closeShutsTheClientDown() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        IMap<String, String> map = member.getMap("closing");
        HazelcastRecordSource source =
                new HazelcastRecordSource(connector("closing-map-hazelcast", "closing"));
        source.start(received::add);
        awaitConnected(source);
        assertThat(HazelcastClient.getAllHazelcastClients()).isNotEmpty();

        source.close();

        assertThat(source.isConnected()).isFalse();
        // A client left running would keep a connection, an event thread pool and an entry
        // listener alive after the connector had stopped.
        assertThat(HazelcastClient.getAllHazelcastClients()).isEmpty();
        assertThat(Thread.getAllStackTraces().keySet())
                .as("no source thread is left behind")
                .noneMatch(thread -> thread.getName().equals("closing-map-hazelcast-hazelcast"));
        // Idempotent, the way ConnectorManager calls it.
        source.close();

        map.put("ACC-1", "{\"quantity\":1}");
        awaitNoMoreThan(received, 0);
        // The member noticed too, rather than holding a half-open client connection.
        Awaitility.await().atMost(PATIENCE)
                .until(() -> member.getClientService().getConnectedClients().isEmpty());
    }
}
