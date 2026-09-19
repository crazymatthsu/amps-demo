package com.demo.amps.connectors.source;

/**
 * A live subscription to one upstream feed.
 *
 * <p>The seam that keeps the rest of the connector testable without a broker: a source module
 * (for example {@code :amps-connectors:source-kafka}) implements one of these per driver and
 * contributes it through a {@link SourceFactory}, while {@link SimulatedSource} generates
 * records in process.
 *
 * <p>Implementations own their reconnect behaviour. {@link #start} returns once the reader
 * thread is running -- it does not wait for a connection -- so a broker that is down at
 * startup is a connector that reports {@link #isConnected()} {@code false} and keeps retrying
 * with backoff, not a connector that failed to start.
 */
public interface RecordSource extends AutoCloseable {

    /**
     * Start reading, delivering every record to {@code handler}.
     *
     * @param handler the record callback, invoked on the source's reader thread
     */
    void start(RecordHandler handler);

    /** Whether the feed is currently connected. Drives the status line and the health probe. */
    boolean isConnected();

    /** Stop reading and disconnect. Idempotent; unblocks and joins the reader thread (<= 5s). */
    @Override
    void close();
}
