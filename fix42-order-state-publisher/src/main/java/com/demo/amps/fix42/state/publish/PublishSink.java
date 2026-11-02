package com.demo.amps.fix42.state.publish;

/**
 * The two commands the publisher issues, and the store it issues them
 * through. {@code amps.AmpsPublishSink} is the AMPS client; a test can stand
 * in a recording sink and check the order of publishes without a server.
 *
 * <p>What a sink must provide, in full, is docs/07 §3: it merges a delta into
 * the record with the same key (13011), or its consumer does; a publish that
 * returns is durably accepted, or its refusal reaches the {@link RepairQueue};
 * and its records can be read back, through a {@code RecordSource} and a
 * {@code MissLookup}, or the publisher runs without recovery.
 */
public interface PublishSink {

    /** A whole-record publish; returns the client sequence number, 0 without a store. */
    long publish(String topic, byte[] payload, int offset, int length) throws Exception;

    /** A delta publish, merged into the record with the same key. */
    long deltaPublish(String topic, byte[] payload, int offset, int length) throws Exception;

    /** Waits until everything published so far is acknowledged as persisted. */
    void flush(long timeoutMs) throws Exception;

    /** Publishes in the store the server has not yet acknowledged. */
    long unpersisted();

    /** The highest sequence the server has acknowledged as persisted. */
    long lastPersisted();
}
