package com.demo.amps.ha;

import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.ha.api.HaPublisher;
import java.time.Duration;
import java.util.function.LongConsumer;

/**
 * Publishes numbered {@link OrderRecord}s through an {@link HaPublisher}.
 *
 * <p>What makes the publishing side of a failover lossless -- a publish store
 * replayed after the reconnect, no retry on a disconnect, {@code publishFlush}
 * rather than {@code flush} -- lives in {@link HaPublisher}; this class only
 * numbers the records and paces them so a human, or the test, has time to
 * pull an instance out from under it.
 */
public final class OrderPublisher implements AutoCloseable {

    private final HaPublisher publisher;
    private final String topic;
    private final String run;
    private volatile long published;

    /**
     * @param publisher a connected HA publisher
     * @param topic     the replicated SOW topic
     * @param run       the tag for this run, part of every record's key
     */
    public OrderPublisher(HaPublisher publisher, String topic, String run) {
        this.publisher = publisher;
        this.topic = topic;
        this.run = run;
    }

    /** Publishes position {@code seq} of this run. */
    public void publish(long seq) throws AMPSException {
        publisher.publish(topic, OrderRecord.of(run, publisher.name(), seq).toJson());
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
     * persisted; {@code false} on a timeout or a disconnect, in which case
     * nothing is lost: the store still holds the unacknowledged messages and
     * the client will replay them.
     */
    public boolean flush(Duration timeout) {
        return publisher.flush(timeout);
    }

    /** Messages the server has not yet acknowledged as persisted. */
    public long unpersisted() {
        return publisher.unpersisted();
    }

    /** The highest sequence number handed to the client so far. */
    public long published() {
        return published;
    }

    public HaPublisher publisher() {
        return publisher;
    }

    @Override
    public void close() {
        publisher.close();
    }
}
