package com.demo.amps.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.ConnectionStateListener;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.ha.AmpsHaCluster.Instance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The failover, end to end, against two real AMPS instances.
 *
 * <p>One consumer and one publisher, both HA clients pointed at both
 * instances, both starting on the primary. The publisher numbers its messages
 * {@code 1..N} and the consumer keeps a {@link SequenceLedger}; the pair is
 * then taken apart in stages while publishing continues:
 *
 * <ol>
 *   <li>the primary is SIGKILLed mid-stream. Both clients move to the
 *       secondary; the publisher replays what was unacknowledged, the consumer
 *       resumes from its bookmark. Every message reaches the consumer once, in
 *       order, and the secondary's SOW holds all of them.</li>
 *   <li>the primary is started again on its own data and catches up from the
 *       secondary; then the secondary is SIGKILLed. Both clients move back to
 *       the primary, which now has everything -- including what was published
 *       to the secondary while the primary was down.</li>
 *   <li>a brand-new consumer replays the whole run from the survivor's journal
 *       and sees exactly the run, once.</li>
 *   <li>the consumer's own machine goes: the secondary is revived, the
 *       consumer process is closed and the primary -- the instance its
 *       bookmark was written to -- is SIGKILLed. More is published with no
 *       consumer running. Then a replacement with the same client name, no
 *       files and only the secondary to talk to starts from {@code recent},
 *       and resumes from the bookmark the SOW topic holds: not from the epoch,
 *       and missing nothing published in between.</li>
 * </ol>
 *
 * <p>The stages share the clients and the ledger (this class is one instance
 * for all its tests), because the second stage IS the continuation of the
 * first: the same publisher carrying on numbering, the same consumer carrying
 * on counting.
 *
 * <p>Skipped, with the reason, when {@code AMPS_IMAGE} is unset or no Docker
 * API is reachable -- see {@link AmpsHaCluster#unavailableReason()}.
 */
@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class FailoverIT {

    private static final Logger log = LoggerFactory.getLogger(FailoverIT.class);

    static final String TOPIC = "orders";
    static final String RUN = "it";
    static final String CONSUMER = "it-consumer";
    static final String RECOVERY_TOPIC = HaSettings.DEFAULT_RECOVERY_POINT_TOPIC;
    static final Duration INTERVAL = Duration.ofMillis(5);

    /** Stage one publishes 1..600 and kills the primary at 200. */
    static final int STAGE_ONE_END = 600;
    static final int KILL_PRIMARY_AT = 200;
    /** Stage two continues to 1000 and kills the secondary at 800. */
    static final int STAGE_TWO_END = 1000;
    static final int KILL_SECONDARY_AT = 800;
    /** Stage four publishes 1001..1200 after the consumer is gone and before its replacement starts. */
    static final int STAGE_FOUR_END = 1200;

    /**
     * How many of machine A's messages its replacement may receive again.
     * The recovery point a store writes is the conservative one: the bookmark
     * of the last persisted acknowledgment the server sent the subscription,
     * or the last message discarded if that is older, so it trails the real
     * position by at most a moment of traffic. Two seconds of it is allowed; a
     * replay from the epoch would be all thousand.
     */
    static final long REDELIVERY_TOLERANCE = Duration.ofSeconds(2).toMillis() / INTERVAL.toMillis();

    /**
     * Generous, because while one instance is down the survivor withholds
     * persisted acknowledgments until its scheduled action downgrades the
     * replication link (a few seconds in these configs), and everything here
     * runs under emulation.
     */
    static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(120);
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(120);
    static final Duration RESYNC_TIMEOUT = Duration.ofSeconds(180);

    private AmpsHaCluster cluster;
    private HaClients.Connected publishing;
    private HaClients.Connected consuming;
    private OrderPublisher publisher;
    private OrderConsumer consumer;
    private SequenceLedger ledger;

    @BeforeAll
    void startPairAndClients() throws Exception {
        Optional<String> unavailable = AmpsHaCluster.unavailableReason();
        assumeTrue(unavailable.isEmpty(), () -> "skipping: " + unavailable.get());

        cluster = AmpsHaCluster.start();
        // Give the pair a moment to bring the replication links up before the
        // first publish. Each instance logs "AMPS replication client session
        // logon" when its PEER has connected to its incoming transport, so both
        // having logged it means both directions are up. The wording is the
        // server's (5.3.5.135), so a miss is a warning, not a failure.
        for (Instance instance : Instance.values()) {
            if (!cluster.awaitLog(instance, AmpsHaCluster.PEER_LOGGED_ON, Duration.ofSeconds(60))) {
                log.warn("{} logged nothing matching {} in 60s; its replication lines:\n{}", instance,
                        AmpsHaCluster.PEER_LOGGED_ON, cluster.replicationLines(instance, 10));
            }
        }
        log.info("primary replication lines:\n{}", cluster.replicationLines(Instance.PRIMARY, 8));
        log.info("secondary replication lines:\n{}", cluster.replicationLines(Instance.SECONDARY, 8));

        Path stateDir = Path.of(System.getProperty("ha.workDir", "build/ha-it")).resolve("run-" + System.currentTimeMillis());
        Files.createDirectories(stateDir);
        consuming = HaClients.consumer(CONSUMER, cluster.uris(), stateDir, RECOVERY_TOPIC);
        consumer = new OrderConsumer(consuming.client(), TOPIC, CONSUMER);
        consumer.start(Client.Bookmarks.EPOCH);
        publishing = HaClients.publisher("it-publisher", cluster.uris(), stateDir);
        publisher = new OrderPublisher(publishing.client(), TOPIC, RUN);
        ledger = consumer.ledger(RUN);

        assertEquals(cluster.hostAndPort(Instance.PRIMARY), lastLogon(publishing), "publisher should start on the primary");
        assertEquals(cluster.hostAndPort(Instance.PRIMARY), lastLogon(consuming), "consumer should start on the primary");
        assertEquals(cluster.hostAndPort(Instance.PRIMARY), lastLogon(consuming.replicatedBookmarks().journal()),
                "the consumer's bookmark tracker should start on the primary");
    }

    @Test
    @Order(1)
    void killingThePrimaryMidStreamLosesNothing() throws Exception {
        publisher.publishRange(1, STAGE_ONE_END, INTERVAL, seq -> {
            if (seq == KILL_PRIMARY_AT) {
                cluster.kill(Instance.PRIMARY);
            }
        });
        log.info("stage one published through {}; {} unacknowledged at the end of the loop", STAGE_ONE_END,
                publisher.unpersisted());

        assertTrue(publisher.flush(FLUSH_TIMEOUT), () -> "the publish store did not drain: " + publisher.unpersisted()
                + " unacknowledged; secondary replication lines:\n" + cluster.replicationLines(Instance.SECONDARY, 10));
        awaitAll(ledger, STAGE_ONE_END);

        assertEquals(List.of(), ledger.gaps(STAGE_ONE_END), "messages lost across the failover");
        assertEquals(0, ledger.duplicates(), "duplicates reached the consumer: " + ledger.report(STAGE_ONE_END));
        assertEquals(0, ledger.outOfOrder(), "order not preserved: " + ledger.report(STAGE_ONE_END));

        assertTrue(publishing.journal().count(ConnectionStateListener.Disconnected) >= 1, "publisher never disconnected");
        assertTrue(consuming.journal().count(ConnectionStateListener.Disconnected) >= 1, "consumer never disconnected");
        assertEquals(cluster.hostAndPort(Instance.SECONDARY), lastLogon(publishing), "publisher should now be on the secondary");
        assertEquals(cluster.hostAndPort(Instance.SECONDARY), lastLogon(consuming), "consumer should now be on the secondary");
        assertEquals(cluster.hostAndPort(Instance.SECONDARY), lastLogon(consuming.replicatedBookmarks().journal()),
                "the consumer's bookmark tracker should now be on the secondary");

        assertEquals(STAGE_ONE_END, sowCount(cluster.uri(Instance.SECONDARY)), "the secondary's SOW is missing records");
        log.info("stage one: {}\npublisher journal:\n{}consumer journal:\n{}tracker journal:\n{}", ledger.report(STAGE_ONE_END),
                publishing.journal().describe(), consuming.journal().describe(),
                consuming.replicatedBookmarks().journal().describe());
    }

    @Test
    @Order(2)
    void revivedPrimaryCatchesUpAndTakesOverWhenTheSecondaryDies() throws Exception {
        assumeTrue(ledger.hasAll(STAGE_ONE_END), "stage one did not complete");

        cluster.revive(Instance.PRIMARY);
        // Caught up means: everything published so far, including what went to
        // the secondary while the primary was dead, is now in the primary's SOW.
        awaitSowCount(cluster.uri(Instance.PRIMARY), STAGE_ONE_END, RESYNC_TIMEOUT);
        log.info("primary caught up; its replication lines:\n{}", cluster.replicationLines(Instance.PRIMARY, 8));
        // And the secondary's link back to it, downgraded to async while the
        // primary was dead, should be upgraded to sync again by the scheduled
        // action once the primary has caught up. Waited for, because killing the
        // secondary while its link is still async would be the one failover the
        // documentation says not to do: a message it acknowledged alone might
        // not have reached the primary yet.
        assertTrue(cluster.awaitLog(Instance.SECONDARY, AmpsHaCluster.LINK_UPGRADED, Duration.ofSeconds(60)),
                () -> "the secondary did not upgrade its link back to sync; its replication lines:\n"
                        + cluster.replicationLines(Instance.SECONDARY, 10));
        log.info("secondary link upgraded back to sync; its replication lines:\n{}",
                cluster.replicationLines(Instance.SECONDARY, 8));

        publisher.publishRange(STAGE_ONE_END + 1, STAGE_TWO_END, INTERVAL, seq -> {
            if (seq == KILL_SECONDARY_AT) {
                cluster.kill(Instance.SECONDARY);
            }
        });
        log.info("stage two published through {}; {} unacknowledged at the end of the loop", STAGE_TWO_END,
                publisher.unpersisted());

        assertTrue(publisher.flush(FLUSH_TIMEOUT), () -> "the publish store did not drain: " + publisher.unpersisted()
                + " unacknowledged; primary replication lines:\n" + cluster.replicationLines(Instance.PRIMARY, 10));
        awaitAll(ledger, STAGE_TWO_END);

        assertEquals(List.of(), ledger.gaps(STAGE_TWO_END), "messages lost across the second failover");
        assertEquals(0, ledger.duplicates(), "duplicates reached the consumer: " + ledger.report(STAGE_TWO_END));
        assertEquals(0, ledger.outOfOrder(), "order not preserved: " + ledger.report(STAGE_TWO_END));

        assertEquals(cluster.hostAndPort(Instance.PRIMARY), lastLogon(publishing), "publisher should be back on the primary");
        assertEquals(cluster.hostAndPort(Instance.PRIMARY), lastLogon(consuming), "consumer should be back on the primary");
        assertEquals(cluster.hostAndPort(Instance.PRIMARY), lastLogon(consuming.replicatedBookmarks().journal()),
                "the consumer's bookmark tracker should be back on the primary");
        assertEquals(STAGE_TWO_END, sowCount(cluster.uri(Instance.PRIMARY)), "the primary's SOW is missing records");
        log.info("stage two: {}\npublisher journal:\n{}consumer journal:\n{}tracker journal:\n{}", ledger.report(STAGE_TWO_END),
                publishing.journal().describe(), consuming.journal().describe(),
                consuming.replicatedBookmarks().journal().describe());
    }

    @Test
    @Order(3)
    void aFreshConsumerReplaysTheWholeRunFromTheSurvivor() throws Exception {
        assumeTrue(ledger.hasAll(STAGE_TWO_END), "stage two did not complete");

        try (HaClients.Connected fresh = HaClients.consumer("it-fresh-consumer", cluster.uris(), null, RECOVERY_TOPIC)) {
            OrderConsumer replay = new OrderConsumer(fresh.client(), TOPIC, "it-fresh-consumer");
            replay.start(Client.Bookmarks.EPOCH);
            SequenceLedger replayed = replay.ledger(RUN);
            awaitAll(replayed, STAGE_TWO_END);
            assertEquals(List.of(), replayed.gaps(STAGE_TWO_END), "the survivor's journal is missing messages");
            assertEquals(0, replayed.duplicates(), "the survivor's journal holds duplicates: " + replayed.report(STAGE_TWO_END));
            assertEquals(0, replayed.outOfOrder(), "the survivor's journal is out of order: " + replayed.report(STAGE_TWO_END));
            log.info("fresh consumer via {}: {}", fresh.journal().lastLogonUri(), replayed.report(STAGE_TWO_END));
        }
    }

    @Test
    @Order(4)
    void aReplacementConsumerWithNoLocalStateResumesFromTheBookmarkInTheSow() throws Exception {
        assumeTrue(ledger.hasAll(STAGE_TWO_END), "stage two did not complete");

        // The secondary has been dead since stage two. Bring it back and let it
        // catch up, so that the recovery point the consumer wrote while on the
        // primary can be shown to have reached the secondary like any other
        // record -- which is what lets it outlive the primary.
        cluster.revive(Instance.SECONDARY);
        awaitSowCount(cluster.uri(Instance.SECONDARY), STAGE_TWO_END, RESYNC_TIMEOUT);
        assertTrue(cluster.awaitLog(Instance.PRIMARY, AmpsHaCluster.LINK_UPGRADED, Duration.ofSeconds(60)),
                () -> "the primary did not upgrade its link back to sync; its replication lines:\n"
                        + cluster.replicationLines(Instance.PRIMARY, 10));

        // Machine A goes away: the consumer process ends, and its memory and
        // its bookmark file go with it. What it leaves behind is one record.
        String lastProcessed = consumer.lastBookmark();
        consuming.close();
        consuming = null;
        RecoveryPoints.Entry onPrimary = recoveryPoint(cluster.uri(Instance.PRIMARY));
        assertNotNull(onPrimary, "the primary holds no recovery point for " + CONSUMER + " in " + RECOVERY_TOPIC);
        RecoveryPoints.Entry onSecondary = awaitRecoveryPoint(cluster.uri(Instance.SECONDARY),
                entry -> entry.bookmark().equals(onPrimary.bookmark()), RESYNC_TIMEOUT,
                "the secondary's copy of the recovery point to match the primary's " + onPrimary.bookmark());
        log.info("{}'s recovery point is {} on both instances; its last processed message was {}", CONSUMER,
                onPrimary.bookmark(), lastProcessed);

        // And so does the instance the record was written to.
        cluster.kill(Instance.PRIMARY);

        // Traffic continues while nobody is consuming it.
        publisher.publishRange(STAGE_TWO_END + 1, STAGE_FOUR_END, INTERVAL, seq -> { });
        assertTrue(publisher.flush(FLUSH_TIMEOUT), () -> "the publish store did not drain: " + publisher.unpersisted()
                + " unacknowledged; secondary replication lines:\n" + cluster.replicationLines(Instance.SECONDARY, 10));

        // Machine B: the same client name, nothing on disk, nothing in memory,
        // and only the secondary to talk to.
        try (HaClients.Connected replacement = HaClients.consumer(CONSUMER, cluster.uris(), null, RECOVERY_TOPIC)) {
            OrderConsumer resumed = new OrderConsumer(replacement.client(), TOPIC, CONSUMER);
            resumed.start(Client.Bookmarks.MOST_RECENT);
            assertEquals(cluster.hostAndPort(Instance.SECONDARY), lastLogon(replacement),
                    "the replacement should be on the secondary");
            assertTrue(onSecondary.bookmarks().stream().anyMatch(resumed.startedFrom()::contains),
                    () -> "the replacement subscribed from " + resumed.startedFrom() + ", not from the recovery point "
                            + onSecondary.bookmark());

            SequenceLedger resumedLedger = resumed.ledger(RUN);
            await(resumedLedger, l -> l.highest() >= STAGE_FOUR_END, STAGE_FOUR_END,
                    "the replacement did not receive message " + STAGE_FOUR_END);
            List<SequenceLedger.Gap> gaps = resumedLedger.gaps(STAGE_FOUR_END);
            assertEquals(1, gaps.size(), () -> "the replacement should be missing exactly what machine A processed: "
                    + resumedLedger.report(STAGE_FOUR_END));
            long redelivered = STAGE_TWO_END - gaps.get(0).to();
            assertTrue(redelivered >= 0, () -> "messages published while no consumer ran were lost: "
                    + resumedLedger.report(STAGE_FOUR_END));
            assertTrue(redelivered <= REDELIVERY_TOLERANCE, () -> redelivered + " messages machine A had processed were "
                    + "delivered again, more than the " + REDELIVERY_TOLERANCE + " a recovery point can trail by: "
                    + resumedLedger.report(STAGE_FOUR_END));
            assertEquals(0, resumedLedger.duplicates(), "duplicates reached the replacement: " + resumedLedger.report(STAGE_FOUR_END));
            assertEquals(0, resumedLedger.outOfOrder(), "order not preserved: " + resumedLedger.report(STAGE_FOUR_END));

            // And the record is now the replacement's to write.
            RecoveryPoints.Entry moved = awaitRecoveryPoint(cluster.uri(Instance.SECONDARY),
                    entry -> !entry.bookmark().equals(onSecondary.bookmark()), Duration.ofSeconds(30),
                    "the replacement to move the recovery point past " + onSecondary.bookmark());
            log.info("stage four: replacement resumed from {}, received {} of machine A's messages again, {}; "
                            + "recovery point now {}\nreplacement journal:\n{}tracker journal:\n{}",
                    resumed.startedFrom(), redelivered, resumedLedger.report(STAGE_FOUR_END), moved.bookmark(),
                    replacement.journal().describe(), replacement.replicatedBookmarks().journal().describe());
        }
    }

    @AfterAll
    void tearDown() {
        if (publishing != null) {
            publishing.close();
        }
        if (consuming != null) {
            consuming.close();
        }
        if (cluster != null) {
            cluster.close();
        }
    }

    private static String lastLogon(HaClients.Connected connected) {
        return lastLogon(connected.journal());
    }

    private static String lastLogon(ConnectionJournal journal) {
        String uri = journal.lastLogonUri();
        return uri == null ? null : ConnectionJournal.hostAndPort(uri);
    }

    private void awaitAll(SequenceLedger target, long expected) throws InterruptedException {
        await(target, l -> l.hasAll(expected), expected, "the consumer did not receive everything");
    }

    private void await(SequenceLedger target, Predicate<SequenceLedger> done, long expected, String failure)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(DRAIN_TIMEOUT);
        Instant lastStatus = Instant.now();
        while (!done.test(target)) {
            if (Instant.now().isAfter(deadline)) {
                fail(failure + " within " + DRAIN_TIMEOUT + ": " + target.report(expected)
                        + "\nprimary tail:\n" + AmpsHaCluster.tail(cluster.logs(Instance.PRIMARY), 20)
                        + "\nsecondary tail:\n" + AmpsHaCluster.tail(cluster.logs(Instance.SECONDARY), 20));
            }
            if (Duration.between(lastStatus, Instant.now()).compareTo(Duration.ofSeconds(5)) >= 0) {
                lastStatus = Instant.now();
                log.info("waiting for the consumer: {}", target.report(expected));
            }
            Thread.sleep(100);
        }
    }

    private void awaitSowCount(String uri, long expected, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        long count = -1;
        while (Instant.now().isBefore(deadline)) {
            count = sowCount(uri);
            if (count >= expected) {
                log.info("{} holds {} records of run '{}'", uri, count, RUN);
                return;
            }
            Thread.sleep(1000);
        }
        fail(uri + " holds " + count + " records of run '" + RUN + "', expected " + expected + ", after " + timeout);
    }

    /** How many records of this run a plain, throwaway client finds in the SOW at {@code uri}. */
    private static long sowCount(String uri) throws Exception {
        Client client = new Client("it-sow-" + System.nanoTime());
        try {
            client.connect(uri);
            client.logon(10_000);
            long count = 0;
            try (MessageStream stream = client.sow(TOPIC, "/run = '" + RUN + "'")) {
                for (Message message : stream) {
                    if (message.getCommand() == Message.Command.SOW) {
                        count++;
                    }
                }
            }
            return count;
        } finally {
            client.close();
        }
    }

    /** The consumer's recovery point as the instance at {@code uri} holds it, or {@code null}. */
    private static RecoveryPoints.Entry recoveryPoint(String uri) throws Exception {
        return RecoveryPoints.read(uri, RECOVERY_TOPIC, CONSUMER).stream()
                .filter(entry -> entry.subId().equals(CONSUMER))
                .findFirst()
                .orElse(null);
    }

    /** The consumer's recovery point at {@code uri} once it satisfies {@code condition}, or a failure. */
    private static RecoveryPoints.Entry awaitRecoveryPoint(String uri, Predicate<RecoveryPoints.Entry> condition,
                                                           Duration timeout, String what) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        RecoveryPoints.Entry last = null;
        while (Instant.now().isBefore(deadline)) {
            last = recoveryPoint(uri);
            if (last != null && condition.test(last)) {
                return last;
            }
            Thread.sleep(500);
        }
        return fail("waited " + timeout + " for " + what + " at " + uri + "; last seen: " + last);
    }
}
