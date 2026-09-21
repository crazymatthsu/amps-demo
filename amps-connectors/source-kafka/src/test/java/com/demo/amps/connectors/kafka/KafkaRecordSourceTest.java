package com.demo.amps.connectors.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.KafkaSourceProperties;
import com.demo.amps.connectors.source.Acknowledger;
import com.demo.amps.connectors.source.InboundRecord;
import java.nio.charset.StandardCharsets;
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
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Kafka source against a {@link MockConsumer} -- no broker, no testcontainers.
 *
 * <p>What is worth asserting here is everything the connector decides for itself: that it
 * joins a consumer group rather than owning its own position, that a tombstone becomes a
 * {@code DELETE}, that a typed topic is read as bytes under its type, and above all
 * <em>when</em> an offset is committed. The last one is the framework's at-least-once
 * contract spelled in Kafka, so most of this file is about it: a record that the pipeline
 * never acknowledged must leave the group's offset where it was, and the acknowledger every
 * record carries is its partition's, called with an offset, so that acknowledging
 * cumulatively -- one call for a run of records -- commits exactly what a per-record
 * acknowledgment would. The consumer's own behaviour is Kafka's to test.
 */
class KafkaRecordSourceTest {

    private static final String NAME = "orders-kafka";
    private static final String TOPIC = "orders.events";
    private static final String GROUP = "amps-connectors-orders";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final TopicPartition OTHER_PARTITION = new TopicPartition(TOPIC, 1);

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
    private static class IdlingMockConsumer<V> extends MockConsumer<String, V> {

        IdlingMockConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public ConsumerRecords<String, V> poll(Duration timeout) {
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
    private static final class InFlightCommitMockConsumer extends IdlingMockConsumer<String> {

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
        return prepared(new IdlingMockConsumer<>());
    }

    /**
     * Beginning and end offsets are set before the source starts, because
     * {@code MockConsumer} resolves a fresh partition's position inside {@code poll()} and
     * throws when it has none -- which the source would report as a broken consumer.
     */
    private static <C extends MockConsumer<String, ?>> C prepared(C consumer) {
        consumer.updateBeginningOffsets(Map.of(PARTITION, BEGINNING, OTHER_PARTITION, 0L));
        consumer.updateEndOffsets(Map.of(PARTITION, BEGINNING, OTHER_PARTITION, 0L));
        return consumer;
    }

    private static ConsumerRecord<String, String> record(long offset, String key, String value) {
        return new ConsumerRecord<>(TOPIC, 0, offset, key, value);
    }

    private static ConsumerRecord<String, byte[]> bytes(long offset, String key, String value) {
        return new ConsumerRecord<>(TOPIC, 0, offset, key,
                value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hand the consumer an assignment and some records, inside one poll.
     *
     * <p>{@code MockConsumer.rebalance} is how a subscribed mock consumer gets partitions (it
     * never joins a group), and it clears whatever records are queued -- so the assignment and
     * the records it carries belong in the same task.
     */
    @SafeVarargs
    private static <V> void deliver(
            MockConsumer<String, V> consumer, ConsumerRecord<String, V>... records) {
        consumer.schedulePollTask(() -> {
            if (consumer.assignment().isEmpty()) {
                consumer.rebalance(List.of(PARTITION));
            }
            for (ConsumerRecord<String, V> record : records) {
                consumer.addRecord(record);
            }
        });
    }

    /** A source reading {@code consumer} instead of a broker, unstarted. */
    private static KafkaRecordSource source(
            ConnectorProperties connector, MockConsumer<String, ?> consumer) {
        return new KafkaRecordSource(connector, () -> consumer);
    }

    private static void awaitConnected(KafkaRecordSource source) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(source::isConnected);
    }

    private static void awaitRecords(List<InboundRecord> received, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= count);
    }

    /** The group's committed offset for the one partition, or {@code null} for none. */
    private static Long committedOffset(MockConsumer<String, ?> consumer) {
        return committedOffset(consumer, PARTITION);
    }

    private static Long committedOffset(MockConsumer<String, ?> consumer, TopicPartition partition) {
        OffsetAndMetadata offset = consumer.committed(Set.of(partition)).get(partition);
        return offset == null ? null : offset.offset();
    }

    private static void awaitCommit(MockConsumer<String, ?> consumer, long offset) {
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
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        MockConsumer<String, String> consumer = mockConsumer();

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(received::add);
            awaitConnected(source);
            deliver(consumer,
                    record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"),
                    record(BEGINNING + 1, "ORD-2", "{\"orderId\":\"ORD-2\"}"));
            awaitRecords(received, 2);

            assertThat(received).extracting(InboundRecord::key).containsExactly("ORD-1", "ORD-2");
            assertThat(received).extracting(InboundRecord::data)
                    .containsExactly("{\"orderId\":\"ORD-1\"}", "{\"orderId\":\"ORD-2\"}");
            assertThat(received).extracting(InboundRecord::action)
                    .containsOnly(InboundRecord.Action.UPSERT);
            // Text by the connector's format: nothing typed the topic.
            assertThat(received).extracting(InboundRecord::type).containsOnly(PayloadType.UNSET);
            // The offset IS the position: it is the seqno, and it is what the acknowledger
            // is called with.
            assertThat(received).extracting(InboundRecord::seqno)
                    .containsExactly(BEGINNING, BEGINNING + 1);
            // Unlike TCP and Hazelcast, every record here can be acknowledged: an offset is
            // exactly the position the framework's at-least-once contract needs -- and the
            // acknowledger is the partition's, shared by every record read from it.
            assertThat(received).extracting(InboundRecord::acknowledger)
                    .doesNotContain(Acknowledger.NONE);
            assertThat(received.get(1).acknowledger()).isSameAs(received.get(0).acknowledger());
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
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        MockConsumer<String, String> consumer = mockConsumer();

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(received::add);
            awaitConnected(source);
            deliver(consumer,
                    record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"),
                    record(BEGINNING + 1, "ORD-1", null));
            awaitRecords(received, 2);

            InboundRecord tombstone = received.get(1);
            assertThat(tombstone.action()).isEqualTo(InboundRecord.Action.DELETE);
            assertThat(tombstone.key()).isEqualTo("ORD-1");
            // Empty rather than null: the DELETE path still decodes the payload, and an
            // empty document is the quiet answer.
            assertThat(tombstone.text()).isEmpty();
            assertThat(tombstone.seqno()).isEqualTo(BEGINNING + 1);
            assertThat(tombstone.acknowledger()).as("a removal is published too, so it is acked too")
                    .isNotSameAs(Acknowledger.NONE);
        }
    }

