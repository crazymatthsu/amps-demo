package com.demo.amps.connectors.amps;

import com.crankuptheamps.client.exception.AMPSException;
import java.time.Duration;

/**
 * The publish side of one connector: everything the pipeline needs from an AMPS connection,
 * and nothing else.
 *
 * <p>An interface for two reasons. The obvious one is that a test can record calls instead of
 * connecting ({@code RecordingAmpsPublisher} in the test fixtures). The less obvious one is
 * that it keeps the AMPS client's shape out of the pipeline: a connector is one topic, one
 * message type and one client, so "which URI, which publish store, how long a reconnect waits"
 * belongs to the implementation rather than to every caller.
 *
 * <p>Every command returns the <em>client sequence number</em> the AMPS client assigned it --
 * the number the publish store keys its replay by, and the one the server's persisted acks
 * count up to. It is {@code 0} when there is no publish store ({@code publish-store: NONE})
 * and nothing was assigned; otherwise it is what the batch publisher records on the
 * {@code MessageContext} as its out-side sequence, so a later persisted ack can be matched
 * back to the record it covers.
 *
 * <p>The payload is an {@code Object}: a {@code String} for a text topic or a {@code byte[]}
 * from a codec that writes a binary one; an implementation sends bytes as bytes and anything
 * else as text (see {@link com.demo.amps.connectors.codec.Payloads#text}).
 *
 * <p>{@link #flush(Duration)} is the load-bearing method. Publishing is asynchronous -- the
 * calls below return as soon as the client has the message -- and it is the flush that says
 * everything issued so far has been acknowledged as persisted by AMPS. That boolean is what
 * the batch publisher turns into acknowledgments back to the sources, so a publisher that
 * returned {@code true} without waiting would silently convert at-least-once into
 * at-most-once.
 */
public interface AmpsPublisher extends AutoCloseable {

    /**
     * Connect and log on.
     *
     * @throws AMPSException if the connection or the logon failed; the connector retries
     */
    void connect() throws AMPSException;

    /** Whether the connection is currently up. Drives the status line. */
    boolean isConnected();

    /**
     * @param topic the AMPS topic
     * @param data the payload: text or bytes
     * @param sowKey the SowKey header, or {@code null} when the topic's {@code <Key>} derives it
     * @return the client sequence number assigned to the command, or {@code 0} without a store
     */
    long publish(String topic, Object data, String sowKey);

    /**
     * Publish only the fields present, for AMPS to merge over the stored record.
     *
     * @param topic the AMPS topic
     * @param data the partial payload: text or bytes
     * @param sowKey the SowKey header, or {@code null} when the topic's {@code <Key>} derives it
     * @return the client sequence number assigned to the command, or {@code 0} without a store
     */
    long deltaPublish(String topic, Object data, String sowKey);

    /**
     * Remove a record by the key the publisher assigned it.
     *
     * @param topic the AMPS topic
     * @param sowKey the SowKey of the record to remove
     * @return the client sequence number assigned to the command, or {@code 0} without a store
     */
    long sowDeleteByKey(String topic, String sowKey);

    /**
     * Remove whatever a filter matches -- how a delete is expressed against a topic whose own
     * {@code <Key>} does the keying.
     *
     * @param topic the AMPS topic
     * @param filter an AMPS filter, e.g. {@code /11 = 'ORD-1'}
     * @return the client sequence number assigned to the command, or {@code 0} without a store
     */
    long sowDeleteByFilter(String topic, String filter);

    /**
     * Wait for everything published so far to be acknowledged as persisted.
     *
     * @param timeout how long to wait
     * @return {@code true} when everything is persisted; {@code false} on a timeout or a
     *     disconnect, which is not data loss -- the publish store replays after a reconnect --
     *     but does mean the batch's records must not be acknowledged yet
     */
    boolean flush(Duration timeout);

    @Override
    void close();
}
