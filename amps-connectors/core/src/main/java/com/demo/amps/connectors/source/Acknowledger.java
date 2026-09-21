package com.demo.amps.connectors.source;

/**
 * What a source wants done once its records have actually reached AMPS -- one per stream,
 * called with the position that has.
 *
 * <p>The delivery guarantee is <em>at-least-once</em>, and this is the whole of it: the batch
 * publisher acknowledges a record once the flush that carried it has been confirmed as
 * persisted, and a failed flush leaves it unacknowledged so the source re-reads it. Kafka
 * commits offsets with it; an incremental JDBC poll persists its watermark with it. A source
 * that cannot rewind (TCP, Hazelcast) attaches {@link #NONE}.
 *
 * <p>The argument is the record's own {@link InboundRecord#seqno() seqno}: the source's
 * position for that record, a Kafka offset or a ringbuffer sequence where one exists and a
 * delivery counter elsewhere. Acknowledgment is <strong>cumulative</strong> -- {@code ack(n)}
 * says everything up to and including {@code n} on this stream has been published -- which
 * is why one acknowledger serves a whole stream rather than one closure serving one record:
 * a source can then answer a run of records with one write. {@link #ackBatch(long, long)} is
 * that run; the default reduces it to the last position, which is what a cumulative source
 * means anyway, and a source with something cheaper to do for a range overrides it.
 *
 * <p>Invoked on the publishing thread, so an implementation must be cheap and must not throw.
 */
@FunctionalInterface
public interface Acknowledger {

    /** Nothing to acknowledge: the source cannot rewind, so it has no position to move. */
    Acknowledger NONE = seqno -> {
    };

    /**
     * The record at this position, and everything before it on this stream, has been
     * published.
     *
     * @param seqno the acknowledged record's own sequence number; {@link InboundRecord#NO_SEQNO}
     *     when the source assigned none
     */
    void ack(long seqno);

    /**
     * A run of records from {@code fromSeqno} to {@code toSeqno}, inclusive, has been
     * published.
     *
     * @param fromSeqno the first position in the run
     * @param toSeqno the last position in the run
     */
    default void ackBatch(long fromSeqno, long toSeqno) {
        ack(toSeqno);
    }
}
