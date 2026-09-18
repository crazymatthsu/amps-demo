package com.demo.amps.qfj2.seqno;

import java.util.Optional;

/**
 * Where checkpoints go and come back from.
 *
 * <p>The store library talks to this, not to AMPS directly, so the store,
 * the write-behind thread and the recovery decision are all testable with an
 * in-memory map -- and so a deployment that wanted a different replication
 * target could supply one as a Spring bean.
 *
 * <p>Both operations are synchronous and may block on I/O; the
 * {@link WriteBehindPublisher} is what keeps them off the FIX session thread.
 */
public interface SeqnoReplicator extends AutoCloseable {

    /** A replicator that keeps nothing: what {@code qfj.seqno.enabled=false} wires. */
    SeqnoReplicator NONE = new SeqnoReplicator() {
        @Override
        public void publish(SeqnoSnapshot snapshot) {
        }

        @Override
        public Optional<SeqnoSnapshot> load(String sessionId) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "SeqnoReplicator.NONE";
        }
    };

    /** Records the checkpoint; returns once it is durable at the target. */
    void publish(SeqnoSnapshot snapshot) throws Exception;

    /** The most recent checkpoint for the session, if the target has one. */
    Optional<SeqnoSnapshot> load(String sessionId) throws Exception;

    @Override
    default void close() {
    }
}
