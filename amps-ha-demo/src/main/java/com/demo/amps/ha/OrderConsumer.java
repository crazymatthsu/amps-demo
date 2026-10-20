package com.demo.amps.ha;

import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.ha.api.BookmarkStart;
import com.demo.amps.ha.api.HaBookmarkSubscriber;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bookmark subscription on the replicated topic, keeping a
 * {@link SequenceLedger} per publisher run.
 *
 * <p>What makes the consuming side of a failover lossless -- the bookmark
 * store, {@code fully_durable}, the recovery point replicated to the pair,
 * and the rule that a message's bookmark is discarded only after it has been
 * processed -- lives in {@link HaBookmarkSubscriber}. This class is the
 * processor: it records each message in the ledger of its run, and returns.
 * A crash after recording and before the subscriber discards redelivers a
 * message the ledger then counts as a duplicate; a crash before recording
 * redelivers one it has never seen. That is at-least-once, and the ledger is
 * what shows whether it ever became more-than-once in practice.
 */
public final class OrderConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    private final HaBookmarkSubscriber subscriber;
    private final String topic;
    private final String subId;
    private final ConcurrentMap<String, SequenceLedger> ledgers = new ConcurrentHashMap<>();
    private final AtomicLong unrecognised = new AtomicLong();
    private volatile HaBookmarkSubscriber.Subscription subscription;

    /**
     * @param subscriber a connected HA bookmark subscriber
     * @param topic      the replicated SOW topic
     * @param subId      the subscription id -- what the bookmark store, and the
     *                   recovery point topic, file its position under, so it must
     *                   be stable across runs
     */
    public OrderConsumer(HaBookmarkSubscriber subscriber, String topic, String subId) {
        this.subscriber = subscriber;
        this.topic = topic;
        this.subId = subId;
    }

    /** Starts the subscription from {@code start}; see {@link BookmarkStart} for what each value means. */
    public void start(BookmarkStart start) throws AMPSException {
        subscription = subscriber.subscribe(topic, subId, start, this::onMessage);
    }

    private void onMessage(Message message) {
        OrderRecord.RunAndSeq record;
        try {
            record = OrderRecord.runAndSeqOf(message.getData());
        } catch (RuntimeException e) {
            record = null;
        }
        if (record == null) {
            unrecognised.incrementAndGet();
            log.warn("[{}] not one of ours, skipping: {}", subscriber.name(), message.getData());
            return;
        }
        SequenceLedger.Outcome outcome = ledger(record.run()).record(record.seq());
        if (outcome == SequenceLedger.Outcome.DUPLICATE) {
            log.warn("[{}] DUPLICATE run {} seq {} (bookmark {})", subscriber.name(),
                    record.run(), record.seq(), message.getBookmark());
        }
    }

    /**
     * The bookmark the subscription was issued with: the literal for
     * {@code EPOCH} and {@code NOW}, the store's position for
     * {@code MOST_RECENT} -- which is {@code EPOCH} when it had none.
     * {@code null} before {@link #start}.
     */
    public String startedFrom() {
        HaBookmarkSubscriber.Subscription current = subscription;
        return current == null ? null : current.startedFrom();
    }

    /** The bookmark of the last message handed to the handler, or {@code null} before the first. */
    public String lastBookmark() {
        HaBookmarkSubscriber.Subscription current = subscription;
        return current == null ? null : current.lastBookmark();
    }

    /** The ledger for {@code run}, created on first sight. */
    public SequenceLedger ledger(String run) {
        return ledgers.computeIfAbsent(run, r -> new SequenceLedger());
    }

    /** Every run seen so far and its ledger. */
    public Map<String, SequenceLedger> ledgers() {
        return Map.copyOf(ledgers);
    }

    /** Publish messages delivered to the handler, duplicates included. */
    public long received() {
        HaBookmarkSubscriber.Subscription current = subscription;
        return current == null ? 0 : current.received();
    }

    /** Messages on the topic that were not {@link OrderRecord}s. */
    public long unrecognised() {
        return unrecognised.get();
    }

    public HaBookmarkSubscriber subscriber() {
        return subscriber;
    }

    @Override
    public void close() {
        subscriber.close();
    }
}
