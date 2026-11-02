package com.demo.amps.fix42.state.publish;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the sink reports refused, handed from the thread that learns of it --
 * over AMPS, the client's receive thread -- to the publishing thread
 * (docs/04 §6, S2).
 *
 * <p>The failure callback runs on the sink's thread and the cache entries are
 * the publishing thread's, so the callback never touches an entry: it copies
 * what it was told into the queue and returns. The publisher drains it before
 * each message, on an idle timer and at shutdown, and decides the repair
 * there -- a delta of every cached field for a rejected delta, the kept
 * payload for a rejected creation, the catch-all topic for a rejected audit
 * publish. Over AMPS the callback is {@code amps.AmpsFailedWriteHandler}, the
 * client's {@code FailedWriteHandler}; another sink calls
 * {@link #onRefused} and {@link #onDuplicate} itself.
 *
 * <p>A duplicate is the server's expected answer to a store replay after a
 * reconnect (the message was already there): logged and counted, never
 * repaired.
 */
public final class RepairQueue {

    private static final Logger log = LoggerFactory.getLogger(RepairQueue.class);

    /**
     * One refused publish, copied off the sink's thread.
     *
     * @param topic    the topic it was published to
     * @param sequence the sequence {@link PublishSink} returned for it, 0 if none
     * @param data     the payload as sent
     * @param reason   why it was refused, for the log: over AMPS the
     *                 {@code Message.Reason} constant's name
     * @param delta    whether it was a delta publish: a refused creation is
     *                 re-sent whole, a refused delta is not
     */
    public record Failure(String topic, long sequence, byte[] data, String reason, boolean delta) {

        public boolean wasPublish() {
            return !delta;
        }
    }

    private final ConcurrentLinkedQueue<Failure> failures = new ConcurrentLinkedQueue<>();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    /** The sink's callback for a publish the server refused: logged and queued for repair. */
    public void onRefused(Failure failure) {
        refused.incrementAndGet();
        log.warn("publish {} to {} refused by the server, reason {}: queued for repair", failure.sequence(),
                failure.topic(), failure.reason());
        failures.add(failure);
    }

    /** The sink's callback for a publish the server dropped as one it already had: counted, never repaired. */
    public void onDuplicate(long sequence) {
        duplicates.incrementAndGet();
        log.info("publish {} dropped by the server as a duplicate (expected after a replay)", sequence);
    }

    /** Enqueues a failure directly: a client without a store, or a test. */
    public void add(Failure failure) {
        refused.incrementAndGet();
        failures.add(failure);
    }

    /** The next failure to repair, or null. */
    public Failure poll() {
        return failures.poll();
    }

    public boolean isEmpty() {
        return failures.isEmpty();
    }

    /** {@code publishDuplicatesOnReplay}. */
    public long duplicates() {
        return duplicates.get();
    }

    /** Everything the server refused for a reason other than a duplicate. */
    public long refused() {
        return refused.get();
    }
}
