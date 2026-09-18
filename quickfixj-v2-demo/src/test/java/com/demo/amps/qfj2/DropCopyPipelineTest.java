package com.demo.amps.qfj2;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.qfj2.engine.Direction;
import com.demo.amps.qfj2.flow.FixSessionDestination;
import com.demo.amps.qfj2.flow.rules.CopyTagRule;
import com.demo.amps.qfj2.flow.rules.ReceivedTimeRule;
import com.demo.amps.qfj2.flow.rules.SetTagRule;
import com.demo.amps.qfj2.flow.rules.SourceSessionRule;
import com.demo.amps.qfj2.mock.ExecutionReports;
import com.demo.amps.qfj2.seqno.SeqnoSnapshot;
import com.demo.amps.qfj2.support.EngineHarness;
import com.demo.amps.qfj2.support.InMemorySeqnoReplicator;
import com.demo.amps.qfj2.support.RecordingDestination;
import com.demo.amps.qfj2.support.TestPaths;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.Session;
import quickfix.SessionID;

/**
 * The whole pipeline between two real QuickFIX/J engines over loopback --
 * an acceptor playing the venue, an initiator playing the consumer -- with
 * the replicator in memory. No Spring, no AMPS: this is the engine, the
 * store, the rules and the destinations doing what the module claims.
 */
class DropCopyPipelineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private Path work;
    private int port;
    private final InMemorySeqnoReplicator venueCheckpoints = new InMemorySeqnoReplicator();
    private final InMemorySeqnoReplicator consumerCheckpoints = new InMemorySeqnoReplicator();

    @BeforeEach
    void freshWorkDirAndPort() throws Exception {
        work = TestPaths.freshWorkDir("pipeline");
        port = EngineHarness.freePort();
    }

    private EngineHarness venue() {
        return EngineHarness.acceptor(work.resolve("venue"), port)
                .session("VENUE", "DROPCOPY")
                .replicator(venueCheckpoints)
                .source("venue")
                .build()
                .start();
    }

    private EngineHarness.Builder consumer() {
        return EngineHarness.initiator(work.resolve("consumer"), "127.0.0.1", port)
                .session("DROPCOPY", "VENUE")
                .replicator(consumerCheckpoints)
                .source("consumer");
    }

    @Test
    @DisplayName("execution reports arrive enriched, and both sides' numbers are replicated as they change")
    void enrichesDeliversAndReplicates() throws Exception {
        RecordingDestination blotter = new RecordingDestination("blotter", Set.of("8"));
        try (EngineHarness venue = venue();
             EngineHarness consumer = consumer()
                     .rule(new SetTagRule(5001, "VENUE-DROPCOPY"))
                     .rule(new CopyTagRule(49, 5002))
                     .rule(new SourceSessionRule(5003))
                     .rule(new ReceivedTimeRule(5004))
                     .destination(blotter)
                     .build()
                     .start()) {
            consumer.awaitLogon();
            venue.awaitLogon();

            for (int n = 1; n <= 5; n++) {
                venue.send(ExecutionReports.sample("RUN", n, "AAPL"));
            }
            Awaitility.await().atMost(TIMEOUT).until(() -> blotter.count() == 5);

            String first = blotter.deliveries().get(0).wire();
            assertThat(first)
                    .contains("17=EXEC-RUN-1")
                    .contains("5001=VENUE-DROPCOPY")
                    .contains("5002=VENUE")
                    .contains("5003=FIX.4.2:DROPCOPY->VENUE")
                    .containsPattern("5004=\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}");
            assertThat(blotter.deliveries()).extracting(d -> d.context().direction())
                    .containsOnly(Direction.INBOUND);
            assertThat(consumer.application().inboundCount()).isEqualTo(5);
            assertThat(consumer.dispatcher().deliveredCount("blotter")).isEqualTo(5);

            // Replication has caught up with the file: the checkpoint says what
            // the session says.
            assertThat(consumer.publisher().flush(TIMEOUT)).isTrue();
            assertThat(venue.publisher().flush(TIMEOUT)).isTrue();
            Session consumerSession = consumer.session();
            SeqnoSnapshot consumerCheckpoint = consumerCheckpoints.latest(consumer.sessionId().toString()).orElseThrow();
            assertThat(consumerCheckpoint.nextSenderMsgSeqNum()).isEqualTo(consumerSession.getExpectedSenderNum());
            assertThat(consumerCheckpoint.nextTargetMsgSeqNum()).isEqualTo(consumerSession.getExpectedTargetNum());
            assertThat(consumerCheckpoint.nextTargetMsgSeqNum()).as("logon + 5 reports + heartbeats").isGreaterThan(6);
            assertThat(consumerCheckpoint.source()).isEqualTo("consumer");

            Session venueSession = venue.session();
            SeqnoSnapshot venueCheckpoint = venueCheckpoints.latest(venue.sessionId().toString()).orElseThrow();
            assertThat(venueCheckpoint.nextSenderMsgSeqNum()).isEqualTo(venueSession.getExpectedSenderNum());
            assertThat(venueCheckpoint.nextTargetMsgSeqNum()).isEqualTo(venueSession.getExpectedTargetNum());

            // And the two sides agree with each other.
            assertThat(venueSession.getExpectedTargetNum()).isEqualTo(consumerSession.getExpectedSenderNum());
            assertThat(venueSession.getExpectedSenderNum()).isEqualTo(consumerSession.getExpectedTargetNum());
        }
    }

    @Test
    @DisplayName("a destination that fails delays the message: the session drops, reconnects, and the gap is resent")
    void aFailedDeliveryIsRedeliveredNotLost() {
        RecordingDestination blotter = new RecordingDestination("blotter", Set.of("8"));
        try (EngineHarness venue = venue();
             EngineHarness consumer = consumer().destination(blotter).build().start()) {
            consumer.awaitLogon();
            venue.awaitLogon();
            int senderBefore = consumer.session().getExpectedSenderNum();

            blotter.failNext(1);
            venue.send(ExecutionReports.sample("RUN", 1, "AAPL"));
            // The exception left the consumer's inbound number where it was and
            // dropped the session; the initiator reconnects on its own ...
            Awaitility.await("disconnect").atMost(TIMEOUT).until(() -> !consumer.isLoggedOn());
            Awaitility.await("reconnect").atMost(TIMEOUT).until(consumer::isLoggedOn);
            // ... and the venue's next message is a gap the resend request closes.
            venue.send(ExecutionReports.sample("RUN", 2, "AAPL"));

            Awaitility.await().atMost(TIMEOUT).until(() -> blotter.count() == 2);
            assertThat(blotter.deliveries()).extracting(d -> d.message().getString(17))
                    .containsExactly("EXEC-RUN-1", "EXEC-RUN-2");
            assertThat(blotter.deliveries().get(0).wire()).as("the redelivery is a possible duplicate")
                    .contains("\u000143=Y\u0001");
            assertThat(consumer.adminCount(consumer.sessionId(), Direction.OUTBOUND, "2"))
                    .as("a resend request for the gap").isGreaterThanOrEqualTo(1);
            assertThat(consumer.session().getExpectedSenderNum() - senderBefore)
                    .as("a reconnect costs a logon and a resend request, not one request per message")
                    .isLessThanOrEqualTo(4);
        }
    }

    @Test
    @DisplayName("without the disconnect, a failed delivery draws a resend request per message instead")
    void withoutDisconnectEveryMessageAfterAFailureIsAGap() {
        RecordingDestination blotter = new RecordingDestination("blotter", Set.of("8"));
        try (EngineHarness venue = venue();
             EngineHarness consumer = consumer().disconnectOnFailure(false).destination(blotter).build().start()) {
            consumer.awaitLogon();
            venue.awaitLogon();

            blotter.failNext(1);
            venue.send(ExecutionReports.sample("RUN", 1, "AAPL"));
            venue.send(ExecutionReports.sample("RUN", 2, "AAPL"));

            Awaitility.await().atMost(TIMEOUT).until(() -> blotter.count() == 2);
            assertThat(blotter.deliveries()).extracting(d -> d.message().getString(17))
                    .containsExactly("EXEC-RUN-1", "EXEC-RUN-2");
            assertThat(consumer.adminCount(consumer.sessionId(), Direction.OUTBOUND, "2")).isEqualTo(1);
            assertThat(consumer.isLoggedOn()).as("the session was never dropped").isTrue();
        }
    }

    @Test
    @DisplayName("a fix destination forwards the enriched message on another session")
    void forwardsToAnotherSession() throws Exception {
        SessionID toDownstream = new SessionID("FIX.4.2", "DROPCOPY", "DOWNSTREAM");
        SessionID downstreamSide = new SessionID("FIX.4.2", "DOWNSTREAM", "DROPCOPY");
        RecordingDestination downstreamInbox = new RecordingDestination("downstream-inbox", Set.of("8"));

        // One acceptor hosting both counterparties: the venue and the downstream.
        try (EngineHarness acceptor = EngineHarness.acceptor(work.resolve("acceptor"), port)
                .session("VENUE", "DROPCOPY")
                .session("DOWNSTREAM", "DROPCOPY")
                .replicator(venueCheckpoints)
                .destination(downstreamInbox)
                .build()
                .start();
             EngineHarness consumer = EngineHarness.initiator(work.resolve("consumer"), "127.0.0.1", port)
                     .session("DROPCOPY", "VENUE")
                     .session("DROPCOPY", "DOWNSTREAM")
                     .replicator(consumerCheckpoints)
                     .rule(new SetTagRule(5001, "FORWARDED"))
                     .rule(new CopyTagRule(49, 5002))
                     .destination(new FixSessionDestination("downstream", toDownstream, Set.of("8")))
                     .build()
                     .start()) {
            for (SessionID id : consumer.sessionIds()) {
                consumer.awaitLogon(id);
            }
            acceptor.awaitLogon(new SessionID("FIX.4.2", "VENUE", "DROPCOPY"));
            acceptor.awaitLogon(downstreamSide);

            acceptor.send(ExecutionReports.sample("RUN", 9, "TSLA"), new SessionID("FIX.4.2", "VENUE", "DROPCOPY"));

            Awaitility.await().atMost(TIMEOUT).until(() -> downstreamInbox.count() == 1);
            RecordingDestination.Delivery delivery = downstreamInbox.deliveries().get(0);
            assertThat(delivery.context().sessionId()).isEqualTo(downstreamSide);
            assertThat(delivery.message().getString(17)).isEqualTo("EXEC-RUN-9");
            assertThat(delivery.message().getString(5001)).isEqualTo("FORWARDED");
            assertThat(delivery.message().getString(5002)).as("the venue's comp id, kept in the body").isEqualTo("VENUE");
            assertThat(delivery.message().getHeader().getString(49)).as("the header is the forwarding session's")
                    .isEqualTo("DROPCOPY");
            assertThat(delivery.message().getHeader().getString(56)).isEqualTo("DOWNSTREAM");
            assertThat(delivery.message().getHeader().isSetField(43)).as("not a possible duplicate").isFalse();
            assertThat(consumer.dispatcher().deliveredCount("downstream")).isEqualTo(1);
        }
    }
}
