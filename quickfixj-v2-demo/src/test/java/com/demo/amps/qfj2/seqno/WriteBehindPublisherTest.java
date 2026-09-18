package com.demo.amps.qfj2.seqno;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.qfj2.support.InMemorySeqnoReplicator;
import java.time.Duration;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WriteBehindPublisherTest {

    private static final String SESSION = "FIX.4.2:A->B";

    private static SeqnoSnapshot snapshot(String session, int sender, int target, long revision) {
        return new SeqnoSnapshot(session, sender, target, Instant.parse("2026-09-17T08:00:00Z"), Instant.now(),
                "test", revision);
    }

    private static WriteBehindPublisher publisher(SeqnoReplicator replicator) {
        return new WriteBehindPublisher(replicator, Duration.ZERO, Duration.ofMillis(50), Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("idle, every offer is published, in order")
    void publishesEveryOfferWhenIdle() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        try (WriteBehindPublisher publisher = publisher(replicator)) {
            publisher.offer(snapshot("FIX.4.2:A->B", 2, 1, 1));
            assertThat(publisher.flush(Duration.ofSeconds(5))).isTrue();
            publisher.offer(snapshot("FIX.4.2:C->D", 5, 5, 1));
            assertThat(publisher.flush(Duration.ofSeconds(5))).isTrue();
            publisher.offer(snapshot("FIX.4.2:A->B", 3, 1, 2));
            assertThat(publisher.flush(Duration.ofSeconds(5))).isTrue();

            assertThat(replicator.history()).extracting(SeqnoSnapshot::sessionId)
                    .containsExactly("FIX.4.2:A->B", "FIX.4.2:C->D", "FIX.4.2:A->B");
            assertThat(replicator.latest("FIX.4.2:A->B")).get().extracting(SeqnoSnapshot::numbers).isEqualTo("3/1");
            assertThat(publisher.stats().published()).isEqualTo(3);
            assertThat(publisher.stats().coalesced()).isZero();
        }
    }

    @Test
    @DisplayName("while a publish is in flight, later offers for the session collapse to the newest")
    void coalescesToTheNewestWhileBusy() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        try (WriteBehindPublisher publisher = publisher(replicator)) {
            replicator.stall();
            publisher.offer(snapshot(SESSION, 1, 1, 1));
            // Let the thread pick that one up and block inside the replicator.
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> publisher.stats().pending() == 0);
            for (int i = 2; i <= 500; i++) {
                publisher.offer(snapshot(SESSION, i, 1, i));
            }
            replicator.open();
            assertThat(publisher.flush(Duration.ofSeconds(5))).isTrue();

            // The blocked one, then exactly one carrying the newest numbers.
            assertThat(replicator.history()).hasSize(2);
            assertThat(replicator.latest(SESSION)).get().extracting(SeqnoSnapshot::revision).isEqualTo(500L);
            assertThat(publisher.stats().offered()).isEqualTo(500);
            assertThat(publisher.stats().coalesced()).isEqualTo(498);
        }
    }

    @Test
    @DisplayName("a failed publish is retried until it goes through")
    void retriesAFailedPublish() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        try (WriteBehindPublisher publisher = publisher(replicator)) {
            replicator.failNextPublishes(2);
            publisher.offer(snapshot(SESSION, 7, 8, 1));
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> replicator.latest(SESSION).isPresent());
            assertThat(publisher.flush(Duration.ofSeconds(5))).isTrue();
            assertThat(publisher.stats().failures()).isEqualTo(2);
            assertThat(publisher.stats().published()).isEqualTo(1);
            assertThat(publisher.stats().lastError()).isNull();
        }
    }

    @Test
    @DisplayName("a newer offer supersedes a failed one instead of both being retried")
    void aNewerOfferSupersedesAFailedOne() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        // A long backoff, so the newer offer lands while the failed one is still waiting.
        try (WriteBehindPublisher publisher = new WriteBehindPublisher(replicator, Duration.ZERO,
                Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            replicator.failNextPublishes(1);
            publisher.offer(snapshot(SESSION, 7, 8, 1));
            Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(5))
                    .until(() -> publisher.stats().failures() == 1);
            publisher.offer(snapshot(SESSION, 9, 8, 2));
            assertThat(publisher.flush(Duration.ofSeconds(5))).isTrue();
            assertThat(replicator.history()).hasSize(1);
            assertThat(replicator.latest(SESSION)).get().extracting(SeqnoSnapshot::revision).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("flush reports a timeout while the replicator is stuck, then succeeds once it is not")
    void flushTimesOutWhileStalled() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        try (WriteBehindPublisher publisher = publisher(replicator)) {
            replicator.stall();
            publisher.offer(snapshot(SESSION, 1, 1, 1));
            assertThat(publisher.flush(Duration.ofMillis(200))).isFalse();
            replicator.open();
            assertThat(publisher.flush(Duration.ofSeconds(5))).isTrue();
            assertThat(replicator.publishedCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("close drains what is pending, and an offer after close is published inline")
    void closeDrainsAndLateOffersGoInline() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        WriteBehindPublisher publisher = publisher(replicator);
        publisher.offer(snapshot(SESSION, 4, 4, 1));
        publisher.close();
        assertThat(replicator.latest(SESSION)).get().extracting(SeqnoSnapshot::revision).isEqualTo(1L);

        // A stopping engine records its logout after the publisher is closed.
        publisher.offer(snapshot(SESSION, 5, 4, 2));
        assertThat(replicator.latest(SESSION)).get().extracting(SeqnoSnapshot::revision).isEqualTo(2L);
        assertThat(publisher.stats().published()).isEqualTo(2);
    }
}
