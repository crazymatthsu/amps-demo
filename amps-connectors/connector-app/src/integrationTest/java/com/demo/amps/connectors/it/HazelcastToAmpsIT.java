package com.demo.amps.connectors.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * A Hazelcast {@code IMap} through the real connector application into a real AMPS instance:
 * the transport that is already shaped like a SOW, and the one where the key never has to be
 * dug out of the payload.
 *
 * <p>Every other source in this suite has to manufacture identity. A socket frame has none, a
 * Kafka message has a key only if the producer set one, a JDBC row has one only because the
 * configuration names its columns. A map entry has one by construction, which is what
 * {@code key.mode: PUBLISHER} with an <em>empty</em> {@code key.fields} means here: the SowKey
 * is the map key, so the assertions are on {@link com.crankuptheamps.client.Message#getSowKey()}
 * rather than on the record count alone -- a keyless publish onto a topic declared without a
 * {@code <Key>} is accepted by AMPS and collapses the whole feed onto one sentinel-keyed
 * record, which looks perfectly healthy from the connector's side.
 *
 * <p>Five things the SOW can contradict, in order, because each builds on the state the last
 * one left -- which is also how a connector experiences a cache:
 *
 * <ul>
 *   <li><b>the snapshot</b> -- entries written <em>before</em> the application existed. Hazelcast
 *       entry events are at-most-once and are never replayed, so the only thing that can put
 *       them in the SOW is the map read the source performs on connect</li>
 *   <li><b>added, then updated</b> -- a live put, and a second put on the same key that must
 *       <em>replace</em> the record rather than add one</li>
 *   <li><b>removed</b> -- the one that is not a message: an entry that stops existing becomes a
 *       {@code sow_delete} addressed by the key the removal still has, with no payload to
 *       rebuild one from</li>
 *   <li><b>values that are not text</b> -- a {@code Map} the cluster stores as an object reaches
 *       AMPS as JSON with its numbers still numbers, which is why a map connector is configured
 *       {@code format: JSON}</li>
 *   <li><b>a predicate</b> -- a second connector on a second map, where the cluster narrows the
 *       feed and the entries that do not match never reach the topic at all</li>
 * </ul>
 *
 * <p>The member is <b>embedded in this JVM</b> rather than in a container: the thing under test
 * is the connector's client, its snapshot and its listener, and a real single-member cluster on
 * loopback exercises all three with nothing to pull or start. It is configured the way
 * {@code HazelcastMapRecordSourceTest} configures its own -- a cluster name nobody else could be
 * using, loopback only, every join mechanism off -- so two suites running in one Gradle
 * invocation cannot find each other.
 *
 * <p>Every assertion polls the SOW with Awaitility. {@code Client.flush()} proves nothing here:
 * it is the connector's client that publishes, not the test's, and a batch is released by size
 * or by an idle timer.
 *
 * <p>Skips rather than fails when {@code AMPS_IMAGE} is unset, like every other suite here, so
 * a green build is not by itself proof this ran.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HazelcastToAmpsIT {

    private static final String POSITIONS = "sow/connectors/positions";
    private static final String EVENTS = "sow/connectors/events";

    /** Nobody else's cluster, and not the driver suite's either. */
    private static final String CLUSTER = "amps-connectors-it-" + ProcessHandle.current().pid();

    /** Away from Hazelcast's default 5701 and from the driver tests' members (5901/5921). */
    private static final int BASE_PORT = 5951;

    /** Generous: a batch waits out its idle timer, and the first publish also logs on. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** That nothing MORE arrives -- the assertion a "this is filtered out" test actually needs. */
    private static final Duration QUIET = Duration.ofSeconds(3);

    private AmpsTestServer server;
    private ConnectorAppRunner app;
    private Client json;

    private HazelcastInstance member;
    private IMap<String, Object> positions;
    private IMap<String, Object> events;

    @BeforeAll
    void startEverything() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.AMPS_CONNECTORS);
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        member = Hazelcast.newHazelcastInstance(memberConfig());
        int port = member.getCluster().getLocalMember().getAddress().getPort();
        positions = member.getMap("positions");
        events = member.getMap("events");

        // Written BEFORE the application exists, so nothing but the snapshot can deliver them:
        // Hazelcast raised no event anyone was listening for, and will never raise them again.
        positions.put("ACC-1|AAPL", position("ACC-1", "AAPL", 100, "101.5"));
        positions.put("ACC-2|MSFT", position("ACC-2", "MSFT", 250, "330.25"));

        server = AmpsTestServer.start(AmpsFlow.AMPS_CONNECTORS);
        app = ConnectorAppRunner.against(server.port())
                // ---- the cache mirrored onto an unkeyed SOW topic ------------------
                .connector("positions-hazelcast")
                // A map value reaches the pipeline as text: JSON if it already was, JSON if
                // Gson had to render it. This is the setting that matches a cache of objects.
                .set("format", "JSON")
                .set("source.hazelcast.cluster-name", CLUSTER)
                .set("source.hazelcast.members[0]", "127.0.0.1:" + port)
                .set("source.hazelcast.map", "positions")
                // Not an optimisation: the repair for an at-most-once event nobody received,
                // and the only reason the two pre-existing entries are ever published.
                .set("source.hazelcast.snapshot", "true")
                .set("source.hazelcast.connection-timeout", "5s")
                .set("source.hazelcast.reconnect-delay", "1s")
                .set("amps.topic", POSITIONS)
                .set("amps.message-type", "json")
                // No key.fields: the SOURCE supplies the key, and the map key is the only
                // identity a removed entry still has.
                .set("amps.key.mode", "PUBLISHER")
                .set("amps.on-delete", "SOW_DELETE")
                .set("amps.batch.max-messages", 100)
                .set("amps.batch.flush-interval", "100ms")
                // ---- a predicate, and a topic AMPS keys itself ---------------------
                .connector("events-hazelcast")
                .set("format", "JSON")
                .set("source.hazelcast.cluster-name", CLUSTER)
                .set("source.hazelcast.members[0]", "127.0.0.1:" + port)
                .set("source.hazelcast.map", "events")
                .set("source.hazelcast.snapshot", "true")
                // Evaluated by the CLUSTER, on the listener and on the snapshot alike, so an
                // entry that does not match never crosses the wire in either direction.
                .set("source.hazelcast.predicate", "qty > 0")
                .set("source.hazelcast.connection-timeout", "5s")
                .set("source.hazelcast.reconnect-delay", "1s")
                .set("amps.topic", EVENTS)
                .set("amps.message-type", "json")
                // sow/connectors/events is declared <Key>/id</Key>: AMPS keys it from the
                // payload, and key.fields is the check that /id survived to be keyed on.
                .set("amps.key.mode", "SERVER")
                .set("amps.key.fields[0]", "id")
                .set("amps.on-delete", "SOW_DELETE")
                .set("amps.batch.max-messages", 100)
                .set("amps.batch.flush-interval", "100ms")
                .start();

        json = AmpsSow.connect(server.port(), "json", "hazelcast-it-json");
    }

    @AfterAll
    void stopEverything() {
        if (app != null) {
            app.close();
        }
        if (json != null) {
            json.close();
        }
        if (server != null) {
            server.close();
        }
        if (member != null) {
            member.shutdown();
        }
    }

    // ---- the snapshot -----------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("entries written before the connector existed reach the SOW under the map key")
    void theSnapshotPublishesWhatTheMapAlreadyHeld() {
        awaitPositions("both pre-existing entries, keyed by the map key", records -> {
            assertThat(records).hasSize(2);
            // The SowKey IS the map key. Asserted rather than assumed, because a publish that
            // forgot it is accepted by AMPS and files every record under one sentinel key.
            assertThat(records).extracting(AmpsSow.Record::sowKey)
                    .containsExactlyInAnyOrder("ACC-1|AAPL", "ACC-2|MSFT");
            assertThat(dataOf(records, "ACC-1|AAPL"))
                    .contains("\"qty\":100")
                    .contains("\"avgCost\":101.5");
        });
    }

    // ---- the live feed ----------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("a put after the connector started is added, and a re-put replaces it")
    void aPutIsAddedAndASecondPutReplacesIt() {
        positions.put("ACC-3|TSLA", position("ACC-3", "TSLA", 40, "242.10"));

        awaitPositions("the new key alongside the snapshot's two", records -> {
            assertThat(records).hasSize(3);
            assertThat(records).extracting(AmpsSow.Record::sowKey)
                    .containsExactlyInAnyOrder("ACC-1|AAPL", "ACC-2|MSFT", "ACC-3|TSLA");
            assertThat(dataOf(records, "ACC-3|TSLA")).contains("\"qty\":40");
        });

        positions.put("ACC-3|TSLA", position("ACC-3", "TSLA", 95, "243.75"));

        // ADDED and UPDATED are two Hazelcast events and one AMPS operation: an upsert under a
        // key the SOW already holds replaces the record rather than adding a second one.
        awaitPositions("the same three keys, the new value on the one that moved", records -> {
            assertThat(records).hasSize(3);
            assertThat(dataOf(records, "ACC-3|TSLA"))
                    .contains("\"qty\":95")
                    .contains("\"avgCost\":243.75");
        });
    }

    @Test
    @Order(3)
    @DisplayName("an entry removed from the map is deleted from the SOW by that same key")
    void aRemovedEntryLeavesTheSow() {
        positions.remove("ACC-2|MSFT");

        // The removal carries no payload at all -- there is nothing left to build a key from,
        // which is exactly why this topic is PUBLISHER-keyed.
        awaitPositions("ACC-2 gone, the other two untouched", records -> {
            assertThat(records).hasSize(2);
            assertThat(records).extracting(AmpsSow.Record::sowKey)
                    .containsExactlyInAnyOrder("ACC-1|AAPL", "ACC-3|TSLA");
        });
    }

    // ---- values -----------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("a Map value arrives as JSON with its numbers still numbers")
    void anObjectValueArrivesAsJson() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("account", "ACC-4");
        value.put("symbol", "NVDA");
        value.put("qty", 175);
        value.put("avgCost", 890.5);
        positions.put("ACC-4|NVDA", value);

        awaitPositions("the object rendered as JSON, unquoted numbers and all", records -> {
            assertThat(records).hasSize(3);
            // Rendered by the source, published byte for byte by passthrough: 175 is a number
            // in the SOW, so an AMPS filter can compare it. A stringified value could not.
            assertThat(dataOf(records, "ACC-4|NVDA"))
                    .isEqualTo("{\"account\":\"ACC-4\",\"symbol\":\"NVDA\","
                            + "\"qty\":175,\"avgCost\":890.5}");
        });

        // A number in the SOW is a number to AMPS as well, which is the point of not
        // stringifying it on the way in: 100 and 175 match, the 95 beside them does not.
        Awaitility.await("the server can compare it numerically").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(json, POSITIONS, "/qty >= 100"))
                        .extracting(AmpsSow.Record::sowKey)
                        .containsExactlyInAnyOrder("ACC-1|AAPL", "ACC-4|NVDA"));
    }

    // ---- the predicate ----------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("a predicate keeps the entries that do not match off the topic entirely")
    void thePredicateNarrowsWhatIsPublished() throws Exception {
        events.put("EV-1", event("EV-1", 10));
        events.put("EV-2", event("EV-2", 0));
        events.put("EV-3", event("EV-3", 25));
        events.put("EV-4", event("EV-4", -5));

        Awaitility.await("only the positive quantities").atMost(PATIENCE)
                .untilAsserted(() -> {
                    List<AmpsSow.Record> records = AmpsSow.records(json, EVENTS, "1=1");
                    assertThat(records).hasSize(2);
                    assertThat(records).extracting(AmpsSow.Record::data)
                            .allSatisfy(data -> assertThat(data).contains("\"qty\":"));
                });

        // ...and it stays two: the cluster evaluated the predicate, so EV-2 and EV-4 were
        // never delivered to the listener and were never in the snapshot either.
        Awaitility.await("and nothing else follows").during(QUIET).atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(json, EVENTS, "1=1"))
                        .hasSize(2));

        // AMPS keyed these itself, from /id -- the connector sent no SowKey at all.
        assertThat(AmpsSow.records(json, EVENTS, "1=1"))
                .extracting(AmpsSow.Record::data)
                .anySatisfy(data -> assertThat(data).contains("\"id\":\"EV-1\""))
                .anySatisfy(data -> assertThat(data).contains("\"id\":\"EV-3\""));
    }

    // ---- fixtures ---------------------------------------------------------------------

    /**
     * A single-member cluster that talks to nothing it was not told about.
     *
     * <p>The same shape {@code HazelcastMapRecordSourceTest} uses, on a port range of its own:
     * one Gradle invocation can run both, and a member that auto-detected the other's would
     * make each suite's map names the other's problem.
     */
    private static Config memberConfig() {
        Config config = new Config();
        config.setClusterName(CLUSTER);
        // A test run is not a telemetry opportunity, and the call slows down startup.
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.logging.type", "slf4j");
        // The suite shuts its own member down; a hook would fight the JVM's exit.
        config.setProperty("hazelcast.shutdownhook.enabled", "false");

        NetworkConfig network = config.getNetworkConfig();
        network.setPort(BASE_PORT).setPortAutoIncrement(true);
        network.getInterfaces().setEnabled(true).clear().addInterface("127.0.0.1");

        JoinConfig join = network.getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        return config;
    }

    /** A position the cluster already knows is JSON, so its text reaches AMPS unchanged. */
    private static HazelcastJsonValue position(String account, String symbol, int qty,
                                               String avgCost) {
        return new HazelcastJsonValue("{\"account\":\"" + account + "\",\"symbol\":\"" + symbol
                + "\",\"qty\":" + qty + ",\"avgCost\":" + avgCost + "}");
    }

    /** An event carrying the {@code id} the SOW topic is keyed on, and the queried {@code qty}. */
    private static HazelcastJsonValue event(String id, int qty) {
        return new HazelcastJsonValue("{\"id\":\"" + id + "\",\"qty\":" + qty + "}");
    }

    /** Poll {@code sow/connectors/positions} until the assertion holds. */
    private void awaitPositions(String what, ThrowingAssertion assertion) {
        Awaitility.await(what).atMost(PATIENCE)
                .untilAsserted(() -> assertion.accept(AmpsSow.records(json, POSITIONS, "1=1")));
    }

    /** The payload AMPS filed under one SowKey, or a failure naming what it did hold. */
    private static String dataOf(List<AmpsSow.Record> records, String sowKey) {
        return records.stream()
                .filter(record -> sowKey.equals(record.sowKey()))
                .map(AmpsSow.Record::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SOW record keyed " + sowKey + " in "
                        + records));
    }

    /** An assertion over a SOW read, which is itself allowed to fail with an AMPS exception. */
    @FunctionalInterface
    private interface ThrowingAssertion {
        void accept(List<AmpsSow.Record> records) throws Exception;
    }
}
