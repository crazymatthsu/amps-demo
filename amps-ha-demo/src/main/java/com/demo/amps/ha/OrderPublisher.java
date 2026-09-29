package com.demo.amps.ha;

import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.DisconnectedException;
import com.crankuptheamps.client.exception.TimedOutException;
import java.time.Duration;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes numbered {@link OrderRecord}s through an HA client with a publish
 * store, and knows what the store is telling it.
 *
 * <p>Two things here are the difference between "survives a failover" and
 * "quietly duplicates":
 *
 * <ul>
 *   <li>A publish that ends in {@link DisconnectedException} is <b>not</b>
 *       retried by this class. With a publish store, the client stores the
 *       message before it attempts the send, and replays the store after it
 *       reconnects; a retry here would store the same order a second time under
 *       a new sequence number, which the server has no way to recognise as a
 *       duplicate. (In practice the HA client blocks the publishing thread
 *       through the reconnect and the exception is rare; the handling is here
 *       for the case where it does escape.)</li>
 *   <li>{@link #flush} uses {@code publishFlush}, which waits for the store to
 *       drain, not {@code flush}, which in this client version does nothing.
 *       With synchronous replication that drain is the moment every message is
 *       on BOTH instances -- or, while one instance is down, the moment the
 *       survivor's replication link was downgraded and it acknowledged alone.</li>
 * </ul>
 */
public final class OrderPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OrderPublisher.class);

    private final HAClient client;
    private final String topic;
    private final String run;
    private volatile long published;

    /**
     * @param client a connected HA client with a publish store
     * @param topic  the replicated SOW topic
     * @param run    the tag for this run, part of every record's key
     */
    public OrderPublisher(HAClient client, String topic, String run) {
        this.client = client;
        this.topic = topic;
        this.run = run;
    }

    /** Publishes position {@code seq} of this run. */
    public void publish(long seq) throws AMPSException {
        String json = OrderRecord.of(run, client.getName(), seq).toJson();
        try {
            client.publish(topic, json);
        } catch (DisconnectedException e) {
            // Stored, not sent: the HA client replays it after reconnecting.
            // See the class comment for why this must not retry.
            log.info("[{}] sequence {} is in the publish store, to be replayed after the reconnect ({})",
                    client.getName(), seq, e.getMessage());
        }
        published = seq;
    }

    /**
     * Publishes {@code from..to} inclusive, pausing {@code interval} between
     * messages, calling {@code afterEach} with every sequence number once it
     * has been handed to the client -- which is how the demo and the test
     * schedule the moment they pull an instance out from under it.
     */
    public void publishRange(long from, long to, Duration interval, LongConsumer afterEach)
            throws AMPSException, InterruptedException {
        for (long seq = from; seq <= to; seq++) {
            publish(seq);
            afterEach.accept(seq);
            if (!interval.isZero()) {
                Thread.sleep(interval.toMillis());
            }
        }
    }

    /**
     * Waits until every message published so far has been acknowledged as
     * persisted.
     *
     * @return {@code true} when the store drained, {@code false} on a timeout
     *     or a disconnect -- in which case nothing is lost, the store still
     *     holds the unacknowledged messages and the client will replay them
     */
    public boolean flush(Duration timeout) {
        try {
            client.publishFlush(Math.max(1L, timeout.toMillis()));
            return true;
        } catch (TimedOutException e) {
            log.warn("[{}] {} message(s) still unacknowledged after {}", client.getName(), unpersisted(), timeout);
            return false;
        } catch (DisconnectedException e) {
            log.warn("[{}] disconnected while waiting for acknowledgments: {}", client.getName(), e.toString());
            return false;
        }
    }

    /** Messages the server has not yet acknowledged as persisted. */
    public long unpersisted() {
        return client.getPublishStore() == null ? 0 : client.getPublishStore().unpersistedCount();
    }

    /** The highest sequence number handed to the client so far. */
    public long published() {
        return published;
    }

    public HAClient client() {
        return client;
    }

    @Override
    public void close() {
        client.close();
    }
}