    // ---- a typed topic ----------------------------------------------------------------

    @Test
    @DisplayName("payload-type reads the values as bytes and tags every record with the type")
    void aTypedTopicIsReadAsBytesUnderItsType() {
        ConnectorProperties connector = connector(KafkaSourceProperties.From.EARLIEST);
        connector.getSource().getKafka().getPayloadType().setFactoryId(100);
        connector.getSource().getKafka().getPayloadType().setClassId(1);
        PayloadType type = PayloadType.of(100, 1);

        // The codec gets exactly the bytes the producer wrote, not a String decoded from them
        // and re-encoded: a serialized protobuf is not text and must not be treated as it.
        assertThat(new KafkaRecordSource(connector, null).consumerConfig())
                .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        ByteArrayDeserializer.class.getName())
                .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class.getName());

        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        MockConsumer<String, byte[]> consumer = prepared(new IdlingMockConsumer<>());
        try (KafkaRecordSource source = source(connector, consumer)) {
            source.start(received::add);
            awaitConnected(source);
            deliver(consumer,
                    bytes(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"),
                    bytes(BEGINNING + 1, "ORD-1", null));
            awaitRecords(received, 2);

            InboundRecord typed = received.get(0);
            assertThat(typed.type()).isEqualTo(type);
            assertThat(typed.data()).isInstanceOf(byte[].class);
            assertThat((byte[]) typed.data())
                    .containsExactly("{\"orderId\":\"ORD-1\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(typed.text()).isEqualTo("{\"orderId\":\"ORD-1\"}");
            assertThat(typed.key()).isEqualTo("ORD-1");
            assertThat(typed.seqno()).isEqualTo(BEGINNING);
            assertThat(typed.acknowledger()).isNotSameAs(Acknowledger.NONE);

            // A tombstone is still a DELETE with nothing to decode -- tagged with the topic's
            // type like every other record of the stream.
            InboundRecord tombstone = received.get(1);
            assertThat(tombstone.action()).isEqualTo(InboundRecord.Action.DELETE);
            assertThat(tombstone.type()).isEqualTo(type);
            assertThat(tombstone.hasData()).isFalse();
        }
    }

    @Test
    @DisplayName("a half-set payload-type is refused when the source is built")
    void aHalfSetPayloadTypeIsRefused() {
        ConnectorProperties connector = connector(KafkaSourceProperties.From.EARLIEST);
        connector.getSource().getKafka().getPayloadType().setClassId(1);

        assertThatThrownBy(() -> new KafkaRecordSource(connector, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0/1");
    }

    // ---- when an offset is committed --------------------------------------------------

    @Test
    @DisplayName("nothing is committed until the pipeline acknowledges the record")
    void offsetsAreCommittedOnlyForAcknowledgedRecords() throws Exception {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
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

            received.get(0).ack();

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
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
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
            received.get(2).ack();
            awaitCommit(consumer, BEGINNING + 3);

            received.get(0).ack();
            severalMorePolls();

            // A committed offset must never walk backwards: doing so would re-publish
            // records AMPS already holds, and would do it on every restart.
            assertThat(committedOffset(consumer)).isEqualTo(BEGINNING + 3);
        }
    }

    @Test
    @DisplayName("ack(offset) through the partition's acknowledger commits offset + 1, whichever record carried it")
    void ackByOffsetCommitsThePositionAfterIt() throws Exception {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
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

            // The acknowledger is the stream's, not the record's: the first record's one,
            // called with the second record's offset, commits past the second record.
            received.get(0).ack(BEGINNING + 1);
            awaitCommit(consumer, BEGINNING + 2);

            // Cumulative: a lower position afterwards is already covered and moves nothing.
            received.get(0).ack();
            severalMorePolls();
            assertThat(committedOffset(consumer)).isEqualTo(BEGINNING + 2);
        }
    }

    @Test
    @DisplayName("ackBatch(first, last) commits last + 1 -- a run is one commit, not one per record")
    void ackBatchCommitsThePositionAfterTheRun() {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
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

            received.get(2).ackBatch(BEGINNING, BEGINNING + 2);

            awaitCommit(consumer, BEGINNING + 3);
        }
    }

