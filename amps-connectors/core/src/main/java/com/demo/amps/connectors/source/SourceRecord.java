package com.demo.amps.connectors.source;

import java.util.Map;
import java.util.Objects;

/**
 * One message delivered by a {@link RecordSource}, before decoding.
 *
 * <p>The seam between a transport and the pipeline, and deliberately narrow: a payload string,
 * whatever the source calls its own key, whether the message asserts or removes the record,
 * transport metadata, and the hook that tells the source the record reached AMPS. Everything
 * that turns this into a publish -- decoding, filtering, transforming, keying, encoding -- is
 * the pipeline's business, so a new driver is a reader loop and nothing else.
 *
 * @param data the raw payload: FIX, NVFIX, JSON or plain text. Never {@code null}, but a
 *     {@link Action#DELETE} may carry {@code ""} when the key alone identifies the record
 * @param key the source's own key for the record (a Kafka message key, the joined JDBC key
 *     columns), or {@code null} for a source that does not key its messages
 * @param action whether the message asserts the record or removes it
 * @param attributes transport metadata as strings -- Kafka's {@code topic}/{@code partition}/
 *     {@code offset}, TCP's {@code remote}, JDBC's {@code poll}, Hazelcast's
 *     {@code publishTime}/{@code member}. Immutable, never {@code null}
 * @param ack what to call once the record has been published and the batch flushed, or
 *     {@code null} for a source with nothing to acknowledge
 */
public record SourceRecord(
        String data, String key, Action action, Map<String, String> attributes, Acknowledgment ack) {

    /** What the message says about the record. */
    public enum Action {
        /** The record exists with this content. */
        UPSERT,
        /** The record is gone: a Kafka tombstone, a row that vanished between JDBC polls. */
        DELETE
    }

    /**
     * Canonical constructor: defensive copy of the attributes so a source that reuses a map
     * across records cannot mutate one already in flight.
     */
    public SourceRecord {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        Objects.requireNonNull(action, "action");
    }

    /** An upsert with no source key -- the journal-feed shape. */
    public static SourceRecord of(String data) {
        return new SourceRecord(data, null, Action.UPSERT, Map.of(), null);
    }

    /** An upsert carrying the source's own key (a Kafka message key, a JDBC row key). */
    public static SourceRecord of(String data, String key) {
        return new SourceRecord(data, key, Action.UPSERT, Map.of(), null);
    }

    /**
     * A removal. {@code data} may be {@code ""} when the key alone identifies the record (a
     * Kafka tombstone), or a payload carrying the key fields when the target topic derives its
     * key server-side and the delete has to be expressed as a filter.
     */
    public static SourceRecord delete(String data, String key) {
        return new SourceRecord(data, key, Action.DELETE, Map.of(), null);
    }

    /** This record with an acknowledgment attached; content and identity kept. */
    public SourceRecord withAck(Acknowledgment ack) {
        return new SourceRecord(data, key, action, attributes, ack);
    }

    /** This record with transport metadata attached; content and identity kept. */
    public SourceRecord withAttributes(Map<String, String> attributes) {
        return new SourceRecord(data, key, action, attributes, ack);
    }

    /**
     * Tell the source the record reached AMPS -- called by the batch publisher on every record
     * of a batch once its {@code flush()} succeeded, and never otherwise.
     *
     * <p>Null-safe, because most sources have nothing to acknowledge: a socket and a Hazelcast
     * topic cannot rewind, so they attach no {@link Acknowledgment} and this does nothing.
     */
    public void acknowledge() {
        if (ack != null) {
            ack.acknowledge();
        }
    }
}
