package com.demo.amps.fix42.state.publish;

/**
 * The two commands the publisher issues, and the store it issues them
 * through. {@link AmpsPublishSink} is the AMPS client; a test can stand in a
 * recording sink and check the order of publishes without a server.
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