    @Test
    @DisplayName("each partition has its own acknowledger, and an offset only ever moves its own")
    void eachPartitionHasItsOwnAcknowledger() throws Exception {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        MockConsumer<String, String> consumer = mockConsumer();

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(received::add);
            awaitConnected(source);
            consumer.schedulePollTask(() -> {
                consumer.rebalance(List.of(PARTITION, OTHER_PARTITION));
                consumer.addRecord(record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"));
                consumer.addRecord(new ConsumerRecord<>(TOPIC, 1, 0L, "ORD-2", "{\"orderId\":\"ORD-2\"}"));
                consumer.addRecord(new ConsumerRecord<>(TOPIC, 1, 1L, "ORD-3", "{\"orderId\":\"ORD-3\"}"));
            });
            awaitRecords(received, 3);

            InboundRecord onZero = received.stream()
                    .filter(record -> record.attributes().get("partition").equals("0"))
                    .findFirst().orElseThrow();
            List<InboundRecord> onOne = received.stream()
                    .filter(record -> record.attributes().get("partition").equals("1"))
                    .toList();
            assertThat(onOne).hasSize(2);
            // An offset is a position within ONE partition, so the acknowledger is per
            // partition: the two records of partition 1 share one, partition 0 has another.
            assertThat(onOne.get(0).acknowledger()).isSameAs(onOne.get(1).acknowledger());
            assertThat(onOne.get(0).acknowledger()).isNotSameAs(onZero.acknowledger());
            assertThat(onOne).extracting(InboundRecord::seqno).containsExactly(0L, 1L);

            onOne.get(1).ack();
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> Objects.equals(committedOffset(consumer, OTHER_PARTITION), 2L));
            severalMorePolls();
            // Partition 0's record was never acknowledged, so partition 0 stays put.
            assertThat(committedOffset(consumer, PARTITION)).isNull();
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
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        InFlightCommitMockConsumer consumer = prepared(new InFlightCommitMockConsumer());

        try (KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer)) {
            source.start(received::add);
            awaitConnected(source);
            deliver(consumer, record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"));
            awaitRecords(received, 1);
            received.get(0).ack();
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

            // The partition's acknowledger went with it. A batch flushed after the revoke
            // still holds the old one, and its late acknowledgment must not re-enter an
            // offset the next poll would commit over whatever the new owner has committed.
            Acknowledger stale = received.get(0).acknowledger();
            stale.ack(BEGINNING + 5);
            severalMorePolls();
            assertThat(consumer.asyncCommits.get()).isEqualTo(afterRevoke);

            // Reassigned, the partition starts with a fresh acknowledger of its own.
            deliver(consumer, record(BEGINNING + 1, "ORD-2", "{\"orderId\":\"ORD-2\"}"));
            awaitRecords(received, 2);
            assertThat(received.get(1).acknowledger()).isNotSameAs(stale);
        }
    }

    // ---- lifecycle -------------------------------------------------------------------

    @Test
    @DisplayName("close() commits what is still owed, synchronously, and stops the poll thread")
    void closeCommitsWhatIsOwedAndStopsThePollThread() throws Exception {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        InFlightCommitMockConsumer consumer = prepared(new InFlightCommitMockConsumer());
        KafkaRecordSource source =
                source(connector(KafkaSourceProperties.From.EARLIEST), consumer);
        source.start(received::add);
        awaitConnected(source);

        deliver(consumer, record(BEGINNING, "ORD-1", "{\"orderId\":\"ORD-1\"}"));
        awaitRecords(received, 1);
        received.get(0).ack();
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
