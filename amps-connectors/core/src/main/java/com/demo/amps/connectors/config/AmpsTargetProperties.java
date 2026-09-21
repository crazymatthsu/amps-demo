package com.demo.amps.connectors.config;

import com.demo.amps.connectors.codec.PayloadType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The AMPS side of one connector: which topic the records land on, and how they get there.
 *
 * <p>{@link #getMessageType() message-type} does double duty, and that is an AMPS fact rather
 * than a choice made here: it selects the outbound encoder <em>and</em> the client URI
 * ({@code /amps/fix} vs {@code /amps/json}), because a connection speaks one message type.
 * It must match what the topic's own definition in the server flow declares.
 *
 * <p>{@link #getPayloadType() payload-type} is the exception to "the message type selects
 * the encoder": set, it names a registered codec that writes the payload instead, which is
 * how a typed record reaches a {@code protobuf} or {@code binary} topic -- or a {@code json}
 * one through the codec's own JSON rendering. Left at {@code 0/0}, the message type's text
 * encoder writes the field map, as it always has.
 *
 * <pre>{@code
 * amps:
 *   topic: sow/connectors/orders
 *   message-type: fix
 *   command: PUBLISH
 *   key: { fields: [ "11" ], mode: SERVER }
 *   batch: { max-messages: 500, flush-interval: 250ms }
 *   # payload-type: { factory-id: 100, class-id: 1 }   # a codec writes the payload
 * }</pre>
 */
public class AmpsTargetProperties {

    /** Which publish command carries the record. */
    public enum Command {
        /** A whole record; the SOW row becomes exactly this payload. */
        PUBLISH,
        /**
         * Only the fields present; AMPS merges them over the stored record, so omitted fields
         * keep their values. Needs a key -- there is nothing to merge onto without one.
         */
        DELTA_PUBLISH
    }

    /** What a {@code DELETE} record does. */
    public enum OnDelete {
        /** Remove the record from the SOW. */
        SOW_DELETE,
        /** Count it and drop it -- for a journal topic, where there is nothing to delete. */
        IGNORE
    }

    /** Whether the original payload bytes may be published unchanged. */
    public enum Passthrough {
        /**
         * Pass through when it is safe to: the record's payload type is the target's -- for
         * text, the source format matches the message type -- and no transform touched the
         * record. Otherwise encode the field map.
         */
        AUTO,
        /** Always publish the original bytes, even after transforms. Filtering still applies. */
        ALWAYS,
        /** Always encode the field map, even when the bytes would have survived a round trip. */
        NEVER
    }

    /** The AMPS topic to publish onto. Must exist in the server's flow configuration. */
    @NotBlank
    private String topic;

    /**
     * {@code json}, {@code fix}, {@code nvfix}, {@code protobuf} or {@code binary} -- validated
     * against that set rather than modelled as an enum, because it is written into a URI and
     * has to read as AMPS spells it. The first three have a text encoder of their own; the
     * other two are written by the codec {@link #getPayloadType() payload-type} names.
     */
    @NotBlank
    private String messageType = "json";

    /**
     * The codec that writes the payload, as the {@link PayloadType} it is registered under;
     * {@code 0/0} means "none: the message type's text encoder does".
     */
    @Valid
    @NotNull
    private PayloadTypeProperties payloadType = new PayloadTypeProperties();

    @NotNull
    private Command command = Command.PUBLISH;

    /** How the record's SOW key is determined. Optional; absent means an unkeyed publish. */
    @Valid
    private KeyProperties key;

    @NotNull
    private OnDelete onDelete = OnDelete.SOW_DELETE;

    @NotNull
    private Passthrough passthrough = Passthrough.AUTO;

    /** How many records ride on one flush, and how long a partial batch waits. */
    @Valid
    @NotNull
    private BatchProperties batch = new BatchProperties();

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public Command getCommand() {
        return command;
    }

    public void setCommand(Command command) {
        this.command = command;
    }

    public KeyProperties getKey() {
        return key;
    }

    public void setKey(KeyProperties key) {
        this.key = key;
    }

    public OnDelete getOnDelete() {
        return onDelete;
    }

    public void setOnDelete(OnDelete onDelete) {
        this.onDelete = onDelete;
    }

    public Passthrough getPassthrough() {
        return passthrough;
    }

    public void setPassthrough(Passthrough passthrough) {
        this.passthrough = passthrough;
    }

    public BatchProperties getBatch() {
        return batch;
    }

    public void setBatch(BatchProperties batch) {
        this.batch = batch == null ? new BatchProperties() : batch;
    }

    public PayloadTypeProperties getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(PayloadTypeProperties payloadType) {
        this.payloadType = payloadType == null ? new PayloadTypeProperties() : payloadType;
    }

    /**
     * The two ids of a {@link PayloadType}, as configuration binds them.
     *
     * <pre>{@code
     * payload-type: { factory-id: 100, class-id: 1 }
     * }</pre>
     *
     * <p>A mutable bean rather than the record itself so that the binder has setters to call
     * and the validator has raw ints to judge; {@link #toPayloadType()} is where the pair
     * becomes the value the registry is keyed by.
     */
    public static class PayloadTypeProperties {

        /** The serialization family; {@code 0} with a class id of {@code 0} means unset. */
        private int factoryId;

        /** The concrete type within the family; {@code 0} with a factory id of {@code 0} means unset. */
        private int classId;

        public int getFactoryId() {
            return factoryId;
        }

        public void setFactoryId(int factoryId) {
            this.factoryId = factoryId;
        }

        public int getClassId() {
            return classId;
        }

        public void setClassId(int classId) {
            this.classId = classId;
        }

        /**
         * The pair as a {@link PayloadType}.
         *
         * @return {@link PayloadType#UNSET} for {@code 0/0}, else the type
         * @throws IllegalArgumentException if the pair is half set or negative -- what the
         *     validator reports readably, and what stops a hand-built pipeline
         */
        public PayloadType toPayloadType() {
            return PayloadType.of(factoryId, classId);
        }

        @Override
        public String toString() {
            return factoryId + "/" + classId;
        }
    }
}
