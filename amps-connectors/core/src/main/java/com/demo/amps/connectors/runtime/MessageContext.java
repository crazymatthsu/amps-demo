package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.source.InboundRecord;
import java.util.Map;

/**
 * One record on its way through a connector: the {@link InboundRecord} as the source
 * delivered it and the {@link OutboundRecord} the pipeline made of it, under one roof.
 *
 * <p>Two immutable halves rather than one mutable object, because the two are made at
 * different times by different code: the source builds the in-half on its reader thread,
 * the pipeline builds the out-half from it, and only then does a context exist at all. A
 * record that was filtered, dropped or rejected never becomes one. What the context adds is
 * what neither half can hold on its own -- the pairing, and the one fact that is learned
 * <em>after</em> both are built, the AMPS client sequence the publisher was given for the
 * command ({@link #dataOutSeqno()}), which is the only mutable slot and is written once.
 *
 * <p>The accessors read like the two halves they delegate to: {@code dataIn*} is the source's
 * side -- the payload, its {@link PayloadType type} as factory/class ids, and the position in
 * the source's stream -- and {@code dataOut*} is the AMPS side. The seqnos are different
 * numbers about different streams: the in-side one is what the source is acknowledged with,
 * the out-side one is what the publish store assigned, {@code 0} until the publisher writes
 * and always {@code 0} with {@code publish-store: NONE}. Acknowledgment goes to the in-half,
 * and {@code seqno} in {@link #ack(long)} means the in-side position.
 */
public interface MessageContext {

    /** The record as the source delivered it. */
    InboundRecord in();

    /** The command the pipeline decided on. */
    OutboundRecord out();

    /** The AMPS client sequence assigned to the command; {@code 0} until published, or without a store. */
    long dataOutSeqno();

    /**
     * Record the AMPS client sequence the publisher was given for the command. Framework use.
     *
     * <p>Write-once: {@code 0} becomes {@code seqno}, the same value again is a no-op, and a
     * different one is a programming error, because a context is published exactly once.
     *
     * @param seqno the sequence the publish store assigned; {@code 0} (no store) is ignored
     * @throws IllegalStateException if a different sequence was already assigned
     * @throws IllegalArgumentException if {@code seqno} is negative
     */
    void assignOutSeqno(long seqno);

    /** The in-side position: the record's sequence number in its source's stream. */
    default long dataInSeqno() {
        return in().seqno();
    }

    /** The in-side payload type's factory id; {@code 0} for text. */
    default int dataInFactoryId() {
        return in().type().factoryId();
    }

    /** The in-side payload type's class id; {@code 0} for text. */
    default int dataInClassId() {
        return in().type().classId();
    }

    /** What the in-side payload is. */
    default PayloadType dataInType() {
        return in().type();
    }

    /** The payload as the source delivered it: text, bytes or the typed object. */
    default Object dataIn() {
        return in().data();
    }

    /** The out-side payload type's factory id; {@code 0} for text by {@code message-type}. */
    default int dataOutFactoryId() {
        return out().type().factoryId();
    }

    /** The out-side payload type's class id; {@code 0} for text by {@code message-type}. */
    default int dataOutClassId() {
        return out().type().classId();
    }

    /** What the out-side payload is. */
    default PayloadType dataOutType() {
        return out().type();
    }

    /** The payload as it goes to AMPS: text, bytes, or {@code null} for a delete. */
    default Object dataOut() {
        return out().data();
    }

    /** The source's own key for the record, or {@code null}. */
    default String key() {
        return in().key();
    }

    /** Whether the record asserts or removes. */
    default InboundRecord.Action action() {
        return in().action();
    }

    /** The transport metadata the source attached. */
    default Map<String, String> attributes() {
        return in().attributes();
    }

    /** Tell the source this record reached AMPS: {@link InboundRecord#ack()}. */
    default void ack() {
        in().ack();
    }

    /**
     * Tell the source an in-side position on this record's stream has been reached.
     *
     * @param seqno the in-side position
     */
    default void ack(long seqno) {
        in().ack(seqno);
    }

    /**
     * Tell the source a run of in-side positions on this record's stream has been reached.
     *
     * @param fromSeqno the first in-side position in the run
     * @param toSeqno the last in-side position in the run
     */
    default void ackBatch(long fromSeqno, long toSeqno) {
        in().ackBatch(fromSeqno, toSeqno);
    }

    /**
     * Pair the two halves.
     *
     * @param in the record as the source delivered it
     * @param out the command the pipeline decided on
     * @return the context, with no out-side sequence yet
     */
    static MessageContext of(InboundRecord in, OutboundRecord out) {
        return new DefaultMessageContext(in, out);
    }
}
