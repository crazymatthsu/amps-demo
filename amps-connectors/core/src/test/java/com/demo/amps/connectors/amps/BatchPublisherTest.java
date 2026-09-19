package com.demo.amps.connectors.amps;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.runtime.Command;
import com.demo.amps.connectors.runtime.PublishRequest;
import com.demo.amps.connectors.source.SourceRecord;
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

    private static SourceRecord acking(AtomicInteger acks) {
        return SourceRecord.of("{}", "K-1").withAck(acks::incrementAndGet);
    }

    private static PublishRequest publish(String data, SourceRecord record) {
        return PublishRequest.publish("sow/orders", Command.PUBLISH, data, "K-1", record);
    }

    @Test
    @DisplayName("the commands come out in the order the records arrived")
    void preservesOrderAcrossTheUpsertDeleteBoundary() {
        SourceRecord record = SourceRecord.of("{}");
        batches.publish(List.of(
                publish("{\"n\":1}", record),
                PublishRequest.deleteByKey("sow/orders", "K-1", record),
                publish("{\"n\":2}", record),
                PublishRequest.deleteByFilter("sow/orders", "/id = 'K-1'", record)));

        assertThat(publisher.calls()).extracting(RecordingAmpsPublisher.Call::kind)
                .containsExactly("publish", "sow_delete_by_key", "publish",
                        "sow_delete_by_filter");
    }

    @Test
    @DisplayName("a batch of many publishes makes exactly one flush")
    void flushesOncePerBatch() {
        List<PublishRequest> batch = new ArrayList<>();
        SourceRecord record = SourceRecord.of("{}");
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
    void acknowledgesEveryRecordAfterASuccessfulFlush() {
        AtomicInteger acks = new AtomicInteger();
        batches.publish(List.of(
                publish("{\"n\":1}", acking(acks)),
                publish("{\"n\":2}", acking(acks)),
                publish("{\"n\":3}", acking(acks))));
        assertThat(acks.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("a failed flush acknowledges nothing, so the sources re-read the batch")
    void acknowledgesNothingWhenTheFlushFails() {
        AtomicInteger acks = new AtomicInteger();
        publisher.failFlushes(1);
        batches.publish(List.of(publish("{\"n\":1}", acking(acks))));

        assertThat(acks.get()).isZero();
        assertThat(batches.failedBatches()).isEqualTo(1);
        assertThat(batches.publishedMessages()).isZero();
        // The publishes still happened: the client's publish store is what replays them.
        assertThat(publisher.calls("publish")).hasSize(1);
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
            public void publish(String topic, String data, String sowKey) {
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
        SourceRecord record = SourceRecord.of("{}");
        batches.publish(List.of(
                PublishRequest.publish("t", Command.PUBLISH, "{\"a\":1}", "K-1", record),
                PublishRequest.publish("t", Command.DELTA_PUBLISH, "{\"a\":2}", "K-1", record)));

        assertThat(publisher.calls()).containsExactly(
                new RecordingAmpsPublisher.Call("publish", "t", "{\"a\":1}", "K-1"),
                new RecordingAmpsPublisher.Call("delta_publish", "t", "{\"a\":2}", "K-1"));
    }
}
