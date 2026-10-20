package com.demo.amps.ha.api;

import java.time.Duration;
import java.util.Objects;

/**
 * The SOW topic a subscriber's bookmarks are replicated to, so that a
 * subscriber restarted anywhere under the same client name resumes where the
 * previous one stopped.
 *
 * <p>The AMPS client's {@code SOWRecoveryPointAdapter} writes one JSON record
 * per (client name, subscription id), carrying the bookmark the subscription
 * resumes from:
 *
 * <pre>{"clientName":"orders-consumer","subId":"orders","bookmark":"17434824616529380601|1200|"}</pre>
 *
 * <p>The server side has to exist before a subscriber can use it. On EVERY
 * instance the subscriber may connect to, the topic must be configured as a
 * JSON SOW topic keyed on {@code /clientName} and {@code /subId} (the two
 * field names here), listed in the transaction log, and -- when the instances
 * replicate -- in the replication destination's topic list, so the record
 * reaches the survivor:
 *
 * <pre>{@code
 * <SOW>
 *   <Topic>
 *     <Name>/ADMIN/bookmark_store</Name>
 *     <MessageType>json</MessageType>
 *     <Key>/clientName</Key>
 *     <Key>/subId</Key>
 *     <FileName>./sow/bookmark_store.sow</FileName>
 *     <Durability>persistent</Durability>
 *   </Topic>
 * </SOW>
 * <TransactionLog> ... <Topic><Name>/ADMIN/bookmark_store</Name><MessageType>json</MessageType></Topic> ... </TransactionLog>
 * <Replication><Destination> ... <Topic><Name>/ADMIN/bookmark_store</Name><MessageType>json</MessageType></Topic> ... </Destination></Replication>
 * }</pre>
 *
 * @param topic           the SOW topic
 * @param clientNameField the JSON field holding the client name; the topic's first key
 * @param subIdField      the JSON field holding the subscription id; the topic's second key
 * @param bookmarkField   the JSON field holding the bookmark
 * @param timeout         bound on the adapter's SOW query when a store is created, and
 *                        on its flush when the store is closed; the library's default
 *                        is to wait forever
 */
public record RecoveryPointTopic(String topic, String clientNameField, String subIdField, String bookmarkField,
                                 Duration timeout) {

    /** The AMPS Java client's own default topic for a {@code SOWRecoveryPointAdapter}. */
    public static final String DEFAULT_TOPIC = "/ADMIN/bookmark_store";
    /** The adapter's default field names. */
    public static final String DEFAULT_CLIENT_NAME_FIELD = "clientName";
    public static final String DEFAULT_SUB_ID_FIELD = "subId";
    public static final String DEFAULT_BOOKMARK_FIELD = "bookmark";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public RecoveryPointTopic {
        requireText(topic, "topic");
        requireText(clientNameField, "clientNameField");
        requireText(subIdField, "subIdField");
        requireText(bookmarkField, "bookmarkField");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, not " + timeout);
        }
    }

    /** {@link #DEFAULT_TOPIC} with the adapter's default field names. */
    public static RecoveryPointTopic defaults() {
        return of(DEFAULT_TOPIC);
    }

    /** {@code topic} with the adapter's default field names. */
    public static RecoveryPointTopic of(String topic) {
        return new RecoveryPointTopic(topic, DEFAULT_CLIENT_NAME_FIELD, DEFAULT_SUB_ID_FIELD, DEFAULT_BOOKMARK_FIELD,
                DEFAULT_TIMEOUT);
    }

    public RecoveryPointTopic withTimeout(Duration value) {
        return new RecoveryPointTopic(topic, clientNameField, subIdField, bookmarkField, value);
    }

    /** The SOW filter selecting one client's records. */
    public String filterFor(String clientName) {
        return "/" + clientNameField + " = '" + clientName.replace("'", "''") + "'";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
