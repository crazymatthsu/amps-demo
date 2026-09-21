package com.demo.amps.connectors.amps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crankuptheamps.client.Message;
import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.config.AmpsTargetProperties;
import com.demo.amps.connectors.runtime.Command;
import com.demo.amps.connectors.runtime.MessageContext;
import com.demo.amps.connectors.runtime.OutboundRecord;
import com.demo.amps.connectors.source.Acknowledger;
import com.demo.amps.connectors.source.InboundRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BatchPublisherTest {

    private final RecordingAmpsPublisher publisher = new RecordingAmpsPublisher();
    private final BatchPublisher batches =
            new BatchPublisher(publisher, "orders", Duration.ofSeconds(1));

    private static InboundRecord acking(AtomicInteger acks) {
        return InboundRecord.of("{}", "K-1").withAck(seqno -> acks.incrementAndGet());
    }

    private static MessageContext publish(String data, InboundRecord record) {
        return MessageContext.of(record, OutboundRecord.publish(
                "sow/orders", Command.PUBLISH, data, PayloadType.UNSET, "K-1"));
    }

    @Test
    @DisplayName("the batch publisher registers its tracker as the publisher's listener, in both modes")
    void registersTheTrackerAsTheListener() {
        assertThat(publisher.listener()).isSameAs(batches.tracker());
        assertThat(batches.ackMode()).isEqualTo(AmpsTargetProperties.AckMode.FLUSH);
        assertThat(batches.pending()).isZero();

        // A FLUSH-mode publisher still hears about a write the server refused: counted,
        // and nothing else changes.
        publisher.failWrite(99, Message.Reason.BadSowKey);
        assertThat(batches.rejectedWrites()).isEqualTo(1);

        assertThatThrownBy(() -> new BatchPublisher(publisher, "orders", Duration.ofSeconds(1),
                AmpsTargetProperties.AckMode.PERSISTED, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-pending");
    }

    @Test
    @DisplayName("the commands come out in the order the records arrived")
    void preservesOrderAcrossTheUpsertDeleteBoundary() {
        InboundRecord record = InboundRecord.of("{}");
        batches.publish(List.of(
                publish("{\"n\":1}", record),
                MessageContext.of(record,
                        OutboundRecord.deleteByKey("sow/orders", PayloadType.UNSET, "K-1")),
                publish("{\"n\":2}", record),
                MessageContext.of(record,
                        OutboundRecord.deleteByFilter("sow/orders", PayloadType.UNSET, "/id = 'K-1'"))));

        assertThat(publisher.calls()).extracting(RecordingAmpsPublisher.Call::kind)
                .containsExactly("publish", "sow_delete_by_key", "publish",
                        "sow_delete_by_filter");
    }

    @Test
    @DisplayName("a batch of many publishes makes exactly one flush")
    void flushesOncePerBatch() {
        List<MessageContext> batch = new ArrayList<>();
        InboundRecord record = InboundRecord.of("{}");
        for (int i = 0; i < 100; i++) {
            batch.add(publish("{\"n\":" + i + "}", record));
        }
        batches.publish(batch);

        assertThat(publisher.calls("publish")).hasSize(100);
        assertThat(publisher.flushCount()).isEqualTo(1);
        assertThat(batches.publishedMessages()).isEqualTo(100);
        assertThat(batches.publishedBatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("every context is given the AMPS sequence its command was assigned, in order")
    void assignsTheOutSideSequencePerContext() {
        AtomicInteger acks = new AtomicInteger();
        InboundRecord record = InboundRecord.of("{}");
        List<MessageContext> batch = List.of(
                publish("{\"n\":1}", acking(acks)),
                MessageContext.of(record,
                        OutboundRecord.deleteByKey("sow/orders", PayloadType.UNSET, "K-1")),
                publish("{\"n\":3}", acking(acks)),
                MessageContext.of(record,
                        OutboundRecord.deleteByFilter("sow/orders", PayloadType.UNSET, "/id = 'K-1'")));
        assertThat(batch).extracting(MessageContext::dataOutSeqno).containsOnly(0L);

        batches.publish(batch);

        assertThat(batch).extracting(MessageContext::dataOutSeqno)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(publisher.lastSequence()).isEqualTo(4);

        // The next batch carries on from where the store left off.
        List<MessageContext> next = List.of(publish("{\"n\":5}", acking(acks)));
        batches.publish(next);
        assertThat(next.get(0).dataOutSeqno()).isEqualTo(5);
    }

    @Test
    @DisplayName("a publisher without a store answers 0, and the context keeps 0")
    void leavesTheSequenceAloneWhenThePublisherAssignsNone() {
        BatchPublisher storeless = new BatchPublisher(new RecordingAmpsPublisher() {
            @Override
            public long publish(String topic, Object data, String sowKey) {
                super.publish(topic, data, sowKey);
                return 0;
            }
        }, "orders", Duration.ofSeconds(1));
        AtomicInteger acks = new AtomicInteger();
        MessageContext context = publish("{\"n\":1}", acking(acks));
        storeless.publish(List.of(context));
        assertThat(context.dataOutSeqno()).isZero();
        assertThat(acks.get()).as("acknowledged all the same: the flush is what counts")
                .isEqualTo(1);
    }

    @Test
    void acknowledgesEveryRecordAfterASuccessfulFlush() {
        AtomicInteger acks = new AtomicInteger();
        batches.publish(List.of(
                publish("{\"n\":1}", acking(acks)),
                publish("{\"n\":2}", acking(acks)),
                publish("{\"n\":3}", acking(acks))));
        assertThat(acks.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("acknowledgment goes to the stream with the record's own position, after the flush")
    void acknowledgesWithTheInSidePosition() {
        List<Long> acked = new ArrayList<>();
        List<Integer> flushesAtAck = new ArrayList<>();
        InboundRecord first = InboundRecord.of("{}", "K-1").withSeqno(10)
                .withAck(seqno -> {
                    acked.add(seqno);
                    flushesAtAck.add(publisher.flushCount());
                });
        InboundRecord second = first.withSeqno(11);
        batches.publish(List.of(publish("{\"n\":1}", first), publish("{\"n\":2}", second)));
        assertThat(acked).containsExactly(10L, 11L);
        assertThat(flushesAtAck).as("every ack came after the one flush").containsOnly(1);
    }

    @Test
    @DisplayName("a failed flush acknowledges nothing, so the sources re-read the batch")
    void acknowledgesNothingWhenTheFlushFails() {
        AtomicInteger acks = new AtomicInteger();
        publisher.failFlushes(1);
        MessageContext context = publish("{\"n\":1}", acking(acks));
        batches.publish(List.of(context));

        assertThat(acks.get()).isZero();
        assertThat(batches.failedBatches()).isEqualTo(1);
        assertThat(batches.publishedMessages()).isZero();
        // The publishes still happened: the client's publish store is what replays them.
        assertThat(publisher.calls("publish")).hasSize(1);
        // ...and the sequence the store assigned is known, flush or no flush.
        assertThat(context.dataOutSeqno()).isEqualTo(1);
    }

    @Test
    @DisplayName("the connector recovers on the next batch")
    void recoversAfterAFailedFlush() {
        AtomicInteger acks = new AtomicInteger();
        publisher.failFlushes(1);
        batches.publish(List.of(publish("{\"n\":1}", acking(acks))));
        batches.publish(List.of(publish("{\"n\":2}", acking(acks))));

        assertThat(acks.get()).isEqualTo(1);
        assertThat(batches.failedBatches()).isEqualTo(1);
        assertThat(batches.publishedBatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("nothing escapes: a timer-triggered release must never kill the scheduler")
    void swallowsAnExceptionFromThePublisher() {
        AtomicInteger acks = new AtomicInteger();
        BatchPublisher throwing = new BatchPublisher(new RecordingAmpsPublisher() {
            @Override
            public long publish(String topic, Object data, String sowKey) {
                throw new IllegalStateException("not connected");
            }
        }, "orders", Duration.ofSeconds(1));

        throwing.publish(List.of(publish("{\"n\":1}", acking(acks))));
        assertThat(acks.get()).isZero();
        assertThat(throwing.failedBatches()).isEqualTo(1);
    }

    @Test
    void anEmptyBatchDoesNothingAtAll() {
        batches.publish(List.of());
        batches.publish(null);
        assertThat(publisher.flushCount()).isZero();
        assertThat(batches.publishedBatches()).isZero();
        assertThat(batches.failedBatches()).isZero();
    }

    @Test
    void routesEachCommandToItsCall() {
        InboundRecord record = InboundRecord.of("{}");
        byte[] bytes = {1, 2, 3};
        batches.publish(List.of(
                MessageContext.of(record, OutboundRecord.publish(
                        "t", Command.PUBLISH, "{\"a\":1}", PayloadType.UNSET, "K-1")),
                MessageContext.of(record, OutboundRecord.publish(
                        "t", Command.DELTA_PUBLISH, "{\"a\":2}", PayloadType.UNSET, "K-1")),
                MessageContext.of(record, OutboundRecord.publish(
                        "t", Command.PUBLISH, bytes, PayloadType.of(100, 1), null))));

        assertThat(publisher.calls()).containsExactly(
                new RecordingAmpsPublisher.Call("publish", "t", "{\"a\":1}", "K-1"),
                new RecordingAmpsPublisher.Call("delta_publish", "t", "{\"a\":2}", "K-1"),
                new RecordingAmpsPublisher.Call("publish", "t", bytes, null));
        assertThat(publisher.calls().get(2).data()).as("bytes go out as they are").isSameAs(bytes);
    }

    /**
     * {@code ack-mode: PERSISTED}: the publisher's acks are under the test's control, so
     * nothing is acknowledged until the test says the server persisted it.
     */
    @Nested
    @DisplayName("in PERSISTED mode")
    class Persisted {

        private final RecordingAmpsPublisher publisher =
                new RecordingAmpsPublisher().manualPersistedAcks(true);
        private final BatchPublisher batches = new BatchPublisher(
                publisher, "orders", Duration.ofSeconds(1),
                AmpsTargetProperties.AckMode.PERSISTED, 4);

        /** Records at consecutive positions on one stream that writes down every ack. */
        private final List<String> acks = new ArrayList<>();
        private final Acknowledger stream = new Acknowledger() {
            @Override
            public void ack(long seqno) {
                acks.add("ack(" + seqno + ")");
            }

            @Override
            public void ackBatch(long fromSeqno, long toSeqno) {
                acks.add("batch(" + fromSeqno + ".." + toSeqno + ")");
            }
        };

        private MessageContext record(long inSeqno) {
            return publish("{\"n\":" + inSeqno + "}",
                    InboundRecord.of("{}", "K-" + inSeqno).withSeqno(inSeqno).withAck(stream));
        }

        private List<MessageContext> records(long from, long to) {
            List<MessageContext> batch = new ArrayList<>();
            for (long i = from; i <= to; i++) {
                batch.add(record(i));
            }
            return batch;
        }

        @Test
        @DisplayName("a batch is issued without a flush, and nothing is acknowledged until the acks arrive")
        void issuesWithoutFlushingAndWaitsForTheAcks() {
            List<MessageContext> batch = records(1, 3);
            batches.publish(batch);

            assertThat(publisher.calls("publish")).hasSize(3);
            assertThat(publisher.flushCount()).as("no flush per batch").isZero();
            assertThat(batch).extracting(MessageContext::dataOutSeqno).containsExactly(1L, 2L, 3L);
            assertThat(acks).isEmpty();
            assertThat(batches.pending()).isEqualTo(3);
            assertThat(batches.publishedBatches()).isEqualTo(1);
            assertThat(batches.publishedMessages()).as("published = persisted").isZero();

            // The server persisted the first two: acknowledged as one range, on this thread
            // -- which stands in for the client's receive thread.
            publisher.persistUpTo(2);
            assertThat(acks).containsExactly("batch(1..2)");
            assertThat(batches.pending()).isEqualTo(1);
            assertThat(batches.publishedMessages()).isEqualTo(2);

            publisher.persistUpTo(3);
            assertThat(acks).containsExactly("batch(1..2)", "ack(3)");
            assertThat(batches.pending()).isZero();
            assertThat(batches.publishedMessages()).isEqualTo(3);
            assertThat(publisher.flushCount()).isZero();
        }

        @Test
        @DisplayName("more than max-pending records waiting forces one flush on the publishing thread")
        void flushesWhenMoreThanMaxPendingAreWaiting() {
            batches.publish(records(1, 4));
            assertThat(publisher.flushCount()).as("4 pending is not over 4").isZero();
            assertThat(batches.backpressureFlushes()).isZero();

            // The fifth crosses the line. The recording publisher's flush persists nothing
            // by itself here (manual acks), so the records stay pending, but the flush
            // happened and was counted.
            batches.publish(records(5, 5));
            assertThat(publisher.flushCount()).isEqualTo(1);
            assertThat(batches.backpressureFlushes()).isEqualTo(1);
            assertThat(batches.flushTimeouts()).isZero();
            assertThat(batches.pending()).isEqualTo(5);
            assertThat(acks).isEmpty();

            // A flush that times out is counted, and the records still stay pending.
            publisher.failFlushes(1);
            batches.publish(records(6, 6));
            assertThat(publisher.flushCount()).isEqualTo(2);
            assertThat(batches.flushTimeouts()).isEqualTo(1);
            assertThat(batches.pending()).isEqualTo(6);
            assertThat(batches.failedBatches()).as("not a failed batch: it was issued").isZero();

            // The acks catch up; the next batch is under the line again and does not flush.
            publisher.persistUpTo(6);
            assertThat(acks).containsExactly("batch(1..6)");
            assertThat(batches.pending()).isZero();
            batches.publish(records(7, 8));
            assertThat(publisher.flushCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("a flush that itself persists everything leaves nothing pending")
        void aRealFlushDrainsThePending() {
            publisher.manualPersistedAcks(false);
            batches.publish(records(1, 5));
            assertThat(publisher.flushCount()).isEqualTo(1);
            assertThat(batches.pending()).isZero();
            assertThat(acks).containsExactly("batch(1..5)");
            assertThat(batches.publishedMessages()).isEqualTo(5);
        }

        @Test
        @DisplayName("drain flushes once and reports what is still pending; nothing pending means no flush")
        void drainFlushesOnceForWhatIsPending() {
            batches.drain(Duration.ofSeconds(1));
            assertThat(publisher.flushCount()).as("nothing pending, nothing to wait for").isZero();

            batches.publish(records(1, 2));
            batches.drain(Duration.ofSeconds(1));
            assertThat(publisher.flushCount()).isEqualTo(1);
            assertThat(batches.pending()).as("the acks did not come: they stay pending, and are logged")
                    .isEqualTo(2);

            publisher.manualPersistedAcks(false);
            batches.drain(Duration.ofSeconds(1));
            assertThat(publisher.flushCount()).isEqualTo(2);
            assertThat(batches.pending()).isZero();
            assertThat(acks).containsExactly("batch(1..2)");

            // FLUSH mode has nothing to drain.
            batches.drain(Duration.ofSeconds(1));
            BatchPublisherTest.this.batches.drain(Duration.ofSeconds(1));
            assertThat(publisher.flushCount()).isEqualTo(2);
            assertThat(BatchPublisherTest.this.publisher.flushCount()).isZero();
        }

        @Test
        @DisplayName("a write the server refuses is acknowledged, removed and counted; a duplicate is not a rejection")
        void aFailedWriteAcknowledgesAndCounts() {
            batches.publish(records(1, 3));

            publisher.failWrite(2, Message.Reason.NotEntitled);
            assertThat(acks).containsExactly("ack(2)");
            assertThat(batches.pending()).isEqualTo(2);
            assertThat(batches.rejectedWrites()).isEqualTo(1);
            assertThat(batches.tracker().duplicates()).isZero();

            publisher.failWrite(1, Message.Reason.Duplicate);
            assertThat(acks).containsExactly("ack(2)", "ack(1)");
            assertThat(batches.rejectedWrites()).isEqualTo(1);
            assertThat(batches.tracker().duplicates()).isEqualTo(1);

            publisher.persistUpTo(3);
            assertThat(acks).containsExactly("ack(2)", "ack(1)", "ack(3)");
            assertThat(batches.pending()).isZero();
            assertThat(batches.publishedMessages()).as("only what was persisted").isEqualTo(1);
        }

        @Test
        @DisplayName("a command that throws ends the batch: what was issued is parked, the rest is left to re-read")
        void aThrowingCommandEndsTheBatch() {
            RecordingAmpsPublisher flaky = new RecordingAmpsPublisher() {
                private int calls;

                @Override
                public long publish(String topic, Object data, String sowKey) {
                    if (++calls == 3) {
                        throw new IllegalStateException("not connected");
                    }
                    return super.publish(topic, data, sowKey);
                }
            }.manualPersistedAcks(true);
            BatchPublisher publisherOfFlaky = new BatchPublisher(flaky, "orders",
                    Duration.ofSeconds(1), AmpsTargetProperties.AckMode.PERSISTED, 100);

            publisherOfFlaky.publish(records(1, 5));

            assertThat(flaky.calls("publish")).hasSize(2);
            assertThat(publisherOfFlaky.failedBatches()).isEqualTo(1);
            assertThat(publisherOfFlaky.publishedBatches()).isZero();
            assertThat(publisherOfFlaky.pending()).isEqualTo(2);
            flaky.persistUpTo(2);
            assertThat(acks).containsExactly("batch(1..2)");
            assertThat(publisherOfFlaky.publishedMessages()).isEqualTo(2);
        }

        @Test
        @DisplayName("a publisher without a store answers 0, and the record is acknowledged on the publish alone")
        void acknowledgesAnUnsequencedRecordAtOnce() {
            RecordingAmpsPublisher storeless = new RecordingAmpsPublisher() {
                @Override
                public long publish(String topic, Object data, String sowKey) {
                    super.publish(topic, data, sowKey);
                    return 0;
                }
            }.manualPersistedAcks(true);
            BatchPublisher publisherOfStoreless = new BatchPublisher(storeless, "orders",
                    Duration.ofSeconds(1), AmpsTargetProperties.AckMode.PERSISTED, 100);

            publisherOfStoreless.publish(records(1, 2));

            assertThat(acks).containsExactly("ack(1)", "ack(2)");
            assertThat(publisherOfStoreless.pending()).isZero();
            assertThat(publisherOfStoreless.tracker().unsequenced()).isEqualTo(2);
        }
    }
}
