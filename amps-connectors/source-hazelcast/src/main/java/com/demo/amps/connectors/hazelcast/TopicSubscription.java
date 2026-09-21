package com.demo.amps.connectors.hazelcast;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.demo.amps.connectors.source.InboundRecord;
import com.demo.amps.connectors.source.RecordHandler;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import com.hazelcast.topic.Message;
import com.hazelcast.topic.ReliableMessageListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Hazelcast topic read as a stream of keyless upserts.
 *
 * <h2>Fire-and-forget, unless the topic is reliable</h2>
 *
 * <p>Which structure is read follows {@code reliable}, and the two differ in exactly the way
 * that matters to a bridge:
 *
 * <table border="1">
 *   <caption>Topic kinds</caption>
 *   <tr><th>{@code reliable}</th><th>structure</th><th>what a restart sees</th></tr>
 *   <tr><td>{@code false}</td><td>{@code getTopic}</td>
 *       <td>nothing published before the listener was registered -- a plain topic keeps no
 *           history at all</td></tr>
 *   <tr><td>{@code true}</td><td>{@code getReliableTopic}</td>
 *       <td>with {@code reliable-from: OLDEST}, whatever the backing ringbuffer still holds;
 *           with {@code NEWEST}, only what comes next</td></tr>
 * </table>
 *
 * <p>So a plain topic loses every message published while the connector was down, and that is
 * a property of the transport rather than of this driver: a feed that must survive a restart
 * is configured {@code reliable: true} (and sized by the cluster's ringbuffer capacity), or it
 * does not survive one.
 *
 * <p>Either way there is <strong>nothing to acknowledge</strong>. A plain topic has no
 * position, and a reliable one is read through a listener whose sequence Hazelcast itself
 * advances as it delivers -- there is no point the connector could ask it to go back to once
 * AMPS has confirmed a batch. Records therefore carry
 * {@link com.demo.amps.connectors.source.Acknowledger#NONE}, and no key either: a topic
 * message is a payload and nothing more, so a keyed AMPS topic gets its key from the payload.
 * A feed whose records have identity belongs in a map ({@link MapSubscription}), not a topic.
 *
 * <p>What a record <em>does</em> carry is a position. On a reliable topic it is the
 * ringbuffer sequence Hazelcast hands the listener just before each message -- the one
 * number that says where in the topic a message sat, and what {@code reliable-from} resumes
 * by -- and on a plain topic, which has no sequence at all, it is a delivery counter that
 * runs for the life of the subscription. The payload follows {@link HazelcastValues}: text
 * as it is, an object rendered as JSON, or -- under {@code typed-values: OBJECT} -- an
 * {@code IdentifiedDataSerializable} handed through as itself under its ids.
 */
final class TopicSubscription implements HazelcastSubscription {

    private static final Logger log = LoggerFactory.getLogger(TopicSubscription.class);

    private final ConnectorProperties connector;
    private final HazelcastSourceProperties source;
    private final HazelcastValues values;

    /** One WARN per source for rendered payloads: the second one says nothing new. */
    private final AtomicBoolean warnedAboutPayloadType = new AtomicBoolean(false);

    /** Numbers a plain topic's messages, which have no sequence of their own. */
    private final AtomicLong delivered = new AtomicLong();

    private volatile ITopic<Object> topic;
    private volatile UUID listener;

    TopicSubscription(ConnectorProperties connector) {
        this.connector = connector;
        this.source = connector.getSource().getHazelcast();
        this.values = new HazelcastValues(connector);
    }

    @Override
    public void subscribe(HazelcastInstance client, RecordHandler handler) {
        ITopic<Object> subscribed = source.isReliable()
                ? client.getReliableTopic(source.getTopic())
                : client.getTopic(source.getTopic());
        this.topic = subscribed;
        this.listener = source.isReliable()
                ? subscribed.addMessageListener(new ReplayingListener(handler))
                : subscribed.addMessageListener(
                        message -> deliver(message, handler, delivered.incrementAndGet()));
    }

    @Override
    public void unsubscribe() {
        ITopic<Object> subscribed = this.topic;
        UUID registration = this.listener;
        this.topic = null;
        this.listener = null;
        if (subscribed != null && registration != null) {
            try {
                subscribed.removeMessageListener(registration);
            } catch (RuntimeException e) {
                // The client may already be down, which removes the listener anyway.
                log.debug("[{}] removing the Hazelcast listener failed", connector.getName(), e);
            }
        }
    }

    @Override
    public String describe() {
        return (source.isReliable() ? "reliable" : "plain") + " topic '" + source.getTopic() + "'"
                + (source.isReliable() ? " from " + source.getReliableFrom() : "");
    }

    /**
     * A reliable-topic listener: same delivery, plus the ringbuffer position that makes
     * {@code reliable-from} mean something -- and that becomes each record's seqno.
     */
    private final class ReplayingListener implements ReliableMessageListener<Object> {

        private final RecordHandler handler;

        /**
         * The sequence of the message being delivered. Hazelcast calls
         * {@link #storeSequence} with a message's sequence immediately before it calls
         * {@link #onMessage} with the message, on the same thread, so by the time the message
         * arrives this is its position.
         */
        private volatile long sequence = -1;

        private ReplayingListener(RecordHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onMessage(Message<Object> message) {
            deliver(message, handler, sequence);
        }

        /**
         * {@code -1} is Hazelcast's "start at the next message published"; {@code 0} is the
         * head of the ringbuffer, which is what replays a backlog.
         */
        @Override
        public long retrieveInitialSequence() {
            return source.getReliableFrom() == HazelcastSourceProperties.ReliableFrom.OLDEST
                    ? 0
                    : -1;
        }

        @Override
        public void storeSequence(long sequence) {
            this.sequence = sequence;
        }

        /**
         * Tolerant: a connector that fell so far behind that the ringbuffer overwrote its
         * position should jump to the head and keep bridging. The alternative -- cancelling
         * the listener -- turns a slow patch into a silent outage.
         */
        @Override
        public boolean isLossTolerant() {
            return true;
        }

        /**
         * Never terminal. The handler already swallows its own failures, so anything arriving
         * here is Hazelcast's, and dropping the subscription over it would leave a connector
         * that looks healthy and delivers nothing.
         */
        @Override
        public boolean isTerminal(Throwable failure) {
            log.error("[{}] reliable topic '{}' listener failed at sequence {}",
                    connector.getName(), source.getTopic(), sequence, failure);
            return false;
        }
    }

    /**
     * Turn one topic message into a record.
     *
     * <p>Runs on a Hazelcast event thread, and the pipeline runs inside
     * {@link RecordHandler#onRecord}: a handler that blocks blocks this subscription, which is
     * the back-pressure the framework wants. Nothing is read ahead onto another thread.
     *
     * @param seqno the ringbuffer sequence on a reliable topic, the delivery count on a plain one
     */
    private void deliver(Message<Object> message, RecordHandler handler, long seqno) {
        try {
            Object payload = message.getMessageObject();
            InboundRecord record = values.upsert(payload, null);
            if (!HazelcastValues.isText(payload) && !record.type().isSet()
                    && warnedAboutPayloadType.compareAndSet(false, true)) {
                // Still published -- a bridge that silently dropped a feed's messages because
                // they were published as objects would be worse than one that publishes their
                // rendering. But say so once: the rendering is a guess at what the feed meant.
                log.warn("[{}] Hazelcast topic '{}' publishes {} rather than text; bridging "
                                + "its JSON rendering -- decoding may fail",
                        connector.getName(), source.getTopic(), payload.getClass().getName());
            }
            handler.onRecord(record
                    .withSeqno(seqno)
                    .withAttributes(attributesOf(message, payload)));
        } catch (RuntimeException e) {
            // One bad record is not a reason to drop the subscription.
            log.error("[{}] failed to handle Hazelcast message", connector.getName(), e);
        }
    }

    /**
     * Publish time and publisher, the only transport metadata a topic message carries -- and
     * the value's own ids when it has them.
     */
    private static Map<String, String> attributesOf(Message<Object> message, Object payload) {
        Map<String, String> attributes = new LinkedHashMap<>(6);
        attributes.put(HazelcastRecordSource.ATTRIBUTE_PUBLISH_TIME,
                Long.toString(message.getPublishTime()));
        String member = HazelcastRecordSource.addressOf(message.getPublishingMember());
        if (member != null) {
            attributes.put(HazelcastRecordSource.ATTRIBUTE_MEMBER, member);
        }
        HazelcastValues.describe(payload, attributes);
        return attributes;
    }
}
