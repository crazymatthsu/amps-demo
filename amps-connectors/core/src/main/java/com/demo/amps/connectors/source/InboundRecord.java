package com.demo.amps.connectors.source;

import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.codec.Payloads;
import java.util.Map;
import java.util.Objects;

/**
 * The inbound record: one message as a {@link RecordSource} delivered it, before decoding.
 *
 * <p>The seam between a transport and the pipeline, and deliberately narrow: a payload and
 * what it is, whatever the source calls its own key, whether the message asserts or removes
 * the record, where in the source's stream it sits, transport metadata, and the hook that
 * tells the source the record reached AMPS. Everything that turns this into a publish --
 * decoding, filtering, transforming, keying, encoding -- is the pipeline's business, so a
 * new driver is a reader loop and nothing else.
 *
 * <p>The payload is an {@code Object} because the wire is not always text: a {@code String}
 * from a socket, a JSON feed or a FIX session; a {@code byte[]} from a Kafka topic of
 * serialized messages; the typed object itself from a Hazelcast map of
 * {@code IdentifiedDataSerializable} values. {@link #type()} says which, and the pipeline
 * picks the decoder by it -- a registered codec for a set type, the connector's
 * {@code format} for {@link PayloadType#UNSET}. Code that wants text asks {@link #text()};
 * {@code (String) data()} on a typed record is the cast this design retired.
 *
 * <p>The sequence number is the record's position in its source's stream -- a Kafka offset,
 * a reliable-topic ringbuffer sequence, or a per-source delivery counter where the native
 * position is not a number -- and it is what the {@link Acknowledger} is called with, so a
 * source can acknowledge cumulatively. {@link #NO_SEQNO} is a source that assigned none.
 *
 * @param data the raw payload: text, bytes or the typed object. May be {@code null} or empty
 *     for an {@link Action#DELETE} that the key alone identifies
 * @param type what the payload is; {@link PayloadType#UNSET} for text the connector's
 *     {@code format} decodes. Never {@code null}
 * @param key the source's own key for the record (a Kafka message key, the joined JDBC key
 *     columns), or {@code null} for a source that does not key its messages
 * @param action whether the message asserts the record or removes it
 * @param seqno the record's position in its source's stream, or {@link #NO_SEQNO}
 * @param attributes transport metadata as strings -- Kafka's {@code topic}/{@code partition}/
 *     {@code offset}, TCP's {@code remote}, JDBC's {@code poll}, Hazelcast's
 *     {@code publishTime}/{@code member}. Immutable, never {@code null}
 * @param acknowledger what to call, with {@link #seqno()}, once the record has been published
 *     and the batch flushed; {@link Acknowledger#NONE} for a source with nothing to
 *     acknowledge. Never {@code null}
 */
public record InboundRecord(
        Object data,
        PayloadType type,
        String key,
        Action action,
        long seqno,
        Map<String, String> attributes,
        Acknowledger acknowledger) {

    /** The sequence number of a record whose source assigned none. */
    public static final long NO_SEQNO = -1L;

    /** What the message says about the record. */
    public enum Action {
        /** The record exists with this content. */
        UPSERT,
        /** The record is gone: a Kafka tombstone, a row that vanished between JDBC polls. */
        DELETE
    }

    /**
     * Canonical constructor: an unset type and a no-op acknowledger stand in for {@code null},
     * and the attributes are copied so a source that reuses a map across records cannot
     * mutate one already in flight.
     */
    public InboundRecord {
        type = type == null ? PayloadType.UNSET : type;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        acknowledger = acknowledger == null ? Acknowledger.NONE : acknowledger;
        Objects.requireNonNull(action, "action");
    }

    /** An upsert with no source key -- the journal-feed shape; text, no position, no ack. */
    public static InboundRecord of(Object data) {
        return new InboundRecord(data, PayloadType.UNSET, null, Action.UPSERT, NO_SEQNO, Map.of(),
                Acknowledger.NONE);
    }

    /** An upsert carrying the source's own key (a Kafka message key, a JDBC row key). */
    public static InboundRecord of(Object data, String key) {
        return new InboundRecord(data, PayloadType.UNSET, key, Action.UPSERT, NO_SEQNO, Map.of(),
                Acknowledger.NONE);
    }

    /**
     * A removal. {@code data} may be {@code ""} or {@code null} when the key alone identifies
     * the record (a Kafka tombstone), or a payload carrying the key fields when the target
     * topic derives its key server-side and the delete has to be expressed as a filter.
     */
    public static InboundRecord delete(Object data, String key) {
        return new InboundRecord(data, PayloadType.UNSET, key, Action.DELETE, NO_SEQNO, Map.of(),
                Acknowledger.NONE);
    }

    /** This record with its payload typed; content and identity kept. */
    public InboundRecord withType(PayloadType type) {
        return new InboundRecord(data, type, key, action, seqno, attributes, acknowledger);
    }

    /** This record at a position in its source's stream; content and identity kept. */
    public InboundRecord withSeqno(long seqno) {
        return new InboundRecord(data, type, key, action, seqno, attributes, acknowledger);
    }

    /** This record with its stream's acknowledger attached; content and identity kept. */
    public InboundRecord withAck(Acknowledger acknowledger) {
        return new InboundRecord(data, type, key, action, seqno, attributes, acknowledger);
    }

    /** This record with transport metadata attached; content and identity kept. */
    public InboundRecord withAttributes(Map<String, String> attributes) {
        return new InboundRecord(data, type, key, action, seqno, attributes, acknowledger);
    }

    /**
     * The payload as text: a {@code String} as it is, a {@code byte[]} decoded as UTF-8,
     * {@code ""} for {@code null}, and {@link String#valueOf(Object)} for a typed object --
     * which is a diagnostic, not a wire form.
     */
    public String text() {
        return Payloads.text(data);
    }

    /** Whether there is a payload at all: not {@code null}, not an empty string or array. */
    public boolean hasData() {
        if (data == null) {
            return false;
        }
        if (data instanceof String text) {
            return !text.isEmpty();
        }
        if (data instanceof byte[] bytes) {
            return bytes.length > 0;
        }
        return true;
    }

    /**
     * Tell the source this record reached AMPS -- called by the batch publisher on every
     * record of a batch once its {@code flush()} succeeded, and never otherwise.
     *
     * <p>Cumulative, per stream: it is {@link Acknowledger#ack(long)} with this record's own
     * {@link #seqno()}. Always safe to call, because a source with nothing to acknowledge
     * attached {@link Acknowledger#NONE}.
     */
    public void ack() {
        acknowledger.ack(seqno);
    }

    /**
     * Tell the source a position on this record's stream has been reached.
     *
     * @param seqno the position, normally this record's own
     */
    public void ack(long seqno) {
        acknowledger.ack(seqno);
    }

    /**
     * Tell the source a run of positions on this record's stream has been reached.
     *
     * @param fromSeqno the first position in the run
     * @param toSeqno the last position in the run
     */
    public void ackBatch(long fromSeqno, long toSeqno) {
        acknowledger.ackBatch(fromSeqno, toSeqno);
    }
}
