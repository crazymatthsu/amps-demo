package com.demo.amps.qfj2.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.qfj2.amps.ReconnectingAmpsPublisher;
import com.demo.amps.qfj2.engine.Direction;
import com.demo.amps.qfj2.flow.AmpsDestination;
import com.demo.amps.qfj2.flow.rules.SetTagRule;
import com.demo.amps.qfj2.mock.ExecutionReports;
import com.demo.amps.qfj2.seqno.AmpsSeqnoReplicator;
import com.demo.amps.qfj2.seqno.RecoveryReport;
import com.demo.amps.qfj2.seqno.SeqnoAdmin;
import com.demo.amps.qfj2.seqno.SeqnoRecovery;
import com.demo.amps.qfj2.seqno.SeqnoSnapshot;
import com.demo.amps.qfj2.support.EngineHarness;
import com.demo.amps.qfj2.support.TestPaths;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * The failover, for real: two QuickFIX/J engines over loopback, their
 * sequence numbers checkpointed in a throwaway AMPS container, the
 * consumer's disk deleted between one instance and the next.
 *
 * <p>Skips rather than fails when {@code AMPS_IMAGE} is unset -- see
 * amps-test-harness -- so {@code ./gradlew build} stays green on a machine
 * without an image, and a green build is not by itself proof this ran.
 */
class SeqnoFailoverIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String BLOTTER = "sow/dropcopy/fix42/execs";

    private static AmpsTestServer server;
    private static Client fixClient;
    private static ReconnectingAmpsPublisher fixPublisher;

    @BeforeAll
    static void startAmps() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.QUICKFIXJ_DROPCOPY);
        assumeTrue(unavailable.isEmpty(), () -> "skipping: " + unavailable.get());
        server = AmpsTestServer.start(AmpsFlow.QUICKFIXJ_DROPCOPY);
        fixClient = new Client("qfj2-it-fix");
        fixClient.connect("tcp://127.0.0.1:" + server.port() + "/amps/fix");
        fixClient.logon(10_000);
        fixPublisher = new ReconnectingAmpsPublisher("qfj2-it-publisher",
                "tcp://127.0.0.1:" + server.port() + "/amps/fix", 10_000, Duration.ofSeconds(30),
                Duration.ofSeconds(5));
    }

    @AfterAll
    static void stopAmps() {
        if (fixPublisher != null) {
            fixPublisher.close();
        }
        if (fixClient != null) {
            fixClient.close();
        }
        if (server != null) {
            server.close();
        }
    }

    /** A checkpoint connection: the harness's URI selects json, which is what the topic is. */
    private static AmpsSeqnoReplicator replicator(String clientName) {
        return new AmpsSeqnoReplicator(new AmpsSeqnoReplicator.Settings(server.uri(), "sow/quickfixj/seqno",
                clientName, 10_000, Duration.ofSeconds(30)));
    }

    private static AmpsDestination blotter() {
        return new AmpsDestination("execs-blotter", fixPublisher, BLOTTER, Set.of("8"), true);
    }

    /** How many records the blotter holds for one test's tag-5001 marker. */
    private static int blotterRecords(String marker) throws Exception {
        int records = 0;
        try (MessageStream stream = fixClient.sow(BLOTTER, "/5001 = '" + marker + "'")) {
            stream.timeout(10_000);
            while (stream.hasNext()) {
                Message message = stream.next();
                if (message == null || message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    records++;
                }
            }
        }
        return records;
    }

    private static void awaitInSync(EngineHarness venue, SessionID venueId, EngineHarness consumer, SessionID consumerId) {
        Awaitility.await("both sides agree on the numbers").atMost(TIMEOUT).until(() ->
                venue.session(venueId).getExpectedTargetNum() == consumer.session(consumerId).getExpectedSenderNum()
                        && venue.session(venueId).getExpectedSenderNum()
                        == consumer.session(consumerId).getExpectedTargetNum());
    }

    @Test
    @DisplayName("a consumer starting on an empty disk resumes from the AMPS checkpoint, with no resend")
    void consumerStartingOnAnEmptyDiskResumesFromAmps() throws Exception {
        Path work = TestPaths.freshWorkDir("failover");
        int port = EngineHarness.freePort();
        SessionID consumerId = new SessionID("FIX.4.2", "DC1", "VENUE1");
        SessionID venueId = new SessionID("FIX.4.2", "VENUE1", "DC1");

        try (EngineHarness venue = EngineHarness.acceptor(work.resolve("venue"), port)
                .session("VENUE1", "DC1")
                .replicator(replicator("it-venue"))
                .source("venue-primary")
                .build()
                .start()) {

            // ---- the primary: five reports, checkpoints keeping up ---------
            SessionSettings consumerSettings;
            try (EngineHarness primary = EngineHarness.initiator(work.resolve("consumer-primary"), "127.0.0.1", port)
                    .session("DC1", "VENUE1")
                    .replicator(replicator("it-consumer-primary"))
                    .source("consumer-primary")
                    .rule(new SetTagRule(5001, "failover"))
                    .destination(blotter())
                    .build()
                    .start()) {
                primary.awaitLogon();
                venue.awaitLogon(venueId);
                assertThat(primary.recovery().orElseThrow().applied())
                        .as("first ever start: nothing in AMPS, the file's 1/1 stands")
                        .isEqualTo(SeqnoRecovery.Applied.FILE);

                for (int n = 1; n <= 5; n++) {
                    venue.send(ExecutionReports.sample("FO", n, "AAPL"), venueId);
                }
                Awaitility.await().atMost(TIMEOUT).until(() -> blotterRecords("failover") == 5);

                assertThat(primary.publisher().flush(TIMEOUT)).isTrue();
                try (AmpsSeqnoReplicator check = replicator("it-check-1")) {
                    SeqnoSnapshot live = check.load(consumerId.toString()).orElseThrow();
                    assertThat(live.nextSenderMsgSeqNum()).isEqualTo(primary.session().getExpectedSenderNum());
                    assertThat(live.nextTargetMsgSeqNum()).isEqualTo(primary.session().getExpectedTargetNum());
                    assertThat(live.nextTargetMsgSeqNum()).as("logon + five reports at least").isGreaterThan(6);
                }
                consumerSettings = primary.settings();
            }

            // ---- stopped cleanly: the logout's increment replicated too ------
            SeqnoSnapshot lastPrimary;
            try (AmpsSeqnoReplicator check = replicator("it-check-2")) {
                SeqnoAdmin.State state = new SeqnoAdmin(consumerSettings, check, "it").show(consumerId);
                assertThat(state.file()).isPresent();
                assertThat(state.amps()).isPresent();
                assertThat(state.inSync()).as("file %s vs AMPS %s", state.file().get().numbers(),
                        state.amps().get().numbers()).isTrue();
                lastPrimary = state.amps().get();
            }
            assertThat(lastPrimary.source()).isEqualTo("consumer-primary");

            // ---- the primary's disk is gone ---------------------------------
            TestPaths.deleteRecursively(work.resolve("consumer-primary"));

            // ---- the DR instance: empty disk, same session -------------------
            try (EngineHarness dr = EngineHarness.initiator(work.resolve("consumer-dr"), "127.0.0.1", port)
                    .session("DC1", "VENUE1")
                    .replicator(replicator("it-consumer-dr"))
                    .source("consumer-dr")
                    .rule(new SetTagRule(5001, "failover"))
                    .destination(blotter())
                    .build()
                    .start()) {
                RecoveryReport report = dr.recovery().orElseThrow();
                assertThat(report.applied()).isEqualTo(SeqnoRecovery.Applied.AMPS);
                assertThat(report.fileSenderBefore()).isEqualTo(1);
                assertThat(report.fileTargetBefore()).isEqualTo(1);
                assertThat(report.decision().numbers()).isEqualTo(lastPrimary.numbers());

                dr.awaitLogon();
                awaitInSync(venue, venueId, dr, consumerId);
                assertThat(dr.adminCount(consumerId, Direction.OUTBOUND, "2")).as("resend requests sent").isZero();
                assertThat(dr.adminCount(consumerId, Direction.INBOUND, "2")).as("resend requests received").isZero();
                assertThat(dr.adminCount(consumerId, Direction.INBOUND, "4")).as("sequence resets received").isZero();

                // Traffic carries on where it left off.
                for (int n = 6; n <= 7; n++) {
                    venue.send(ExecutionReports.sample("FO", n, "AAPL"), venueId);
                }
                Awaitility.await().atMost(TIMEOUT).until(() -> blotterRecords("failover") == 7);

                // And so do the checkpoints, now from the DR box.
                assertThat(dr.publisher().flush(TIMEOUT)).isTrue();
                try (AmpsSeqnoReplicator check = replicator("it-check-3")) {
                    SeqnoSnapshot now = check.load(consumerId.toString()).orElseThrow();
                    assertThat(now.source()).isEqualTo("consumer-dr");
                    assertThat(now.revision()).isGreaterThan(lastPrimary.revision());
                    assertThat(now.nextSenderMsgSeqNum()).isEqualTo(dr.session().getExpectedSenderNum());
                    assertThat(now.nextTargetMsgSeqNum()).isEqualTo(dr.session().getExpectedTargetNum());
                }
            }
        }
    }

    @Test
    @DisplayName("a manual resequence written to AMPS is what both engines start with")
    void manualResequenceInAmpsIsAppliedAtStartup() throws Exception {
        Path work = TestPaths.freshWorkDir("manual");
        int port = EngineHarness.freePort();
        SessionID consumerId = new SessionID("FIX.4.2", "DC2", "VENUE2");
        SessionID venueId = new SessionID("FIX.4.2", "VENUE2", "DC2");

        // Built, not started: the settings are what the admin tool needs.
        EngineHarness venue = EngineHarness.acceptor(work.resolve("venue"), port)
                .session("VENUE2", "DC2")
                .replicator(replicator("it-venue2"))
                .source("venue")
                .build();
        EngineHarness consumer = EngineHarness.initiator(work.resolve("consumer"), "127.0.0.1", port)
                .session("DC2", "VENUE2")
                .replicator(replicator("it-consumer2"))
                .source("consumer")
                .rule(new SetTagRule(5001, "manual"))
                .destination(blotter())
                .build();

        // The operator sets both sides consistently, with the engines down.
        try (AmpsSeqnoReplicator admin = replicator("it-admin")) {
            SeqnoSnapshot venueSet = new SeqnoAdmin(venue.settings(), admin, "admin@it")
                    .setAmps(venueId, OptionalInt.of(700), OptionalInt.of(500));
            SeqnoSnapshot consumerSet = new SeqnoAdmin(consumer.settings(), admin, "admin@it")
                    .setAmps(consumerId, OptionalInt.of(500), OptionalInt.of(700));
            assertThat(venueSet.numbers()).isEqualTo("700/500");
            assertThat(consumerSet.numbers()).isEqualTo("500/700");
        }

        try (venue; consumer) {
            venue.start();
            consumer.start();
            assertThat(venue.recovery(venueId).orElseThrow().decision().numbers()).isEqualTo("700/500");
            assertThat(consumer.recovery(consumerId).orElseThrow().decision().numbers()).isEqualTo("500/700");
            assertThat(consumer.recovery(consumerId).orElseThrow().applied()).isEqualTo(SeqnoRecovery.Applied.AMPS);

            consumer.awaitLogon();
            venue.awaitLogon(venueId);
            // The logon itself was MsgSeqNum 500 one way and 700 the other.
            assertThat(consumer.session().getExpectedSenderNum()).isGreaterThanOrEqualTo(501);
            assertThat(consumer.session().getExpectedTargetNum()).isGreaterThanOrEqualTo(701);
            awaitInSync(venue, venueId, consumer, consumerId);
            assertThat(consumer.adminCount(consumerId, Direction.OUTBOUND, "2")).isZero();
            assertThat(consumer.adminCount(consumerId, Direction.INBOUND, "2")).isZero();

            venue.send(ExecutionReports.sample("MAN", 1, "MSFT"), venueId);
            Awaitility.await().atMost(TIMEOUT).until(() -> blotterRecords("manual") == 1);

            // The engine's own checkpoints have taken over from the operator's.
            assertThat(consumer.publisher().flush(TIMEOUT)).isTrue();
            try (AmpsSeqnoReplicator check = replicator("it-check-4")) {
                SeqnoSnapshot now = check.load(consumerId.toString()).orElseThrow();
                assertThat(now.source()).isEqualTo("consumer");
                assertThat(now.revision()).isGreaterThan(1);
                assertThat(now.nextSenderMsgSeqNum()).isGreaterThanOrEqualTo(501);
            }
        }
    }
}
