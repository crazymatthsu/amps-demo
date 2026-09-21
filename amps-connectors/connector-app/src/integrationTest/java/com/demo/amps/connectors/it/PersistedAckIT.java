package com.demo.amps.connectors.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.connectors.amps.BatchPublisher;
import com.demo.amps.connectors.app.ConnectorApplication;
import com.demo.amps.connectors.config.AmpsTargetProperties;
import com.demo.amps.connectors.runtime.Connector;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * {@code ack-mode: PERSISTED} against a real AMPS: the acknowledgments come from the
 * server's persisted acks, observed through the real client's publish store on its receive
 * thread, rather than from a flush per batch.
 *
 * <p>Two connectors, because the mode has two halves:
 *
 * <ul>
 *   <li><b>positions</b> -- the default {@code max-pending}, far above anything this feed
 *       reaches, so no batch ever flushes: every record has to be acknowledged by an ack the
 *       receive thread delivered, and the counters have to say so ({@code published=N},
 *       {@code pending=0}, not one back-pressure flush)</li>
 *   <li><b>events</b> -- a {@code max-pending} smaller than a batch, so every batch crosses
 *       the line and flushes on the publishing thread: the back-pressure path, which has to
 *       leave the same SOW and the same {@code pending=0} behind it. It is visibly slower,
 *       and that is the measurement worth having: each of those flushes waits for the
 *       server's persisted ack, which 5.3.5 sends on a timer of about a second, so five
 *       batches take about five seconds here where the two hundred positions took one.
 *       That per-batch wait is what {@code FLUSH} mode pays on every batch and what
 *       {@code PERSISTED} mode pays only when it is over the line</li>
 * </ul>
 *
 * <p>The tracker's own counters are read beside the SOW because they are the operator's
 * evidence: a SOW with every record in it proves the publishes; {@code persisted} equal to
 * the record count proves the acks came back and were matched to their records.
 *
 * <p>Skips rather than fails when {@code AMPS_IMAGE} is unset -- see amps-test-harness -- so
 * {@code ./gradlew build} stays green on a machine without an image, and a green build is not
 * by itself proof this ran.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistedAckIT {

    private static final String POSITIONS = "sow/connectors/positions";
    private static final String EVENTS = "sow/connectors/events";

    /** Generous: a batch waits out its idle timer, and the first publish also logs on. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private static final int POSITION_COUNT = 200;
    private static final int EVENT_COUNT = 50;

    private AmpsTestServer server;
    private ConnectorAppRunner app;
    private Client json;

    private int positionsPort;
    private int eventsPort;

    @BeforeAll
    void startServerAndConnectors() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.AMPS_CONNECTORS);
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        server = AmpsTestServer.start(AmpsFlow.AMPS_CONNECTORS);
        positionsPort = freePort();
        eventsPort = freePort();

        app = ConnectorAppRunner.against(server.port(), ConnectorApplication.class)
                // ---- PERSISTED, never over max-pending: acks only ----------------------
                .connector("positions-persisted")
                .set("format", "JSON")
                .set("source.tcp.mode", "LISTEN")
                .set("source.tcp.host", "127.0.0.1")
                .set("source.tcp.port", positionsPort)
                .set("amps.topic", POSITIONS)
                .set("amps.message-type", "json")
                .set("amps.ack-mode", "PERSISTED")
                .set("amps.key.mode", "PUBLISHER")
                .set("amps.key.fields[0]", "account")
                .set("amps.key.fields[1]", "symbol")
                .set("amps.key.separator", "|")
                .set("amps.batch.max-messages", 50)
                .set("amps.batch.flush-interval", "200ms")
                // ---- PERSISTED, max-pending below the batch size: back-pressure --------
                .connector("events-persisted")
                .set("format", "JSON")
                .set("source.tcp.mode", "LISTEN")
                .set("source.tcp.host", "127.0.0.1")
                .set("source.tcp.port", eventsPort)
                .set("amps.topic", EVENTS)
                .set("amps.message-type", "json")
                .set("amps.ack-mode", "PERSISTED")
                .set("amps.key.mode", "SERVER")
                .set("amps.key.fields[0]", "id")
                .set("amps.batch.max-messages", 10)
                .set("amps.batch.max-pending", 5)
                .set("amps.batch.flush-interval", "200ms")
                .start();

        json = AmpsSow.connect(server.port(), "json", "persisted-ack-it");
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
    @DisplayName("every record is acknowledged by the server's persisted ack, with no flush per batch")
    void recordsAreAcknowledgedByThePersistedAcks() throws Exception {
        Connector connector = app.connector("positions-persisted");
        BatchPublisher batches = connector.batchPublisher();
        assertThat(batches.ackMode()).isEqualTo(AmpsTargetProperties.AckMode.PERSISTED);

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < POSITION_COUNT; i++) {
            lines.add(position("ACC-" + (i % 20), "SYM-" + i, 100 + i, "10." + i));
        }
        feed(positionsPort, lines.toArray(String[]::new));

        Awaitility.await("every position in the SOW").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(json, POSITIONS, "1=1"))
                        .hasSize(POSITION_COUNT));

        // The SOW proves the publishes; the counters prove the acks came back and found
        // their records. Both settle a little after the last publish, on the receive thread.
        Awaitility.await("every record persisted and nothing pending").atMost(PATIENCE)
                .untilAsserted(() -> {
                    assertThat(connector.published()).isEqualTo(POSITION_COUNT);
                    assertThat(connector.pending()).isZero();
                });
        assertThat(batches.tracker().persisted()).isEqualTo(POSITION_COUNT);
        assertThat(batches.tracker().pending()).isZero();
        assertThat(batches.tracker().rejected()).isZero();
        assertThat(batches.tracker().duplicates()).isZero();
        assertThat(batches.tracker().unsequenced())
                .as("every command got a sequence from the memory publish store").isZero();
        assertThat(batches.backpressureFlushes())
                .as("never over the default max-pending, so never a flush").isZero();
        assertThat(batches.failedBatches()).isZero();
        assertThat(batches.publishedBatches()).isGreaterThanOrEqualTo(POSITION_COUNT / 50);
        assertThat(connector.received()).isEqualTo(POSITION_COUNT);
        assertThat(connector.status())
                .contains("RUNNING")
                .contains("published=" + POSITION_COUNT)
                .contains("pending=0")
                .contains("publish-rejected=0");
    }

    @Test
    @DisplayName("a max-pending below the batch size flushes on the publishing thread, and still lands everything")
    void backPressureFlushesWhenTooMuchIsPending() throws Exception {
        Connector connector = app.connector("events-persisted");
        BatchPublisher batches = connector.batchPublisher();

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < EVENT_COUNT; i++) {
            lines.add("{\"id\":\"EV-" + i + "\",\"severity\":\"INFO\",\"text\":\"event " + i + "\"}");
        }
        feed(eventsPort, lines.toArray(String[]::new));

        Awaitility.await("every event in the SOW").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(json, EVENTS, "1=1"))
                        .hasSize(EVENT_COUNT));
        Awaitility.await("every record persisted and nothing pending").atMost(PATIENCE)
                .untilAsserted(() -> {
                    assertThat(connector.published()).isEqualTo(EVENT_COUNT);
                    assertThat(connector.pending()).isZero();
                });

        assertThat(batches.tracker().persisted()).isEqualTo(EVENT_COUNT);
        // Ten per batch against a bound of five: every full batch crossed the line.
        assertThat(batches.backpressureFlushes()).isGreaterThanOrEqualTo(EVENT_COUNT / 10 - 1);
        assertThat(batches.flushTimeouts()).isZero();
        assertThat(batches.failedBatches()).isZero();
        assertThat(batches.tracker().rejected()).isZero();
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

    /** A port nothing is listening on, for a connector to bind. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
