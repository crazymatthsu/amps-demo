package com.demo.amps.connectors.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Four socket feeds through the real connector application into a real AMPS instance, read
 * back with a plain client.
 *
 * <p>This is where the framework's promises stop being unit tests. Each connector exercises
 * one thing the SOW can contradict:
 *
 * <ul>
 *   <li><b>positions</b> -- {@code key.mode: PUBLISHER} onto a topic declared with no
 *       {@code <Key>}. The assertion is on {@link com.crankuptheamps.client.Message#getSowKey()}
 *       rather than on the record count alone, because the failure this mode exists to prevent
 *       looks <em>fine</em> from the connector's side: AMPS accepts a keyless publish onto such
 *       a topic and files every one of them under the same sentinel key, so a feed collapses
 *       onto one record while every counter says it published</li>
 *   <li><b>events</b> -- {@code key.mode: SERVER} plus a filter, and the two ways a record can
 *       legitimately not arrive: the filter refused it, or it could not be decoded at all. Both
 *       have to leave the SOW alone and neither may stop the feed</li>
 *   <li><b>ticks</b> -- a journal-only topic, which has no SOW to query: the only way to see
 *       what reached it is to replay the transaction log from the epoch bookmark</li>
 *   <li><b>orders</b> -- FIX rather than JSON, on a topic AMPS keys itself from tag 11, where a
 *       later message for the same ClOrdID must <em>replace</em> the record rather than add one</li>
 * </ul>
 *
 * <p>Every assertion polls the SOW with Awaitility. {@code Client.flush()} proves nothing here
 * -- it is the connector's client that publishes, not the test's -- and a batch is released by
 * size or by an idle timer, so "has it arrived yet" is genuinely a question about elapsed time.
 *
 * <p>Skips rather than fails when {@code AMPS_IMAGE} is unset -- see amps-test-harness -- so
 * {@code ./gradlew build} stays green on a machine without an image, and a green build is not
 * by itself proof this ran.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TcpToAmpsIT {

    private static final String POSITIONS = "sow/connectors/positions";
    private static final String EVENTS = "sow/connectors/events";
    private static final String TICKS = "connectors/ticks";
    private static final String ORDERS = "sow/connectors/orders";

    /** The FIX field separator every dictionary -- and AMPS's own fix parser -- assumes. */
    private static final char SOH = (char) 0x01;

    /** Generous: a batch waits out its idle timer, and the first publish also logs on. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private AmpsTestServer server;
    private ConnectorAppRunner app;
    private Client json;
    private Client fix;

    private int positionsPort;
    private int eventsPort;
    private int ticksPort;
    private int ordersPort;

    @BeforeAll
    void startServerAndConnectors() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.AMPS_CONNECTORS);
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        server = AmpsTestServer.start(AmpsFlow.AMPS_CONNECTORS);
        positionsPort = freePort();
        eventsPort = freePort();
        ticksPort = freePort();
        ordersPort = freePort();

        app = ConnectorAppRunner.against(server.port())
                // ---- PUBLISHER-keyed upserts onto an unkeyed SOW topic -------------
                .connector("positions-tcp")
                .set("format", "JSON")
                .set("source.tcp.mode", "LISTEN")
                .set("source.tcp.host", "127.0.0.1")
                .set("source.tcp.port", positionsPort)
                .set("amps.topic", POSITIONS)
                .set("amps.message-type", "json")
                .set("amps.key.mode", "PUBLISHER")
                .set("amps.key.fields[0]", "account")
                .set("amps.key.fields[1]", "symbol")
                .set("amps.key.separator", "|")
                .set("amps.batch.max-messages", 50)
                .set("amps.batch.flush-interval", "200ms")
                // ---- SERVER-keyed upserts, with a filter in front ------------------
                .connector("events-tcp")
                .set("format", "JSON")
                .set("source.tcp.mode", "LISTEN")
                .set("source.tcp.host", "127.0.0.1")
                .set("source.tcp.port", eventsPort)
                .set("filter.match", "ALL")
                .set("filter.rules[0].field", "severity")
                .set("filter.rules[0].in[0]", "INFO")
                .set("filter.rules[0].in[1]", "WARN")
                .set("amps.topic", EVENTS)
                .set("amps.message-type", "json")
                .set("amps.key.mode", "SERVER")
                .set("amps.key.fields[0]", "id")
                .set("amps.batch.max-messages", 50)
                .set("amps.batch.flush-interval", "200ms")
                // ---- a journal topic: no key, no deletes, everything appended ------
                .connector("ticks-tcp")
                .set("format", "JSON")
                .set("source.tcp.mode", "LISTEN")
                .set("source.tcp.host", "127.0.0.1")
                .set("source.tcp.port", ticksPort)
                .set("amps.topic", TICKS)
                .set("amps.message-type", "json")
                .set("amps.on-delete", "IGNORE")
                .set("amps.batch.max-messages", 50)
                .set("amps.batch.flush-interval", "200ms")
                // ---- FIX onto the topic AMPS keys from tag 11 ----------------------
                .connector("orders-tcp")
                .set("format", "FIX")
                // The feed writes SOH between the fields, as a FIX session does; the socket's
                // own framing is still the newline. A feed that writes `|` instead says
                // `field-separator: "|"` -- but the connector's separator serves BOTH ends,
                // so the pipes would reach AMPS as well, and a topic whose <Key> is /11 gets
                // nothing it can parse out of them. Measured on 5.3.5.135: the publish is
                // ACCEPTED, the record is filed under a key derived from nothing recognisable
                // (8936155381668143850 for the one tried), and no /11 or /55 filter ever
                // matches it again -- a silently unreachable SOW, which is the failure mode
                // worth writing a comment about.
                .set("source.tcp.mode", "LISTEN")
                .set("source.tcp.host", "127.0.0.1")
                .set("source.tcp.port", ordersPort)
                .set("amps.topic", ORDERS)
                .set("amps.message-type", "fix")
                .set("amps.key.mode", "SERVER")
                .set("amps.key.fields[0]", "11")
                .set("amps.batch.max-messages", 50)
                .set("amps.batch.flush-interval", "200ms")
                .start();

        // Two clients, because the message type lives in the URI: the same connection cannot
        // read a json topic and a fix one.
        json = AmpsSow.connect(server.port(), "json", "tcp-it-json");
        fix = AmpsSow.connect(server.port(), "fix", "tcp-it-fix");
    }

    @AfterAll
    void stopEverything() {
        if (app != null) {
            app.close();
        }
        if (json != null) {
            json.close();
        }
        if (fix != null) {
            fix.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("the publisher's key is the SOW key, and an update replaces that record")
    void publisherKeyedRecordsConvergeOnTheirOwnKeys() throws Exception {
        feed(positionsPort,
                position("ACC-1", "AAPL", 100, "101.50"),
                position("ACC-2", "MSFT", 250, "330.25"),
                // The same account and symbol again: an upsert, not a second record.
                position("ACC-1", "AAPL", 175, "102.00"));

        Awaitility.await("two positions, keyed by the connector").atMost(PATIENCE)
                .untilAsserted(() -> {
                    List<AmpsSow.Record> records = AmpsSow.records(json, POSITIONS, "1=1");
                    assertThat(records).hasSize(2);
                    assertThat(records).extracting(AmpsSow.Record::sowKey)
                            .as("the SowKey the connector sent, not a sentinel AMPS invented")
                            .containsExactlyInAnyOrder("ACC-1|AAPL", "ACC-2|MSFT");
                    assertThat(records).filteredOn(r -> "ACC-1|AAPL".equals(r.sowKey()))
                            .singleElement()
                            .satisfies(record -> assertThat(record.data())
                                    .contains("\"quantity\":175")
                                    // A price survives the round trip digit for digit: the
                                    // decoder keeps it as a BigDecimal rather than a double.
                                    .contains("\"avgCost\":102.00"));
                });
    }

    @Test
    @DisplayName("a filtered line and a malformed one are counted, and neither reaches the SOW")
    void filteredAndMalformedRecordsNeverReachTheTopic() throws Exception {
        feed(eventsPort,
                "{\"id\":\"EV-1\",\"severity\":\"INFO\",\"detail\":\"kept\"}",
                // Refused by the filter: a decision, not a failure.
                "{\"id\":\"EV-2\",\"severity\":\"DEBUG\",\"detail\":\"filtered out\"}",
                // Truncated: the decoder throws, the record is rejected, the feed carries on.
                "{\"id\":\"EV-3\",\"severity\":\"INFO\"",
                "{\"id\":\"EV-4\",\"severity\":\"WARN\",\"detail\":\"kept too\"}");

        Awaitility.await("the two well-formed, unfiltered events").atMost(PATIENCE)
                .untilAsserted(() -> {
                    List<AmpsSow.Record> records = AmpsSow.records(json, EVENTS, "1=1");
                    assertThat(records).hasSize(2);
                    assertThat(records).extracting(AmpsSow.Record::data)
                            .anySatisfy(data -> assertThat(data).contains("EV-1"))
                            .anySatisfy(data -> assertThat(data).contains("EV-4"))
                            .noneSatisfy(data -> assertThat(data).contains("EV-2"))
                            .noneSatisfy(data -> assertThat(data).contains("EV-3"));
                });

        // The counters are the operator's half of the same fact: four received, one refused
        // by the filter, one that could not be decoded, and a connector still running.
        assertThat(app.connector("events-tcp").received()).isEqualTo(4);
        assertThat(app.connector("events-tcp").pipeline().filtered()).isEqualTo(1);
        assertThat(app.connector("events-tcp").pipeline().rejected()).isEqualTo(1);
        assertThat(app.connector("events-tcp").isStarted()).isTrue();
    }

    @Test
    @DisplayName("a journal topic keeps every record, replayed from the epoch bookmark")
    void theJournalTopicReplaysEveryTick() throws Exception {
        feed(ticksPort,
                tick("AAPL", "185.25"),
                tick("MSFT", "330.10"),
                tick("AAPL", "185.30"),
                tick("GOOG", "141.05"),
                tick("AAPL", "185.35"));

        // No SOW to query: connectors/ticks is journal-only, so the transaction log IS the
        // record of what arrived -- including the three AAPL ticks a keyed topic would have
        // collapsed into one.
        Awaitility.await("five ticks in the transaction log").atMost(PATIENCE)
                .untilAsserted(() -> {
                    List<String> replayed = AmpsSow.replay(json, TICKS, Duration.ofSeconds(2));
                    assertThat(replayed).hasSize(5);
                    assertThat(replayed).filteredOn(data -> data.contains("AAPL")).hasSize(3);
                    assertThat(replayed).last().asString().contains("185.35");
                });
    }

    @Test
    @DisplayName("a server-keyed FIX topic replaces the record when a later 35=G repeats tag 11")
    void serverKeyedFixRecordsAreReplacedByTheirSuccessors() throws Exception {
        feed(ordersPort, fixMessage("35=D", "11=ORD-1", "55=AAPL", "54=1", "38=100", "44=101.50",
                "60=20260919-10:00:00.000"));

        Awaitility.await("the new order, keyed by the server on tag 11").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(fix, ORDERS, "/11 = 'ORD-1'"))
                        .singleElement()
                        .satisfies(record -> assertThat(record.data()).contains("38=100")));

        // A replace for the same ClOrdID: one record, the later contents.
        feed(ordersPort, fixMessage("35=G", "11=ORD-1", "55=AAPL", "54=1", "38=175", "44=102.00",
                "60=20260919-10:01:00.000"));

        Awaitility.await("the replace superseding it").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(fix, ORDERS, "/11 = 'ORD-1'"))
                        .singleElement()
                        .satisfies(record -> assertThat(record.data())
                                .contains("35=G").contains("38=175")));
    }

    // ---- the feed --------------------------------------------------------------------

    /** Writes lines into a connector's LISTEN socket and hangs up. */
    private static void feed(int port, String... lines) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port);
             Writer writer = new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
            writer.flush();
        }
    }

    private static String position(String account, String symbol, int quantity, String avgCost) {
        return "{\"account\":\"" + account + "\",\"symbol\":\"" + symbol + "\",\"quantity\":"
                + quantity + ",\"avgCost\":" + avgCost + "}";
    }

    private static String tick(String symbol, String price) {
        return "{\"symbol\":\"" + symbol + "\",\"price\":" + price + ",\"size\":100}";
    }

    /** A FIX message as a session writes one: SOH between the fields, never a pipe. */
    private static String fixMessage(String... fields) {
        return String.join(String.valueOf(SOH), fields) + SOH;
    }

    /** A port nothing is listening on, for a connector to bind. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
