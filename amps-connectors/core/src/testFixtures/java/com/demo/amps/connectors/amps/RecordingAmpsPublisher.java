package com.demo.amps.connectors.amps;

import com.demo.amps.connectors.codec.Payloads;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link AmpsPublisher} that records instead of connecting.
 *
 * <p>Stands in for the AMPS client so a test can drive the whole flow -- the pipeline, the
 * aggregator, the timers, the acknowledgments -- and then assert on exactly the things that
 * matter and are hard to see against a real server: that the commands came out in the order
 * the records arrived, and that a batch of a hundred publishes made <em>one</em> flush.
 *
 * <p>Every command answers with a sequence number the way a client with a publish store does
 * -- {@code 1, 2, 3…} in the order the commands were issued -- so a test can see the out-side
 * sequence the batch publisher writes onto each {@code MessageContext}.
 *
 * <p>{@link #failFlushes(int)} is the other half. A failed flush is the interesting path of
 * the at-least-once contract: nothing is acknowledged, the sources re-read, and the connector
 * carries on. Reproducing that against a live AMPS means unplugging something; here it is a
 * counter.
 *
 * <p>{@link #slowFlushes(Duration)} makes a flush <em>take time</em>, which a real one always
 * does: {@code publishFlush} waits for the server's persisted ack. A flush that returns
 * instantly hides every scheduling problem a slow one would expose, and the shared-scheduler
 * starvation in {@code ConnectorFlowTest} is exactly such a problem.
 *
 * <p>The persisted acks are modelled too, for {@code ack-mode: PERSISTED}. The
 * {@link PublishListener} the batch publisher registers is kept, and by default a successful
 * {@link #flush} tells it everything issued so far is persisted -- which is what a real flush
 * means, and what keeps the FLUSH-mode tests true without their knowing. A test about the
 * acks themselves switches to {@link #manualPersistedAcks(boolean) manual} and then says
 * exactly what the server confirmed, with {@link #persistUpTo(long)}, or refused, with
 * {@link #failWrite(long, int)}.
 */
public class RecordingAmpsPublisher implements AmpsPublisher {

    /**
     * One recorded call.
     *
     * @param kind {@code publish}, {@code delta_publish}, {@code sow_delete_by_key} or
     *     {@code sow_delete_by_filter}
     * @param topic the topic it named
     * @param data the payload as the batch handed it over -- a {@code String}, a
     *     {@code byte[]} or a passed-through object -- or {@code null} for a delete
     * @param sowKeyOrFilter the SowKey or the delete filter, whichever the call carried
     */
    public record Call(String kind, String topic, Object data, String sowKeyOrFilter) {

        /** The payload as text: bytes decoded as UTF-8, {@code ""} for a delete. */
        public String text() {
            return Payloads.text(data);
        }
    }

    private final List<Call> calls = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger flushFailures = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();

    private volatile Duration flushDelay = Duration.ZERO;
    private volatile PublishListener listener;
    private volatile boolean manualPersistedAcks;

    private volatile boolean connected;

    @Override
    public void connect() {
        connected = true;
    }

    @Override
    public void setPublishListener(PublishListener listener) {
        this.listener = listener;
    }

    /** The listener the batch publisher registered, or {@code null}. */
    public PublishListener listener() {
        return listener;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /** Fake a connection drop without closing: what the status line reads. */
    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    @Override
    public long publish(String topic, Object data, String sowKey) {
        return record(new Call("publish", topic, data, sowKey));
    }

    @Override
    public long deltaPublish(String topic, Object data, String sowKey) {
        return record(new Call("delta_publish", topic, data, sowKey));
    }

    @Override
    public long sowDeleteByKey(String topic, String sowKey) {
        return record(new Call("sow_delete_by_key", topic, null, sowKey));
    }

    @Override
    public long sowDeleteByFilter(String topic, String filter) {
        return record(new Call("sow_delete_by_filter", topic, null, filter));
    }

    /** Record the call and answer the next sequence number, as a publish store would. */
    private long record(Call call) {
        calls.add(call);
        return sequence.incrementAndGet();
    }

    @Override
    public boolean flush(Duration timeout) {
        flushes.incrementAndGet();
        Duration delay = flushDelay;
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        boolean flushed = flushFailures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) == 0;
        if (flushed && !manualPersistedAcks) {
            // A real flush returns once the store is empty, i.e. once every sequence issued
            // so far has been discarded -- and reported -- as persisted.
            persistUpTo(sequence.get());
        }
        return flushed;
    }

    /**
     * Whether a successful flush stops standing in for the server's persisted acks. On, the
     * listener hears nothing until {@link #persistUpTo(long)} or {@link #failWrite(long, int)}
     * says so; off (the default), every flush that returns {@code true} persists everything
     * issued so far.
     */
    public RecordingAmpsPublisher manualPersistedAcks(boolean manual) {
        this.manualPersistedAcks = manual;
        return this;
    }

    /**
     * The server's persisted ack: everything up to {@code sequence} is in the transaction log,
     * as the client's store would report it through {@code discardUpTo}.
     */
    public RecordingAmpsPublisher persistUpTo(long sequence) {
        PublishListener current = listener;
        if (current != null) {
            current.persistedUpTo(sequence);
        }
        return this;
    }

    /**
     * The server refused, or already had, the command with this sequence, as the client's
     * failed-write handler would report it.
     *
     * @param sequence the command's sequence
     * @param reason one of the {@code Message.Reason} constants
     */
    public RecordingAmpsPublisher failWrite(long sequence, int reason) {
        PublishListener current = listener;
        if (current != null) {
            current.failedWrite(sequence, reason);
        }
        return this;
    }

    @Override
    public void close() {
        connected = false;
    }

    /** Make the next {@code count} flushes fail, as a timeout or a disconnect would. */
    public RecordingAmpsPublisher failFlushes(int count) {
        flushFailures.set(count);
        return this;
    }

    /** Make every flush block for {@code delay}, as the wait for the persisted ack does. */
    public RecordingAmpsPublisher slowFlushes(Duration delay) {
        this.flushDelay = delay;
        return this;
    }

    /** Every call made so far, in order. A copy, so an assertion cannot race the flow. */
    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** The calls of one kind, in order. */
    public List<Call> calls(String kind) {
        return calls().stream().filter(call -> call.kind().equals(kind)).toList();
    }

    /** How many flushes were attempted; one per batch is the contract. */
    public int flushCount() {
        return flushes.get();
    }

    /** The last sequence number handed out; the number of commands issued since construction. */
    public long lastSequence() {
        return sequence.get();
    }

    /** Forget everything recorded, keeping the connection state and the sequence. */
    public void clear() {
        calls.clear();
        flushes.set(0);
    }
}
