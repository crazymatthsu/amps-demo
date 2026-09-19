package com.demo.amps.connectors.source;

import java.util.List;

/**
 * Callback a {@link RecordSource} invokes for every message it receives.
 *
 * <p>Called on the source's own reader thread, and the pipeline runs there: that is what gives
 * the connector back-pressure for free, because a source that cannot hand off a record is a
 * source that stops reading.
 */
@FunctionalInterface
public interface RecordHandler {

    /**
     * Handle one record.
     *
     * <p>May throw: a source treats a thrown {@code RuntimeException} as one bad record, logs
     * it and carries on reading. A reader thread never dies because the pipeline rejected
     * something.
     *
     * @param record the received record
     */
    void onRecord(SourceRecord record);

    /**
     * Handle a group of records a source read together.
     *
     * <p>The default forwards one at a time, which is what every current source needs -- a
     * Kafka poll and a JDBC result set already arrive as a list, and this is the hook for a
     * driver that later wants the whole group to cross the seam once.
     *
     * @param records the received records, in wire order
     */
    default void onBatch(List<SourceRecord> records) {
        for (SourceRecord record : records) {
            onRecord(record);
        }
    }
}
