package com.demo.amps.connectors.amps;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.runtime.Command;
import com.demo.amps.connectors.runtime.MessageContext;
import com.demo.amps.connectors.runtime.OutboundRecord;
import com.demo.amps.connectors.source.InboundRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
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
}
