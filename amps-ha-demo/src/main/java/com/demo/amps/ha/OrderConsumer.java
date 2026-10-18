package com.demo.amps.ha;

import com.crankuptheamps.client.BookmarkStore;
import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.fields.Field;
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
 * <p>The subscription asks for {@code fully_durable}, and that option is the
 * consumer's half of the no-loss guarantee. Without it AMPS delivers a message
 * as soon as the instance the consumer is connected to has journaled it, which
 * can be BEFORE the other instance has it. If that instance then dies, the
 * consumer's most recent bookmark names a message the survivor never received:
 * the survivor cannot resume from it, and when the publisher replays the same
 * message the survivor gives it a new bookmark, so the consumer's store cannot
 * recognise it either -- a duplicate, or worse. With {@code fully_durable} a
 * message is delivered only once every synchronous replication destination has
 * acknowledged it, so every bookmark the consumer ever holds exists on both
 * instances. The cost is latency (a replication round trip before delivery)
 * and, while the other instance is down, a pause until the survivor downgrades
 * its link -- the instance configs schedule that.
 *
 * <p>Ordering inside the handler: record, then discard the bookmark. A crash
 * between the two redelivers a message the ledger then counts as a duplicate;
 * a crash before recording redelivers one it has never seen. That is
 * at-least-once, and the ledger is what shows whether it ever became
 * more-than-once in practice. The discard is also what moves the recovery
 * point the store's adapter replicates (see {@link HaClients}): a replacement
 * consumer resumes at or before the last message discarded here, never after
 * one still being processed.
 */
public final class OrderConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    private final HAClient client;
    private final String topic;
    private final String subId;
    private final ConcurrentMap<String, SequenceLedger> ledgers = new ConcurrentHashMap<>();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong unrecognised = new AtomicLong();
    private volatile String startedFrom;
    private volatile String lastBookmark;

    /**
     * @param client a connected HA client with a bookmark store
     * @param topic  the replicated SOW topic
     * @param subId  the subscription id -- what the bookmark store, and the
     *               recovery point topic, file its position under, so it must
     *               be stable across runs
     */
    public OrderConsumer(HAClient client, String topic, String subId) {
        this.client = client;
        this.topic = topic;
        this.subId = subId;
    }

    /**
     * Starts the subscription from {@code bookmark} -- {@code EPOCH} for
     * everything the journal holds, {@code NOW} for new messages only, or
     * {@code MOST_RECENT} to resume where the bookmark store says, which after
     * a restart is what the store recovered from its recovery point adapter.
     *
     * <p>{@code EPOCH} first purges the subscription's state from the store
     * and, through the adapter, from the recovery point topic. A store that
     * remembered the previous position would otherwise filter the first part
     * of the replay as already seen, and "epoch" would quietly mean "since the
     * recovery point". Starting over means starting over.
     */
    public void start(String bookmark) throws AMPSException {
        BookmarkStore store = client.getBookmarkStore();
        Field subIdField = new Field(subId);
        if (Client.Bookmarks.EPOCH.equals(bookmark)) {
            store.purge(subIdField);
        }
        String from = Client.Bookmarks.MOST_RECENT.equals(bookmark)
                ? store.getMostRecent(subIdField).toString()
                : bookmark;
        startedFrom = from;
        Command subscribe = new Command("subscribe")
                .setTopic(topic)
                .setSubId(subId)
                .setBookmark(bookmark)
                .setOptions(Message.Options.FullyDurable);
        client.executeAsync(subscribe, this::onMessage);
        log.info("[{}] subscribed to {} as '{}' from {} (asked for '{}', fully_durable)", client.getName(), topic,
                subId, describe(from), bookmark);
    }

    private void onMessage(Message message) {
        if (message.getCommand() != Message.Command.Publish || message.isDataNull()) {
            return;
        }
        received.incrementAndGet();
        lastBookmark = message.getBookmark();
        OrderRecord.RunAndSeq record;
        try {
            record = OrderRecord.runAndSeqOf(message.getData());
        } catch (RuntimeException e) {
            record = null;
        }
        if (record == null) {
            unrecognised.incrementAndGet();
            log.warn("[{}] not one of ours, skipping: {}", client.getName(), message.getData());
        } else {
            SequenceLedger.Outcome outcome = ledger(record.run()).record(record.seq());
            if (outcome == SequenceLedger.Outcome.DUPLICATE) {
                log.warn("[{}] DUPLICATE run {} seq {} (bookmark {})", client.getName(),
                        record.run(), record.seq(), message.getBookmark());
            }
        }
        // Processed: only now may the bookmark advance past it.
        try {
            client.getBookmarkStore().discard(message);
        } catch (AMPSException e) {
            log.warn("[{}] could not discard bookmark {}: {}", client.getName(), message.getBookmark(), e.toString());
        }
    }

    /**
     * The bookmark the subscription was issued with: the literal for
     * {@code EPOCH} and {@code NOW}, the store's position for
     * {@code MOST_RECENT} -- which is {@code EPOCH} when it had none.
     * {@code null} before {@link #start}.
     */
    public String startedFrom() {
        return startedFrom;
    }

    /** The bookmark of the last message handed to the handler, or {@code null} before the first. */
    public String lastBookmark() {
        return lastBookmark;
    }

    /** A bookmark in words: the two well-known values by name, any other as it is. */
    public static String describe(String bookmark) {
        if (Client.Bookmarks.EPOCH.equals(bookmark)) {
            return "EPOCH (the start of the journal)";
        }
        if (Client.Bookmarks.NOW.equals(bookmark)) {
            return "NOW (new messages only)";
        }
        return "bookmark '" + bookmark + "'";
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
        return received.get();
    }

    /** Messages on the topic that were not {@link OrderRecord}s. */
    public long unrecognised() {
        return unrecognised.get();
    }

    public HAClient client() {
        return client;
    }

    @Override
    public void close() {
        client.close();
    }
}
