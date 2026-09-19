package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.source.SourceRecord;

/**
 * One AMPS command the pipeline decided on, waiting for its batch.
 *
 * <p>The whole output of {@link RecordPipeline}: by the time a request exists, the payload is
 * decoded, filtered, transformed, keyed and encoded, and nothing downstream has to look at the
 * connector's configuration again. That is what lets the batch publisher be a loop over a list
 * and one flush.
 *
 * <p>The {@link #record()} rides along for exactly one reason: acknowledgment. After a batch's
 * flush succeeds every request's record is acknowledged, which is what commits a Kafka offset
 * or persists a JDBC watermark -- and a failed flush leaves them unacknowledged so the source
 * re-reads them. That is the at-least-once contract, held in this one field.
 *
 * @param topic the AMPS topic to publish onto
 * @param command which command carries it
 * @param data the payload; {@code ""} for a {@link Command#SOW_DELETE}
 * @param sowKey the SowKey header to send, or {@code null} when the topic's own {@code <Key>}
 *     derives it from the payload
 * @param deleteFilter the AMPS filter that identifies the record to delete, or {@code null};
 *     only a {@link Command#SOW_DELETE} against a server-keyed topic sets it
 * @param record the source record, for its acknowledgment
 */
public record PublishRequest(
        String topic,
        Command command,
        String data,
        String sowKey,
        String deleteFilter,
        SourceRecord record) {

    /** An upsert: {@link Command#PUBLISH} or {@link Command#DELTA_PUBLISH}. */
    public static PublishRequest publish(
            String topic, Command command, String data, String sowKey, SourceRecord record) {
        return new PublishRequest(topic, command, data, sowKey, null, record);
    }

    /** A removal addressed by its SOW key, for a topic the publisher keys. */
    public static PublishRequest deleteByKey(String topic, String sowKey, SourceRecord record) {
        return new PublishRequest(topic, Command.SOW_DELETE, "", sowKey, null, record);
    }

    /** A removal addressed by a filter, for a topic whose own {@code <Key>} does the keying. */
    public static PublishRequest deleteByFilter(
            String topic, String filter, SourceRecord record) {
        return new PublishRequest(topic, Command.SOW_DELETE, "", null, filter, record);
    }
}
