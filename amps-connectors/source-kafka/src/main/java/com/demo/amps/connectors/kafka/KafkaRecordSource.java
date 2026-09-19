package com.demo.amps.connectors.kafka;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.KafkaSourceProperties;
import com.demo.amps.connectors.source.RecordHandler;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceRecord;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordSource} backed by the Apache Kafka consumer.
 *
 * <p>Reads its cluster and topic settings from {@code source.kafka}; the wire format stays on
 * the connector, because it describes the payload rather than the transport. Values and keys
 * arrive as strings and go straight to the connector's decoder, exactly as an AMPS payload
 * does. A null value is a tombstone and becomes a {@code DELETE}; {@code topic},
 * {@code partition} and {@code offset} ride along as attributes, because once several
 * partitions are interleaved they are the only things that say where a record came from.
 *
 * <h2>A consumer group, because AMPS is the state</h2>
 *
 * <p>This source {@link Consumer#subscribe subscribes} with a {@code group.id} rather than
 * assigning partitions and seeking. That is the opposite of what a connector into an in-memory
 * analytics table does, and for the opposite reason: <strong>here the AMPS topic is the
 * durable sink</strong>. A SOW keeps what was published to it and a transaction log keeps every
 * message that ever went through it, so a connector that replayed its Kafka topic from the
 * beginning on every restart would append the whole log to the journal topic a second time.
 * The group is where the position lives, which is why {@code group-id} is mandatory, and
 * {@code from} only decides where a <em>brand new</em> group starts
 * ({@code auto.offset.reset}) -- a group that has committed anything resumes from its commit
 * and ignores it.
 *
 * <p>It also buys the thing assignment cannot: running the same connector twice shares the
 * topic's partitions between the two instances instead of publishing everything twice.
 *
 * <h2>Acknowledgments drive the commits</h2>
 *
 * <p>{@code enable.auto.commit} is off and nothing is committed on a timer. Every record
 * carries an {@link com.demo.amps.connectors.source.Acknowledgment} that records
 * {@code offset + 1} for its partition, and the framework calls it only after the batch that
 * contains the record has been published <em>and flushed</em> to AMPS. The poll thread then
 * commits what has been acknowledged. So a crash between a publish and its flush re-reads
 * those records rather than losing them -- at-least-once, the framework's contract, made of
 * Kafka's own parts:
 *
 * <table border="1">
 *   <caption>Who commits what, and from where</caption>
 *   <tr><th>moment</th><th>commit</th><th>why there</th></tr>
 *   <tr><td>after every {@code poll()}</td><td>{@code commitAsync}</td>
 *       <td>the cheap steady-state path: the callback logs a failure and the offsets are
 *           carried into the next commit, so a failed commit costs a re-read at worst</td></tr>
 *   <tr><td>{@code onPartitionsRevoked}</td><td>{@code commitSync}</td>
 *       <td>the partition is about to belong to somebody else, and an async commit would
 *           still be in flight when it does</td></tr>
 *   <tr><td>{@link #close()}</td><td>{@code commitSync}</td>
 *       <td>a clean stop should not re-publish the last batch on the next start</td></tr>
 * </table>
 *
 * <p>Every one of those runs on the poll thread. A {@code KafkaConsumer} is not thread-safe
 * and {@link Consumer#wakeup()} is its one exception, so {@link #close()} only wakes the poll
 * and lets the thread that owns the consumer do the committing and the closing.
 *
 * <p>A handler that throws is a record that is never acknowledged, and therefore an offset
 * that is never committed: the failure is logged and the poll loop carries on, and the record
 * comes back on the next start. Note the shape of that -- offsets are a <em>watermark</em>,
 * not a set, so a later record that IS acknowledged commits past the failed one. That is the
 * standard Kafka trade-off and the reason the pipeline counts its rejections rather than
 * relying on the commit to remember them.
 *
 * <p>{@link #start} returns as soon as the poll thread is running, rather than connecting on
 * the caller's thread. For a transport whose normal state includes "the broker is not up yet",
 * a failed first connect is the same event as a dropped one and both belong in the same
 * backoff -- so {@link #isConnected()}, not a thrown {@code start}, is what reports it.
 */
public class KafkaRecordSource implements RecordSource {

    private static final Logger log = LoggerFactory.getLogger(KafkaRecordSource.class);

    /** Attribute carrying the Kafka topic a record was read from. */
    public static final String ATTRIBUTE_TOPIC = "topic";

    /** Attribute carrying the partition a record was read from. */
    public static final String ATTRIBUTE_PARTITION = "partition";

    /** Attribute carrying the record's offset within its partition. */
    public static final String ATTRIBUTE_OFFSET = "offset";

    /** How long {@link #close()} waits for the poll thread before giving up on it. */
    private static final long CLOSE_JOIN_MILLIS = 5_000;

    /** How long the final {@code commitSync} on close is allowed to take. */
    private static final Duration CLOSE_COMMIT_TIMEOUT = Duration.ofSeconds(5);

    private final ConnectorProperties connector;
    private final KafkaSourceProperties source;
    private final Supplier<Consumer<String, String>> consumerFactory;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** Monitor the reconnect backoff waits on, so {@link #close()} cuts it short. */
    private final Object backoff = new Object();

    /**
     * The highest offset the pipeline has acknowledged per partition, already expressed as the
     * offset to <em>resume from</em> ({@code offset + 1}), which is what a commit means.
     *
     * <p>Written by whichever thread flushed the batch and read by the poll thread, hence
     * concurrent; merged with {@code max} so acknowledgments that arrive out of order (a batch
     * spanning two polls, a retry) can only move a partition forwards.
     */
    private final ConcurrentHashMap<TopicPartition, Long> acknowledged = new ConcurrentHashMap<>();

    /**
     * What has actually been committed, per partition, so the same offsets are not committed
     * again on every poll and a late low acknowledgment cannot walk one backwards.
     *
     * <p>Entries appear only when a commit succeeded, which is what makes a failed
     * {@code commitAsync} self-healing: its offsets are still in {@link #acknowledged} and
     * above this floor, so the next poll simply commits them again.
     */
    private final ConcurrentHashMap<TopicPartition, Long> committed = new ConcurrentHashMap<>();

    private volatile Consumer<String, String> consumer;
    private volatile Thread thread;

    public KafkaRecordSource(ConnectorProperties connector) {
        this(connector, null);
    }

    /**
     * Package-private seam: hands the poll loop a consumer of the test's choosing (a
     * {@code MockConsumer}) instead of dialling a broker. Each call must return a <em>new</em>
     * consumer -- the loop closes its consumer before it reconnects.
     *
     * @param connector the connector configuration
     * @param consumerFactory builds the consumer, or {@code null} for a real
     *     {@link KafkaConsumer} built from {@link #consumerConfig()}
     */
    KafkaRecordSource(
            ConnectorProperties connector, Supplier<Consumer<String, String>> consumerFactory) {
        this.connector = connector;
        this.source = connector.getSource().getKafka();
        this.consumerFactory = consumerFactory != null
                ? consumerFactory
                : () -> new KafkaConsumer<>(consumerConfig());
    }

    @Override
    public void start(RecordHandler handler) {
        log.info("[{}] starting Kafka source: {} (topic '{}', group '{}', new groups from {})",
                connector.getName(), source.getBootstrapServers(), source.getTopic(),
                source.getGroupId(), source.getFrom());
        Thread runner = new Thread(() -> run(handler), connector.getName() + "-kafka");
        runner.setDaemon(true);
        this.thread = runner;
        runner.start();
    }

    /**
     * The consumer configuration this source dials with.
     *
     * <p>Package-private because it is the honest place to assert what the connector sends to
     * the broker; the {@code properties} passthrough is applied <em>last</em>, so an operator
     * can override anything here -- including the deserializers -- without a code change.
     *
     * @return the consumer properties, in the order they are applied
     */
    Map<String, Object> consumerConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, source.getBootstrapServers());
        // The group IS this connector's position: it is what a restart resumes from, and what
        // stops a second instance from re-publishing the partitions the first one is reading.
        config.put(ConsumerConfig.GROUP_ID_CONFIG, source.getGroupId());
        // Only consulted for a group that has never committed; afterwards the commit wins.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                source.getFrom().name().toLowerCase(Locale.ROOT));
        // Off, and the whole point: a background commit would acknowledge records the
        // pipeline has not flushed to AMPS yet, turning at-least-once into at-most-once.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        // One poll is one pass through the pipeline, so this bounds how much work sits
        // between two commits -- and how much is re-read after a crash.
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                Integer.toString(source.getMaxPollRecords()));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        // Names this connector in the broker's own logs and metrics, so a busy cluster can
        // be asked which connector is behind a lagging group.
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, connector.getName());
        config.putAll(source.getProperties());
        return config;
    }

    /**
     * Connect, subscribe, poll -- and on any failure short of {@link #close()}, do it all
     * again after the backoff. One iteration of the outer loop is one consumer's lifetime.
     */
    private void run(RecordHandler handler) {
        while (!closed.get()) {
            Consumer<String, String> client = null;
            try {
                client = consumerFactory.get();
                this.consumer = client;
                if (closed.get()) {
                    // close() publishes `closed` and then reads `consumer`; this reads them
                    // the other way round, so between the two at least one of us sees the
                    // other. Without the check, a close landing in this window would leave a
                    // consumer nobody wakes.
                    break;
                }
                // subscribe(), not assign(): the group is the position, and the rebalance
                // listener is what keeps that position honest when a partition moves.
                client.subscribe(List.of(source.getTopic()), new GroupListener(client));
                connected.set(true);
                log.info("[{}] subscribed to '{}' as group '{}'",
                        connector.getName(), source.getTopic(), source.getGroupId());
                consume(client, handler);
            } catch (WakeupException e) {
                // The only thing that wakes the poll is close().
                if (!closed.get()) {
                    log.warn("[{}] Kafka poll woken without a close", connector.getName());
                }
            } catch (Exception e) {
                if (!closed.get()) {
                    log.error("[{}] Kafka source failed on topic '{}'",
                            connector.getName(), source.getTopic(), e);
                }
            } finally {
                connected.set(false);
                this.consumer = null;
                // Still on the poll thread, which is the only thread allowed to touch the
                // consumer -- so this is where the last acknowledged offsets go out.
                commitFinal(client);
                closeQuietly(client);
            }
            if (!closed.get()) {
                log.info("[{}] reconnecting to Kafka in {} (resuming from the group's offsets)",
                        connector.getName(), source.getReconnectDelay());
                if (!sleep(source.getReconnectDelay())) {
                    break;
                }
            }
        }
        connected.set(false);
        log.info("[{}] Kafka source stopped", connector.getName());
    }

    /** Poll, hand every record over, commit whatever the pipeline acknowledged meanwhile. */
    private void consume(Consumer<String, String> client, RecordHandler handler) {
        Duration pollTimeout = source.getPollTimeout();
        while (!closed.get()) {
            ConsumerRecords<String, String> records = client.poll(pollTimeout);
            for (ConsumerRecord<String, String> record : records) {
                dispatch(record, handler);
            }
            // After the records, not before: a poll that returned nothing still has to commit,
            // because the acknowledgments for the PREVIOUS poll arrive while this one blocks.
            commitAcknowledged(client);
        }
    }

    /**
     * Turn one Kafka record into a {@link SourceRecord} and hand it over.
     *
     * <p>Runs on the poll thread and the pipeline runs inside
     * {@link RecordHandler#onRecord}, which is the back-pressure: a connector that cannot keep
     * up stops polling, and Kafka's own {@code max.poll.interval.ms} eventually rebalances its
     * partitions to an instance that can.
     */
    private void dispatch(ConsumerRecord<String, String> record, RecordHandler handler) {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        try {
            SourceRecord delivered = record.value() == null
                    // A tombstone: key, no value. The empty payload keeps the DELETE path's
                    // decode quiet -- a removal is addressed by its key, and on a compacted
                    // topic the message key is the only thing that can carry it.
                    ? SourceRecord.delete("", record.key())
                    : SourceRecord.of(record.value(), record.key());
            handler.onRecord(delivered
                    .withAttributes(attributesOf(record))
                    .withAck(() -> acknowledge(partition, record.offset())));
        } catch (RuntimeException e) {
            // One bad record is not a reason to drop the subscription -- and because it was
            // never acknowledged, its offset is not committed either.
            log.error("[{}] failed to handle Kafka record at {}-{}@{}", connector.getName(),
                    record.topic(), record.partition(), record.offset(), e);
        }
    }

    /** Where the record came from: the only thing an interleaved feed cannot reconstruct. */
    private static Map<String, String> attributesOf(ConsumerRecord<String, String> record) {
        Map<String, String> attributes = new LinkedHashMap<>(4);
        attributes.put(ATTRIBUTE_TOPIC, record.topic());
        attributes.put(ATTRIBUTE_PARTITION, Integer.toString(record.partition()));
        attributes.put(ATTRIBUTE_OFFSET, Long.toString(record.offset()));
        return attributes;
    }

    // ---- offsets ---------------------------------------------------------------------

    /**
     * Record that one record reached AMPS.
     *
     * <p>Called by the batch publisher, on whichever thread flushed -- never on the poll
     * thread, and never touching the consumer. All it does is move a number; the poll thread
     * turns that number into a commit.
     *
     * @param partition the record's partition
     * @param offset the record's own offset, stored as {@code offset + 1} because that is
     *     what a committed offset means: where to resume
     */
    private void acknowledge(TopicPartition partition, long offset) {
        acknowledged.merge(partition, offset + 1, Math::max);
    }

    /**
     * The offsets that are acknowledged but not committed yet.
     *
     * <p>Entries are left in {@link #acknowledged} rather than removed: a commit that fails
     * (or never reports back before a close) then simply happens again, and the
     * {@link #committed} floor is what stops the successful ones from being re-sent every
     * poll.
     *
     * @param partitions the partitions to consider
     * @return the commit payload, empty when there is nothing new to say
     */
    private Map<TopicPartition, OffsetAndMetadata> pendingOffsets(
            Collection<TopicPartition> partitions) {
        Map<TopicPartition, OffsetAndMetadata> offsets = new LinkedHashMap<>();
        for (TopicPartition partition : partitions) {
            Long next = acknowledged.get(partition);
            if (next == null) {
                continue;
            }
            Long floor = committed.get(partition);
            if (floor != null && next <= floor) {
                continue;
            }
            offsets.put(partition, new OffsetAndMetadata(next));
        }
        return offsets;
    }

    /** The steady-state commit: cheap, asynchronous, and retried by the next poll if it fails. */
    private void commitAcknowledged(Consumer<String, String> client) {
        Map<TopicPartition, OffsetAndMetadata> offsets = pendingOffsets(acknowledged.keySet());
        if (offsets.isEmpty()) {
            return;
        }
        client.commitAsync(offsets, (committedOffsets, failure) -> {
            if (failure == null) {
                markCommitted(committedOffsets);
                return;
            }
            // Not an error: the offsets stay pending and the next poll commits them again.
            // The only cost of losing this one is re-reading those records after a crash.
            log.warn("[{}] Kafka commit failed for {}: {}", connector.getName(),
                    committedOffsets.keySet(), failure.toString());
            log.debug("[{}] Kafka commit failed", connector.getName(), failure);
        });
    }

    /**
     * The last commit of a consumer's life, synchronous because there is no next poll to
     * carry a retry: a clean stop should not re-publish its last batch on the next start.
     */
    private void commitFinal(Consumer<String, String> client) {
        if (client == null) {
            return;
        }
        Map<TopicPartition, OffsetAndMetadata> offsets = pendingOffsets(acknowledged.keySet());
        if (offsets.isEmpty()) {
            return;
        }
        try {
            client.commitSync(offsets, CLOSE_COMMIT_TIMEOUT);
            markCommitted(offsets);
            log.info("[{}] committed {} acknowledged offset(s) on close",
                    connector.getName(), offsets.size());
        } catch (RuntimeException e) {
            // The consumer may already be gone (this also runs after a failed poll). Nothing
            // is lost that at-least-once does not already allow: those records are re-read.
            log.warn("[{}] final Kafka commit failed: {}", connector.getName(), e.toString());
            log.debug("[{}] final Kafka commit failed", connector.getName(), e);
        }
    }

    /** Raise the floor so the same offsets are not committed again on the next poll. */
    private void markCommitted(Map<TopicPartition, OffsetAndMetadata> offsets) {
        offsets.forEach((partition, offset) ->
                committed.merge(partition, offset.offset(), Math::max));
    }

    /**
     * Keeps the group's offsets honest across a rebalance.
     *
     * <p>Everything here runs on the poll thread, inside {@code poll()}: that is where Kafka
     * invokes a rebalance listener, which is precisely why it is safe to commit from it.
     */
    private final class GroupListener implements ConsumerRebalanceListener {

        private final Consumer<String, String> client;

        private GroupListener(Consumer<String, String> client) {
            this.client = client;
        }

        /**
         * The partition is about to belong to another member, so its acknowledged offsets go
         * out <em>synchronously</em> -- an async commit would still be in flight when the new
         * owner starts reading, and it would start reading records this connector has already
         * published.
         */
        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            Map<TopicPartition, OffsetAndMetadata> offsets = pendingOffsets(partitions);
            if (!offsets.isEmpty()) {
                try {
                    client.commitSync(offsets);
                    markCommitted(offsets);
                    log.info("[{}] committed {} offset(s) for {} revoked partition(s)",
                            connector.getName(), offsets.size(), partitions.size());
                } catch (RuntimeException e) {
                    log.warn("[{}] commit on revoke failed for {}: {}",
                            connector.getName(), partitions, e.toString());
                    log.debug("[{}] commit on revoke failed", connector.getName(), e);
                }
            }
            forget(partitions);
        }

        /**
         * Lost, not revoked: the group has already given these partitions away, so committing
         * would only fail. Drop what is pending for them -- the member that owns them now
         * resumes from the group's last commit, which is the at-least-once re-read.
         */
        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            log.warn("[{}] lost {} partition(s) without a chance to commit: {}",
                    connector.getName(), partitions.size(), partitions);
            forget(partitions);
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            log.info("[{}] assigned {} partition(s) of '{}': {}", connector.getName(),
                    partitions.size(), source.getTopic(), partitions);
        }

        /**
         * Forget both the pending offsets and the committed floor of partitions this consumer
         * no longer owns: whatever happens to them next is another member's business, and a
         * stale floor would suppress a legitimate commit if they ever came back.
         */
        private void forget(Collection<TopicPartition> partitions) {
            for (TopicPartition partition : partitions) {
                acknowledged.remove(partition);
                committed.remove(partition);
            }
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        closed.set(true);
        connected.set(false);
        Consumer<String, String> client = this.consumer;
        if (client != null) {
            try {
                // The one consumer method that is safe to call from another thread -- which is
                // also why the final commit is the poll thread's job and not this one's.
                client.wakeup();
            } catch (RuntimeException e) {
                log.debug("[{}] Kafka wakeup on close failed", connector.getName(), e);
            }
        }
        synchronized (backoff) {
            backoff.notifyAll();
        }
        Thread runner = this.thread;
        this.thread = null;
        if (runner == null || runner == Thread.currentThread()) {
            return;
        }
        try {
            runner.join(CLOSE_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (runner.isAlive()) {
            log.warn("[{}] Kafka poll thread did not stop within {}ms",
                    connector.getName(), CLOSE_JOIN_MILLIS);
        }
    }

    /** The consumer belongs to its poll thread, so only that thread ever closes it. */
    private void closeQuietly(Consumer<String, String> client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            log.debug("[{}] Kafka consumer close failed", connector.getName(), e);
        }
    }

    /**
     * Wait out the reconnect backoff, returning early when {@link #close()} rings the monitor.
     *
     * @param delay the configured backoff
     * @return {@code false} if the source should stop instead of reconnecting
     */
    private boolean sleep(Duration delay) {
        synchronized (backoff) {
            if (closed.get()) {
                return false;
            }
            try {
                backoff.wait(Math.max(1L, delay.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !closed.get();
    }
}
