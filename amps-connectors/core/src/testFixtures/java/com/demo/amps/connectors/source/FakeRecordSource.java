package com.demo.amps.connectors.source;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link RecordSource} the tests drive by hand.
 *
 * <p>Stands in for every transport at once, which is the point of the SPI: a test that is
 * about batching, keying or acknowledgment has no business starting a broker, and a test that
 * IS about a broker lives in that broker's own source module.
 */
public class FakeRecordSource implements RecordSource {

    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final List<SourceRecord> replay = new ArrayList<>();

    private volatile RecordHandler handler;
    private volatile boolean connected;

    /** Records delivered automatically on every {@link #start}, standing in for a replay. */
    public FakeRecordSource withReplay(SourceRecord... records) {
        replay.addAll(List.of(records));
        return this;
    }

    @Override
    public void start(RecordHandler handler) {
        this.handler = handler;
        this.connected = true;
        starts.incrementAndGet();
        replay.forEach(handler::onRecord);
    }

    /** Push one record as if the source had read it. */
    public void emit(SourceRecord record) {
        handler().onRecord(record);
    }

    /** Push a group of records the way a Kafka poll or a JDBC result set would. */
    public void emitAll(SourceRecord... records) {
        handler().onBatch(List.of(records));
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /** Fake a connection drop without closing: what the status line and health probe read. */
    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    @Override
    public void close() {
        connected = false;
        handler = null;
        closes.incrementAndGet();
    }

    /** How many times this source was started -- one per connector restart. */
    public int startCount() {
        return starts.get();
    }

    /** How many times it was closed; {@code close()} is meant to be idempotent, not unrepeated. */
    public int closeCount() {
        return closes.get();
    }

    /** The handler the connector registered, so a test can call it directly. */
    public RecordHandler handler() {
        RecordHandler current = handler;
        if (current == null) {
            throw new IllegalStateException("not started");
        }
        return current;
    }
}
