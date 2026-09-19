package com.demo.amps.connectors.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.KafkaSourceProperties;
import com.demo.amps.connectors.source.SourceRecord;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Kafka source against a {@link MockConsumer} -- no broker, no testcontainers.
 *
 * <p>What is worth asserting here is everything the connector decides for itself: that it
 * joins a consumer group rather than owning its own position, that a tombstone becomes a
 * {@code DELETE}, and above all <em>when</em> an offset is committed. The last one is the
 * framework's at-least-once contract spelled in Kafka, so most of this file is about it: a
 * record that the pipeline never acknowledged must leave the group's offset where it was.
 * The consumer's own behaviour is Kafka's to test.
 */
class KafkaRecordSourceTest {

    private static final String NAME = "orders-kafka";
    private static final String TOPIC = "orders.events";
    private static final String GROUP = "amps-connectors-orders";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    /** Where the mock topic's retained log starts, so "offset + 1" is not "1". */
    private static final long BEGINNING = 12L;

    /** Long enough for several poll cycles of the idling consumer below. */
    private static final Duration QUIET_POLLS = Duration.ofMillis(250);

    // ---- fixtures ------------------------------------------------------------------

    private static ConnectorProperties connector(KafkaSourceProperties.From from) {
        ConnectorProperties connector = TestConnectors.kafka(NAME, TOPIC, GROUP);
        KafkaSourceProperties kafka = connector.getSource().getKafka();
        kafka.setBootstrapServers("broker-1:9092");
        kafka.setFrom(from);
        kafka.setMaxPollRecords(250);
        kafka.setPollTimeout(Duration.ofMillis(20));
        // Production backs off for seconds; a test that waited that out would be a slow test.
        kafka.setReconnectDelay(Duration.ofMillis(50));
        return connector;
    }

    /**
     * A {@link MockConsumer} that idles instead of spinning.
     *
     * <p>{@code MockConsumer.poll} ignores its timeout and returns immediately, so the poll
     * loop under test would busy-wait -- and because every other {@code MockConsumer} method
     * is {@code synchronized}, a busy-wait can starve the {@code wakeup()} that
     * {@code close()} depends on. Sleeping OUTSIDE the monitor (before delegating) restores
     * the blocking poll the real consumer provides.
     */
    private static class IdlingMockConsumer extends MockConsumer<String, String> {

        IdlingMockConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public ConsumerRecords<String, String> poll(Duration timeout) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.poll(timeout);
        }
    }

    /**
     * A consumer whose asynchronous commits are taken and never confirmed -- exactly the
     * state a commit is in when a connector is asked to stop, or loses a partition, right
     * after issuing one. Synchronous commits are recorded separately, because
     * {@code MockConsumer} refuses to answer anything once it has been closed; and the
     * rebalance listener is captured, because {@code MockConsumer.rebalance} never calls it.
     */
    private static final class InFlightCommitMockConsumer extends IdlingMockConsumer {

        private final Map<TopicPartition, Long> syncCommits = new ConcurrentHashMap<>();
        private final AtomicInteger asyncCommits = new AtomicInteger();
        private volatile ConsumerRebalanceListener listener;

        @Override
        public void subscribe(Collection<String> topics, ConsumerRebalanceListener listener) {
            this.listener = listener;
            super.subscribe(topics, listener);
        }

        @Override
        public synchronized void commitAsync(
                Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback) {
            // Accepted, never acknowledged: no callback, so the source's committed floor
            // stays where it was and those offsets are still owed.
            asyncCommits.incrementAndGet();
        }

        @Override
        public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            offsets.forEach((partition, offset) -> syncCommits.put(partition, offset.offset()));
            super.commitSync(offsets);
        }
    }

    private static MockConsumer<String, String> mockConsumer() {
        return prepared(new IdlingMockConsumer());
    }

    /**
     * Beginning and end offsets are set before the source starts, because
     * {@code MockConsumer} resolves a fresh partition's position inside {@code poll()} and
     * throws when it has none -- which the source would report as a broken consumer.
     */
    private static <C extends MockConsumer<String, String>> C prepared(C consumer) {
        consumer.updateBeginningOffsets(Map.of(PARTITION, BEGINNING));
        consumer.updateEndOffsets(Map.of(PARTITION, BEGINNING));
        return consumer;
    }

    private static ConsumerRecord<String, String> record(long offset, String key, String value) {
        return new ConsumerRecord<>(TOPIC, 0, offset, key, value);
    }

    /**
     * Hand the consumer an assignment and some records, inside one poll.
     *
     * <p>{@code MockConsumer.rebalance} is how a subscribed mock consumer gets partitions (it
     * never joins a group), and it clears whatever records are queued -- so the assignment and
     * the records it carries belong in the same task.
     */
    @SafeVarargs
    private static void deliver(
            MockConsumer<String, String> consumer, ConsumerRecord<String, String>... records) {
        consumer.schedulePollTask(() -> {
            if (consumer.assignment().isEmpty()) {
                consumer.rebalance(List.of(PARTITION));
            }
            for (ConsumerRecord<String, String> record : records) {
                consumer.addRecord(record);
            }
        });
    }

    /** A source reading {@code consumer} instead of a broker, unstarted. */
    private static KafkaRecordSource source(
            ConnectorProperties connector, MockConsumer<String, String> consumer) {
        return new KafkaRecordSource(connector, () -> consumer);
    }

    private static void awaitConnected(KafkaRecordSource source) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(source::isConnected);
    }

    private static void awaitRecords(List<SourceRecord> received, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= count);
    }

    /** The group's committed offset for the one partition, or {@code null} for none. */
    private static Long committedOffset(MockConsumer<String, String> consumer) {
        OffsetAndMetadata offset = consumer.committed(Set.of(PARTITION)).get(PARTITION);
        return offset == null ? null : offset.offset();
    }

    private static void awaitCommit(MockConsumer<String, String> consumer, long offset) {
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> Objects.equals(committedOffset(consumer), offset));
    }

    /** Let several more polls run, for the assertions about what does NOT get committed. */
    private static void severalMorePolls() throws InterruptedException {
        Thread.sleep(QUIET_POLLS.toMillis());
    }

    private static boolean pollThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals(NAME + "-kafka") && thread.isAlive());
    }

    // ---- configuration --------------------------------------------------------------

    @Test
    @DisplayName("the consumer joins the configured group and never commits by itself")
    void consumerConfigJoinsTheGroupWithoutAutoCommit() {
        Map<String, Object> config =
                new KafkaRecordSource(connector(KafkaSourceProperties.From.EARLIEST), null)
                        .consumerConfig();

        assertThat(config)
                .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker-1:9092")
                // The group is the position: this is what makes a restart resume instead of
                // re-publishing the whole log into an AMPS topic that already holds it.
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, GROUP)
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                // Off: a background commit would acknowledge records the pipeline has not
                // flushed to AMPS yet.
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "250")
                .containsEntry(ConsumerConfig.CLIENT_ID_CONFIG, NAME)
                .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class.getName())
                .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class.getName());
    }

    @Test
    @DisplayName("from: LATEST only moves where a brand new group starts")
    void fromLatestSetsTheAutoOffsetReset() {
        Map<String, Object> config =
                new KafkaRecordSource(connector(KafkaSourceProperties.From.LATEST), null)
                        .consumerConfig();

        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    }

    @Test
    @DisplayName("passthrough properties are applied last, so an operator can override anything")
    void passthroughPropertiesWin() {
        ConnectorProperties connector = connector(KafkaSourceProperties.From.EARLIEST);
        connector.getSource().getKafka().setProperties(Map.of(
                "security.protocol", "SSL",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1"));

        Map<String, Object> config = new KafkaRecordSource(connector, null).consumerConfig();

        assertThat(config).containsEntry("security.protocol", "SSL")
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
    }

    // ---- records -------------------------------------------------------------------

    @Test
    @DisplayName("an upsert carries the message key, where it came from, and an acknowledgment")
    void deliversRecordsWithKeyAttributesAndAck() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        MockConsumer<String, String> consumer = mockConsumer();

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(received::add);
            awaitConnected(source);
            deliver(consumer,
                    record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"),
                    record(BEGINNING + 1, "ORD-2", "{\"orderId\":\"ORD-2\"}"));
            awaitRecords(received, 2);

            assertThat(received).extracting(SourceRecord::key).containsExactly("ORD-1", "ORD-2");
            assertThat(received).extracting(SourceRecord::data)
                    .containsExactly("{\"orderId\":\"ORD-1\"}", "{\"orderId\":\"ORD-2\"}");
            assertThat(received).extracting(SourceRecord::action)
                    .containsOnly(SourceRecord.Action.UPSERT);
            // Unlike TCP and Hazelcast, every record here can be acknowledged: an offset is
            // exactly the position the framework's at-least-once contract needs.
            assertThat(received).extracting(SourceRecord::ack).doesNotContainNull();
            assertThat(received.get(0).attributes())
                    .containsEntry("topic", TOPIC)
                    .containsEntry("partition", "0")
                    .containsEntry("offset", Long.toString(BEGINNING));
            assertThat(received.get(1).attributes())
                    .containsEntry("offset", Long.toString(BEGINNING + 1));
        }
    }

    @Test
    @DisplayName("a tombstone arrives as a DELETE carrying its key and an empty payload")
    void aNullValueBecomesADelete() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        MockConsumer<String, String> consumer = mockConsumer();

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(received::add);
            awaitConnected(source);
            deliver(consumer,
                    record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"),
                    record(BEGINNING + 1, "ORD-1", null));
            awaitRecords(received, 2);

            SourceRecord tombstone = received.get(1);
            assertThat(tombstone.action()).isEqualTo(SourceRecord.Action.DELETE);
            assertThat(tombstone.key()).isEqualTo("ORD-1");
            // Empty rather than null: the DELETE path still decodes the payload, and an
            // empty document is the quiet answer.
            assertThat(tombstone.data()).isEmpty();
            assertThat(tombstone.ack()).as("a removal is published too, so it is acked too")
                    .isNotNull();
        }
    }

    // ---- when an offset is committed --------------------------------------------------

    @Test
    @DisplayName("nothing is committed until the pipeline acknowledges the record")
    void offsetsAreCommittedOnlyForAcknowledgedRecords() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        MockConsumer<String, String> consumer = mockConsumer();

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(received::add);
            awaitConnected(source);
            // Two polls, one record each: the second poll commits nothing either, which is
            // the point -- a poll is not an acknowledgment.
            deliver(consumer, record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"));
            deliver(consumer, record(BEGINNING + 1, "ORD-2", "{\"orderId\":\"ORD-2\"}"));
            awaitRecords(received, 2);
            severalMorePolls();

            assertThat(committedOffset(consumer))
                    .as("read, handed to the pipeline, not flushed to AMPS yet")
                    .isNull();

            received.get(0).acknowledge();

            // offset + 1: a committed offset is where to resume, not where we were.
            awaitCommit(consumer, BEGINNING + 1);
            severalMorePolls();
            assertThat(committedOffset(consumer))
                    .as("the second record is still unacknowledged, so the group stays put")
                    .isEqualTo(BEGINNING + 1);
        }
    }

    @Test
    @DisplayName("acknowledgments that arrive out of order commit the highest offset, once")
    void outOfOrderAcknowledgmentsCommitTheHighestOffset() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        MockConsumer<String, String> consumer = mockConsumer();

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(received::add);
            awaitConnected(source);
            deliver(consumer,
                    record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"),
                    record(BEGINNING + 1, "ORD-2", "{\"orderId\":\"ORD-2\"}"),
                    record(BEGINNING + 2, "ORD-3", "{\"orderId\":\"ORD-3\"}"));
            awaitRecords(received, 3);

            // A batch can span several polls and is acknowledged as a whole, so the order
            // acknowledgments arrive in is not the order the records were read in.
            received.get(2).acknowledge();
            awaitCommit(consumer, BEGINNING + 3);

            received.get(0).acknowledge();
            severalMorePolls();

            // A committed offset must never walk backwards: doing so would re-publish
            // records AMPS already holds, and would do it on every restart.
            assertThat(committedOffset(consumer)).isEqualTo(BEGINNING + 3);
        }
    }

    @Test
    @DisplayName("a handler that throws leaves its record's offset uncommitted")
    void aFailingHandlerLeavesTheRecordUncommitted() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        MockConsumer<String, String> consumer = mockConsumer();

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(record -> {
                seen.incrementAndGet();
                throw new IllegalStateException("the pipeline rejected this record");
            });
            awaitConnected(source);
            deliver(consumer, record(BEGINNING, "ORD-1", "not-json"));

            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> seen.get() >= 1);
            severalMorePolls();

            // The record never reached AMPS, so its offset is not the group's position --
            // it comes back on the next start, which is what at-least-once means.
            assertThat(committedOffset(consumer)).isNull();
            assertThat(source.isConnected()).as("one bad record does not drop the feed").isTrue();
        }
    }

    @Test
    @DisplayName("a revoked partition is committed synchronously and then forgotten")
    void revokedPartitionsAreCommittedThenForgotten() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        InFlightCommitMockConsumer consumer = prepared(new InFlightCommitMockConsumer());

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(received::add);
            awaitConnected(source);
            deliver(consumer, record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"));
            awaitRecords(received, 1);
            received.get(0).acknowledge();
            // The async commit was taken and never confirmed, so the offset is still owed
            // when the group takes the partition away.
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> consumer.asyncCommits.get() > 0);

            // Kafka invokes a rebalance listener from inside poll(), on the poll thread; a
            // MockConsumer never does, so the test plays that part from a poll task.
            CountDownLatch revoked = new CountDownLatch(1);
            consumer.schedulePollTask(() -> {
                consumer.listener.onPartitionsRevoked(List.of(PARTITION));
                revoked.countDown();
            });
            assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue();

            // Synchronously, because an async commit would still be in flight when the new
            // owner starts reading records this connector has already published.
            assertThat(consumer.syncCommits).containsEntry(PARTITION, BEGINNING + 1);

            int afterRevoke = consumer.asyncCommits.get();
            severalMorePolls();
            // And then forgotten: a partition somebody else owns is not this connector's to
            // keep committing.
            assertThat(consumer.asyncCommits.get()).isEqualTo(afterRevoke);
        }
    }

    // ---- lifecycle -------------------------------------------------------------------

    @Test
    @DisplayName("close() commits what is still owed, synchronously, and stops the poll thread")
    void closeCommitsWhatIsOwedAndStopsThePollThread() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        InFlightCommitMockConsumer consumer = prepared(new InFlightCommitMockConsumer());
        KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer);
        source.start(received::add);
        awaitConnected(source);

        deliver(consumer, record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"));
        awaitRecords(received, 1);
        received.get(0).acknowledge();
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> consumer.asyncCommits.get() > 0);
        assertThat(consumer.syncCommits).as("the steady-state commit is the async one").isEmpty();

        long startedAt = System.nanoTime();
        source.close();
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(took).as("close() joins within its 5s budget").isLessThan(Duration.ofSeconds(5));
        // The async commit was never confirmed, so the offset was still owed -- and a clean
        // stop pays it, rather than re-publishing that record on the next start.
        assertThat(consumer.syncCommits).containsEntry(PARTITION, BEGINNING + 1);
        assertThat(source.isConnected()).isFalse();
        assertThat(consumer.closed()).as("the poll thread closes its own consumer").isTrue();
        assertThat(pollThreadAlive()).as("no connector thread is left behind").isFalse();
        // Idempotent, the way ConnectorManager calls it.
        source.close();
    }
}
