package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.codec.Payloads;
import com.demo.amps.connectors.source.InboundRecord;
import java.util.Objects;

/**
 * The outbound record: one AMPS command the pipeline decided on, waiting for its batch.
 *
 * <p>Named for symmetry with {@link InboundRecord}, but not a unit of data: it is the pending
 * request to the publisher -- the topic, the command, the payload in the wire form the
 * target wants, and the SOW key or the delete filter that addresses the record. The whole
 * output of {@link RecordPipeline}: by the time one exists, the payload is decoded, filtered,
 * transformed, keyed and encoded, and nothing downstream has to look at the connector's
 * configuration again. That is what lets the batch publisher be a loop over a list and one
 * flush.
 *
 * <p>The payload is an {@code Object} for the same reason the inbound one is: a {@code String}
 * for a text topic, a {@code byte[]} from a codec that writes a binary one, or -- when the
 * pipeline passed the record through -- whatever the source delivered. {@link #type()} says
 * which codec wrote it, {@link PayloadType#UNSET} for text by {@code message-type}. It carries
 * no reference back to the inbound record: the two halves meet in the {@link MessageContext}
 * the pipeline returns, which is where acknowledgment lives.
 *
 * @param topic the AMPS topic to publish onto
 * @param command which command carries it
 * @param data the payload; {@code null} for a {@link Command#SOW_DELETE}
 * @param type what the payload is: the target's {@code payload-type}, or
 *     {@link PayloadType#UNSET} for text by {@code message-type}. Never {@code null}
 * @param sowKey the SowKey header to send, or {@code null} when the topic's own {@code <Key>}
 *     derives it from the payload
 * @param deleteFilter the AMPS filter that identifies the record to delete, or {@code null};
 *     only a {@link Command#SOW_DELETE} against a server-keyed topic sets it
 */
public record OutboundRecord(
        String topic,
        Command command,
        Object data,
        PayloadType type,
        String sowKey,
        String deleteFilter) {

    /** Canonical constructor: an unset type stands in for {@code null}; topic and command required. */
    public OutboundRecord {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(command, "command");
        type = type == null ? PayloadType.UNSET : type;
    }

    /** An upsert: {@link Command#PUBLISH} or {@link Command#DELTA_PUBLISH}. */
    public static OutboundRecord publish(
            String topic, Command command, Object data, PayloadType type, String sowKey) {
        return new OutboundRecord(topic, command, data, type, sowKey, null);
    }

    /** A removal addressed by its SOW key, for a topic the publisher keys. */
    public static OutboundRecord deleteByKey(String topic, PayloadType type, String sowKey) {
        return new OutboundRecord(topic, Command.SOW_DELETE, null, type, sowKey, null);
    }

    /** A removal addressed by a filter, for a topic whose own {@code <Key>} does the keying. */
    public static OutboundRecord deleteByFilter(String topic, PayloadType type, String filter) {
        return new OutboundRecord(topic, Command.SOW_DELETE, null, type, null, filter);
    }

    /**
     * The payload as text: a {@code String} as it is, a {@code byte[]} decoded as UTF-8,
     * {@code ""} for a delete, and {@link String#valueOf(Object)} for a typed object.
     */
    public String text() {
        return Payloads.text(data);
    }
}
