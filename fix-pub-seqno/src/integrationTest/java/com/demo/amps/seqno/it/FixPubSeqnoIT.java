package com.demo.amps.seqno.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.crankuptheamps.client.Client;
import com.demo.amps.seqno.SeqnoConfig;
import com.demo.amps.seqno.fix.FixMessage;
import com.demo.amps.seqno.fix.OrderFeed;
import com.demo.amps.seqno.outbox.Outbox;
import com.demo.amps.seqno.publish.JournalLastSequenceLocator;
import com.demo.amps.seqno.publish.JournalScanResult;
import com.demo.amps.seqno.publish.PublisherRecovery;
import com.demo.amps.seqno.publish.RecoveryDecision;
import com.demo.amps.seqno.publish.RecoveryReport;
import com.demo.amps.seqno.publish.SequencedPublisher;
import com.demo.amps.seqno.publish.SowLastSequenceLocator;
import com.demo.amps.seqno.subscribe.BookmarkSubscriber;
import com.demo.amps.seqno.subscribe.SequenceTracker;
import com.demo.amps.seqno.subscribe.SubscriberReport;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole mechanism against a real AMPS instance: publish, drop the
 * connection mid-batch, recover from the server's own answer, and check that
 * nothing was lost or duplicated.
 *
 * <p>The claim under test is the design's headline: <b>after a crash between
 * "recorded in the outbox" and "sent to AMPS", the publisher republishes
 * exactly the gap the server is missing -- no more, no less -- by asking the
 * server what its last tag 8888 was.</b>
 *
 * <p>Skipped, not failed, when no AMPS image is configured; see
 * {@link AmpsTestServer#unavailableReason(AmpsFlow)}. Assertions are plain
 * JUnit, matching the other non-Spring integration suites in this repository.
 */
class FixPubSeqnoIT {

    private static final String SENDER = "PUB-IT";

    @TempDir
    Path stateDir;

    private AmpsTestServer server;
    private SeqnoConfig config;

    @BeforeEach
    void startServer() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.FIX_PUB_SEQNO);
        assumeTrue(unavailable.isEmpty(), () -> "integration prerequisites: " + unavailable.get());

        server = AmpsTestServer.start(AmpsFlow.FIX_PUB_SEQNO);
        config = new SeqnoConfig(server.uri(), SeqnoConfig.DEFAULT_TOPIC, SENDER, stateDir,
                10_000, 2500, Duration.ofHours(24));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    // ---- the headline claim -------------------------------------------------

    @Test
    @DisplayName("a crash between outbox and wire is recovered with no loss and no duplicate")
    void recoversTheGapExactly() throws Exception {
        // 1. Publish 1..10 cleanly.
        Outbox outbox = Outbox.open(config.outboxFile());
        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, outbox, config);
            new PublisherRecovery(publisher, config, seq).recover();
            seq.publishAll(new OrderFeed(SENDER, 1, Instant.now()).next(10));
            seq.flush();
            assertEquals(10, lastInSow(publisher), "AMPS holds 1..10 after a clean publish");
        }

        // 2. Crash: record 11..16 in the outbox, send only 11..12, drop the link.
        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, outbox, config);
            List<FixMessage> more = new OrderFeed(SENDER, 11, Instant.now()).next(6);
            long[] sequences = more.stream().mapToLong(seq::enqueue).toArray();
            seq.transmit(sequences[0]);   // 11
            seq.transmit(sequences[1]);   // 12
            seq.flush();
            publisher.disconnect();
        }
        assertEquals(16, outbox.lastSequence(), "the outbox recorded all six");

        // 3. A fresh client -- same name, no shared in-memory state -- recovers.
        Outbox reopened = Outbox.open(config.outboxFile());
        assertEquals(16, reopened.lastSequence(), "the outbox is durable across the crash");
        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, reopened, config);
            RecoveryReport report = new PublisherRecovery(publisher, config, seq).recover();

            assertEquals(RecoveryDecision.Action.REPUBLISH, report.decision().action());
            assertEquals(12, report.decision().resolvedL(), "AMPS held exactly 1..12 after the crash");
            assertEquals(4, report.messagesResent(), "only the four missing messages 13..16 are resent");
            assertTrue(report.verified(), "AMPS and the outbox agree afterwards");
        }

        // 4. The journal holds an unbroken 1..16, each exactly once.
        try (Client reader = connect()) {
            JournalScanResult scan = new JournalLastSequenceLocator(reader, config).scanFromEpoch(SENDER);
            assertEquals(16, scan.count(), "sixteen messages, no duplicates");
            assertEquals(1, scan.min().getAsLong());
            assertEquals(16, scan.max().getAsLong());
            assertTrue(scan.isContiguous(), () -> "no gap and no duplicate: " + scan);
        }
    }

    // ---- the subscriber sees the recovered stream as continuous -------------

    @Test
    @DisplayName("a bookmark subscriber sees an unbroken 8888 sequence across the recovery")
    void subscriberSeesNoGap() throws Exception {
        Outbox outbox = Outbox.open(config.outboxFile());
        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, outbox, config);
            seq.publishAll(new OrderFeed(SENDER, 1, Instant.now()).next(5));
            List<FixMessage> more = new OrderFeed(SENDER, 6, Instant.now()).next(4);
            long[] sequences = more.stream().mapToLong(seq::enqueue).toArray();
            seq.transmit(sequences[0]);   // 6, then "crash"
            seq.flush();
            publisher.disconnect();
        }
        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, Outbox.open(config.outboxFile()), config);
            new PublisherRecovery(publisher, config, seq).recover();
        }

        SequenceTracker tracker = new SequenceTracker();
        try (BookmarkSubscriber subscriber = new BookmarkSubscriber(config)) {
            SubscriberReport report = subscriber.consume(tracker);
            assertEquals(9, report.processed(), "all nine, once each");
            assertEquals(0, report.duplicatesSkipped());
            assertTrue(report.gaps().isEmpty(), () -> "no gaps: " + report.gaps());
            assertEquals(9, tracker.mark(SENDER));
        }
    }

    @Test
    @DisplayName("a subscriber resumes after its own restart and reprocesses nothing")
    void subscriberResumesFromItsBookmarkStore() throws Exception {
        Outbox outbox = Outbox.open(config.outboxFile());
        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, outbox, config);
            seq.publishAll(new OrderFeed(SENDER, 1, Instant.now()).next(4));
            seq.flush();
        }

        // First run consumes 1..4 and discards their bookmarks.
        SequenceTracker firstMarks = SequenceTracker.load(config.highWaterMarkFile());
        try (BookmarkSubscriber subscriber = new BookmarkSubscriber(config)) {
            assertEquals(4, subscriber.consume(firstMarks).processed());
        }

        // Publish 5..7.
        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, Outbox.open(config.outboxFile()), config);
            seq.publishAll(new OrderFeed(SENDER, 5, Instant.now()).next(3));
            seq.flush();
        }

        // A new subscriber instance -- same names, so the same durable stores --
        // resumes after 4 and sees only 5..7.
        SequenceTracker resumedMarks = SequenceTracker.load(config.highWaterMarkFile());
        assertEquals(4, resumedMarks.mark(SENDER), "the mark survived the restart");
        try (BookmarkSubscriber subscriber = new BookmarkSubscriber(config)) {
            SubscriberReport report = subscriber.consume(resumedMarks);
            assertEquals(3, report.processed(), "only the three new messages, not the whole topic");
            assertEquals(0, report.duplicatesSkipped());
            assertEquals(7, resumedMarks.mark(SENDER));
        }
    }

    // ---- the counter-example: a naive resend lands twice, and is caught -----

    @Test
    @DisplayName("resending below L duplicates in the journal; the scan and the subscriber catch it")
    void naiveResendIsDetectedNotAbsorbed() throws Exception {
        Outbox outbox = Outbox.open(config.outboxFile());
        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, outbox, config);
            seq.publishAll(new OrderFeed(SENDER, 1, Instant.now()).next(5));
            seq.flush();
            // The mistake the recovery exists to avoid: resend 4 and 5, which
            // AMPS already has. With no publish store the server cannot reject
            // them, so they land in the journal a second time.
            seq.republish(3, 5);
            seq.flush();
        }

        try (Client reader = connect()) {
            JournalScanResult scan = new JournalLastSequenceLocator(reader, config).scanFromEpoch(SENDER);
            assertTrue(scan.duplicates().contains(4L) && scan.duplicates().contains(5L),
                    () -> "the scan reports the resent numbers: " + scan);
            assertFalse(scan.isContiguous());
        }

        // The subscriber processes each 8888 once and skips the resends.
        SequenceTracker tracker = new SequenceTracker();
        try (BookmarkSubscriber subscriber = new BookmarkSubscriber(config)) {
            SubscriberReport report = subscriber.consume(tracker);
            assertEquals(5, report.processed(), "each distinct 8888 once");
            assertEquals(2, report.duplicatesSkipped(), "the two resent messages are dropped");
            assertEquals(5, tracker.mark(SENDER));
        }
    }

    // ---- recovery halts rather than reuse numbers when the outbox is lost ---

    @Test
    @DisplayName("recovery halts when AMPS is ahead of a lost outbox, rather than reuse numbers")
    void haltsWhenTheOutboxIsLost() throws Exception {
        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, Outbox.open(config.outboxFile()), config);
            seq.publishAll(new OrderFeed(SENDER, 1, Instant.now()).next(6));
            seq.flush();
        }

        // The outbox is lost (a different host, an ephemeral disk) but AMPS
        // still holds 1..6. Starting from an empty outbox, recovery must refuse.
        Files.deleteIfExists(config.outboxFile());
        Outbox emptyOutbox = Outbox.open(config.outboxFile());
        assertEquals(0, emptyOutbox.lastSequence());

        try (Client publisher = connect()) {
            SequencedPublisher seq = new SequencedPublisher(publisher, emptyOutbox, config);
            RecoveryReport report = new PublisherRecovery(publisher, config, seq).recover();
            assertEquals(RecoveryDecision.Action.HALT, report.decision().action(),
                    "AMPS ahead of the outbox must stop, not silently reuse 1..6");
            assertTrue(report.decision().haltReason().contains("reuse sequence numbers"));
        }
    }

    // ---- helpers ------------------------------------------------------------

    private Client connect() throws Exception {
        // A unique client name per connection, deliberately. The B1 recovery
        // keys entirely on tag 49 (the sender) and the outbox -- it uses no
        // publish store, so AMPS's own per-client-name sequence tracking is not
        // in play, and the client name is free to vary. Unique names also avoid
        // the NameInUse lag between the rapid reconnects these tests do. The
        // demo uses ONE stable name instead, because that is the production
        // choice and what a later publish store (B3) would require.
        Client client = new Client(config.publisherClientName() + "-" + System.nanoTime());
        try {
            client.connect(config.uri());
            client.logon(config.timeoutMillis());
        } catch (Exception e) {
            client.close();
            throw e;
        }
        return client;
    }

    private long lastInSow(Client client) throws Exception {
        return new SowLastSequenceLocator(client, config).locate(SENDER);
    }
}
