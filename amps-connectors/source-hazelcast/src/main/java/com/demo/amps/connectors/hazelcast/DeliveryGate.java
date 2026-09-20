package com.demo.amps.connectors.hazelcast;

import com.demo.amps.connectors.source.RecordHandler;
import com.demo.amps.connectors.source.SourceRecord;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts the deliveries that are inside the handler, so the client is not shut down under
 * them.
 *
 * <p>Hazelcast delivers entry events and plain-topic messages on its own event threads, and
 * {@code HazelcastInstance.shutdown()} ends those threads by <em>interrupting</em> them
 * ({@code StripedExecutor.Worker.shutdown()} is {@code taskQueue.clear(); interrupt();}). A
 * delivery that is inside {@link RecordHandler#onRecord} at that moment is inside the
 * pipeline, and the pipeline ends in the aggregator's {@code lockInterruptibly()}: the record
 * does not arrive and does not come back either -- the aggregator reports "Interrupted
 * getting lock", the connector counts a source error, and the record misses the forced
 * release the connector performs right after the source closes.
 *
 * <p>So every delivery passes through {@link #guard}, which counts it in on the way into the
 * handler and out on the way back, and {@code close()} asks {@link #awaitIdle} for the count
 * to reach zero -- bounded -- <em>before</em> it shuts the client down. Once {@link #close()}
 * has been called a delivery that has not entered yet is turned away rather than started: it
 * is a record Hazelcast raised after the connector decided to stop, the same record the
 * client's own shutdown would have thrown out of its event queue.
 *
 * <p>The increment comes before the closed check, and {@link #close()} sets closed before it
 * reads the count. Between the two orders at least one side sees the other, so a delivery is
 * either counted -- and waited for -- or refused, never silently in flight.
 */
final class DeliveryGate {

    private static final Logger log = LoggerFactory.getLogger(DeliveryGate.class);

    private final String connectorName;

    /** Deliveries currently inside the handler. */
    private final AtomicInteger inFlight = new AtomicInteger();

    /** Monitor {@link #awaitIdle} waits on; rung whenever the count returns to zero. */
    private final Object idle = new Object();

    private volatile boolean closed;

    DeliveryGate(String connectorName) {
        this.connectorName = connectorName;
    }

    /**
     * The handler every subscription is given: the same calls, counted.
     *
     * @param handler the connector's handler
     * @return a handler that enters the gate around each call and refuses calls after
     *     {@link #close()}
     */
    RecordHandler guard(RecordHandler handler) {
        return new RecordHandler() {
            @Override
            public void onRecord(SourceRecord record) {
                if (!enter()) {
                    return;
                }
                try {
                    handler.onRecord(record);
                } finally {
                    exit();
                }
            }

            @Override
            public void onBatch(List<SourceRecord> records) {
                if (!enter()) {
                    return;
                }
                try {
                    handler.onBatch(records);
                } finally {
                    exit();
                }
            }
        };
    }

    /**
     * Refuse every delivery that has not entered yet. Idempotent; does not wait -- that is
     * {@link #awaitIdle}'s job, so the caller can share one deadline between the wait here
     * and the join that follows it.
     */
    void close() {
        closed = true;
    }

    /**
     * Wait until no delivery is inside the handler, or {@code deadlineMillis} passes.
     *
     * <p>Callable whether or not the gate is {@link #close() closed}: the source thread also
     * drains before it shuts down a client it is about to replace, and there the gate stays
     * open for the client that comes next.
     *
     * @param deadlineMillis an absolute {@link System#currentTimeMillis()} deadline
     * @return {@code true} if nothing is in flight; {@code false} if the deadline passed or
     *     this thread was interrupted with a delivery still inside the handler
     */
    boolean awaitIdle(long deadlineMillis) {
        synchronized (idle) {
            while (inFlight.get() > 0) {
                long remaining = deadlineMillis - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    idle.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return inFlight.get() == 0;
                }
            }
            return true;
        }
    }

    /** Deliveries inside the handler right now. */
    int inFlight() {
        return inFlight.get();
    }

    private boolean enter() {
        inFlight.incrementAndGet();
        if (closed) {
            exit();
            log.debug("[{}] Hazelcast delivery refused: the source is closed", connectorName);
            return false;
        }
        return true;
    }

    private void exit() {
        if (inFlight.decrementAndGet() == 0) {
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }
}
