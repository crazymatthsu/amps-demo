package com.demo.amps.connectors.amps;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link AmpsPublisher} that records instead of connecting.
 *
 * <p>Stands in for the AMPS client so a test can drive the whole flow -- the pipeline, the
 * aggregator, the timers, the acknowledgments -- and then assert on exactly the things that
 * matter and are hard to see against a real server: that the commands came out in the order
 * the records arrived, and that a batch of a hundred publishes made <em>one</em> flush.
 *
 * <p>{@link #failFlushes(int)} is the other half. A failed flush is the interesting path of
 * the at-least-once contract: nothing is acknowledged, the sources re-read, and the connector
 * carries on. Reproducing that against a live AMPS means unplugging something; here it is a
 * counter.
 */
public class RecordingAmpsPublisher implements AmpsPublisher {

    /**
     * One recorded call.
     *
     * @param kind {@code publish}, {@code delta_publish}, {@code sow_delete_by_key} or
     *     {@code sow_delete_by_filter}
     * @param topic the topic it named
     * @param data the payload, or {@code null} for a delete
     * @param sowKeyOrFilter the SowKey or the delete filter, whichever the call carried
     */
    public record Call(String kind, String topic, String data, String sowKeyOrFilter) {
    }

    private final List<Call> calls = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger flushFailures = new AtomicInteger();

    private volatile boolean connected;

    @Override
    public void connect() {
        connected = true;
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
    public void publish(String topic, String data, String sowKey) {
        calls.add(new Call("publish", topic, data, sowKey));
    }

    @Override
    public void deltaPublish(String topic, String data, String sowKey) {
        calls.add(new Call("delta_publish", topic, data, sowKey));
    }

    @Override
    public void sowDeleteByKey(String topic, String sowKey) {
        calls.add(new Call("sow_delete_by_key", topic, null, sowKey));
    }

    @Override
    public void sowDeleteByFilter(String topic, String filter) {
        calls.add(new Call("sow_delete_by_filter", topic, null, filter));
    }

    @Override
    public boolean flush(Duration timeout) {
        flushes.incrementAndGet();
        return flushFailures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) == 0;
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

    /** Forget everything recorded, keeping the connection state. */
    public void clear() {
        calls.clear();
        flushes.set(0);
    }
}
