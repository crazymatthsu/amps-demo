package com.demo.amps.connectors.runtime;

/**
 * What the batch publisher does with one {@link PublishRequest}.
 *
 * <p>A superset of the connector's configured {@code amps.command}: a connector chooses
 * between {@link #PUBLISH} and {@link #DELTA_PUBLISH} for its upserts, and
 * {@link #SOW_DELETE} is what a {@code DELETE} record becomes regardless. Keeping all three in
 * one stream is what preserves order across the boundary -- a record deleted and re-added
 * within a batch must not be resurrected by publishing every upsert first.
 */
public enum Command {

    /** Publish the whole payload; the SOW record becomes exactly this. */
    PUBLISH,

    /** Publish only the fields present; AMPS merges them over the stored record. */
    DELTA_PUBLISH,

    /** Remove the record, by SOW key or by filter. */
    SOW_DELETE
}
