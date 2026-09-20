package com.demo.amps.connectors.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.connectors.app.ConnectorApplication;
import com.demo.amps.connectors.runtime.Connector;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.time.Duration;
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
 * AMPS as a <em>source</em>: two connectors reading topics of the same instance they publish
 * into, so the whole round trip -- subscribe, decode, key, publish, read back -- runs against
 * one real server with nothing else in the picture.
 *
 * <ul>
 *   <li><b>events-mirror</b> -- {@code sow_and_subscribe} on a server-keyed SOW topic, mirrored
 *       onto a publisher-keyed one. Records that existed <em>before the application started</em>
 *       arrive (the SOW half), a later publish arrives (the subscribe half), and a
 *       {@code sow_delete} on the source arrives as an out-of-focus message and removes the
 *       mirror's record -- the delete that only {@code oof} can deliver</li>
 *   <li><b>ticks-replay</b> -- a bookmark subscription from the epoch on a journal-only topic,
 *       keyed by the server on the way out. What was journalled before the application
 *       existed is replayed into the SOW, and what is published afterwards follows live</li>
 * </ul>
 *
 * <p>The two are chained on purpose: the replay's target is the mirror's source, so a tick
 * travels journal -> SOW -> mirror through two connectors, four AMPS clients and one server.
 * That each connector holds a subscribing client <em>and</em> a publishing client on the same
 * instance, and both log on, is the assertion behind every other one here: AMPS refuses a
 * logon whose client name is in use, so a shared name would leave the connector retrying
 * forever.
 *
 * <p>Skips rather than fails when {@code AMPS_IMAGE} is unset, like the other suites.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AmpsToAmpsIT {

    private static final String POSITIONS = "sow/connectors/positions";
    private static final String EVENTS = "sow/connectors/events";
    private static final String TICKS = "connectors/ticks";

    /** Generous: a batch waits out its idle timer, a subscription its logon. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private AmpsTestServer server;
    private ConnectorAppRunner app;
    private Client json;

    @BeforeAll
    void startServerAndConnectors() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.AMPS_CONNECTORS);
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        server = AmpsTestServer.start(AmpsFlow.AMPS_CONNECTORS);
        json = AmpsSow.connect(server.port(), "json", "amps-it-json");

        // Before the application exists: two SOW records the mirror must pick up as a
        // snapshot, and three journal entries the replay must find behind the epoch bookmark.
        json.publish(EVENTS, event("e1", "before"));
        json.publish(EVENTS, event("e2", "before"));
        json.publish(TICKS, tick("t1", "1.0"));
        json.publish(TICKS, tick("t2", "2.0"));
        json.publish(TICKS, tick("t3", "3.0"));
        json.publishFlush(AmpsSow.TIMEOUT.toMillis());

        app = ConnectorAppRunner.against(server.port(), ConnectorApplication.class)
                // ---- a keyed SOW, mirrored: sow_and_subscribe + oof -----------------
                .connector("events-mirror")
                .set("format", "JSON")
                .set("source.amps.topic", EVENTS)
                .set("source.amps.mode", "SOW_AND_SUBSCRIBE")
                .set("amps.topic", POSITIONS)
                .set("amps.message-type", "json")
                // The mirror keys by the publisher, from the same field the source's
                // server keyed on -- so a delete on one side is a delete on the other.
                .set("amps.key.mode", "PUBLISHER")
                .set("amps.key.fields[0]", "id")
                .set("amps.on-delete", "SOW_DELETE")
                .set("amps.batch.max-messages", 50)
                .set("amps.batch.flush-interval", "200ms")
                // ---- a journal replayed from the epoch into a server-keyed SOW ------
                .connector("ticks-replay")
                .set("format", "JSON")
                .set("source.amps.topic", TICKS)
                .set("source.amps.mode", "BOOKMARK")
                .set("source.amps.bookmark", "EPOCH")
                .set("amps.topic", EVENTS)
                .set("amps.message-type", "json")
                .set("amps.key.mode", "SERVER")
                .set("amps.key.fields[0]", "id")
                .set("amps.on-delete", "IGNORE")
                .set("amps.batch.max-messages", 50)
                .set("amps.batch.flush-interval", "200ms")
                .start();
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
    }

    @Test
    @Order(1)
    @DisplayName("both connectors log on twice to the one instance -- as a subscriber and as a publisher")
    void eachConnectorHoldsTwoClientsOnTheSameInstance() {
        Awaitility.await("both ends of both connectors connected").atMost(PATIENCE)
                .until(() -> app.manager().connectors().stream().allMatch(Connector::isConnected));
        assertThat(app.connector("events-mirror").isConnected()).isTrue();
        assertThat(app.connector("ticks-replay").isConnected()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("the records a SOW already held reach the mirror, keyed by the publisher")
    void existingSowRecordsAreMirrored() {
        Awaitility.await("the SOW half of sow_and_subscribe").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(json, POSITIONS, "/id LIKE '^e'"))
                        .extracting(AmpsSow.Record::sowKey)
                        .containsExactlyInAnyOrder("e1", "e2"));
    }

    @Test
    @Order(3)
    @DisplayName("a journal replayed from the epoch delivers what was published before the application existed")
    void journalIsReplayedFromTheEpoch() {
        Awaitility.await("the three ticks, keyed by the server").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(json, EVENTS, "/id LIKE '^t'"))
                        .extracting(AmpsSow.Record::data)
                        .allSatisfy(data -> assertThat(data).contains("\"last\""))
                        .hasSize(3));
        // ...and on through the mirror, which saw them either in its snapshot or live.
        Awaitility.await("the same three, mirrored").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(json, POSITIONS, "/id LIKE '^t'"))
                        .extracting(AmpsSow.Record::sowKey)
                        .containsExactlyInAnyOrder("t1", "t2", "t3"));
    }

    @Test
    @Order(4)
    @DisplayName("a publish after the subscriptions are up follows live, through both connectors")
    void livePublishesFollow() throws Exception {
        json.publish(EVENTS, event("e3", "after"));
        json.publish(TICKS, tick("t4", "4.0"));
        json.publishFlush(AmpsSow.TIMEOUT.toMillis());

        Awaitility.await("the live event, mirrored").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(json, POSITIONS, "/id = 'e3'"))
                        .singleElement()
                        .satisfies(record -> {
                            assertThat(record.sowKey()).isEqualTo("e3");
                            assertThat(record.data()).contains("\"after\"");
                        }));
        Awaitility.await("the live tick, replayed then mirrored").atMost(PATIENCE)
                .untilAsserted(() -> {
                    assertThat(AmpsSow.records(json, EVENTS, "/id = 't4'")).hasSize(1);
                    assertThat(AmpsSow.records(json, POSITIONS, "/id = 't4'"))
                            .extracting(AmpsSow.Record::sowKey).containsExactly("t4");
                });
    }

    @Test
    @Order(5)
    @DisplayName("a sow_delete on the source arrives out of focus and deletes the mirror's record")
    void sourceDeleteBecomesTargetDelete() throws Exception {
        json.sowDelete(EVENTS, "/id = 'e1'", AmpsSow.TIMEOUT.toMillis());

        Awaitility.await("e1 gone from the mirror, e2 untouched").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(json, POSITIONS, "/id LIKE '^e'"))
                        .extracting(AmpsSow.Record::sowKey)
                        .containsExactlyInAnyOrder("e2", "e3"));
        assertThat(app.connector("events-mirror").ignoredDeletes()).isZero();
        assertThat(app.connector("events-mirror").rejected()).isZero();
    }

    private static String event(String id, String detail) {
        return "{\"id\":\"" + id + "\",\"severity\":\"INFO\",\"detail\":\"" + detail + "\"}";
    }

    private static String tick(String id, String last) {
        return "{\"id\":\"" + id + "\",\"symbol\":\"AAPL\",\"last\":" + last + "}";
    }
}
