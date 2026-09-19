package com.demo.amps.connectors.config;

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
 * <pre>{@code
 * amps:
 *   topic: sow/connectors/orders
 *   message-type: fix
 *   command: PUBLISH
 *   key: { fields: [ "11" ], mode: SERVER }
 *   batch: { max-messages: 500, flush-interval: 250ms }
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
         * Pass through when it is safe to: the source format matches the message type and no
         * transform touched the record. Otherwise encode the field map.
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
     * {@code json}, {@code fix} or {@code nvfix} -- validated against that set rather than
     * modelled as an enum, because it is written into a URI and has to read as AMPS spells it.
     */
    @NotBlank
    private String messageType = "json";

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
}
