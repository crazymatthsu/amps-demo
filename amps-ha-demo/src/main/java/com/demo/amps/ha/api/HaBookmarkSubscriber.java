package com.demo.amps.ha.api;

import com.crankuptheamps.client.BookmarkStore;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.CommandId;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.LoggedBookmarkStore;
import com.crankuptheamps.client.MemoryBookmarkStore;
import com.crankuptheamps.client.MemoryPublishStore;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.RecoveryPointAdapter;
import com.crankuptheamps.client.SOWRecoveryPointAdapter;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.DisconnectedException;
import com.crankuptheamps.client.fields.Field;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bookmark subscriber that survives losing the instance it is connected
 * to, and can itself be restarted anywhere: an {@link HAClient} over every
 * instance of a pair, with a bookmark store, optionally backed by a SOW topic
 * on the pair.
 *
 * <p>After a reconnect the client re-issues each bookmark subscription from
 * the most recent bookmark its store holds, and the store filters any
 * redelivery of a message it has already seen. Bookmarks survive replication
 * unchanged, so the position means the same thing on the other instance.
 *
 * <p>With a {@link RecoveryPointTopic}, the store is backed by the AMPS
 * client's {@code SOWRecoveryPointAdapter}: it writes the position, as it
 * moves, to a SOW record keyed on the client name and the subscription id,
 * and a store created later under the same client name reads it back before
 * subscribing. So a subscriber started on another machine, with no file to
 * its name and against whichever instance survived, resumes where the
 * previous one stopped. The adapter may not share the subscriber's own client
 * (it queries and publishes while that client is busy subscribing, and the
 * library refuses the same name), so a subscriber with replicated bookmarks
 * is two HA clients: the subscriber, and a <b>tracker</b> named after it that
 * does nothing but write recovery points. The tracker carries a publish store
 * for the same reason a publisher does: a recovery point whose
 * acknowledgment died with an instance is replayed to the survivor, and one
 * that had arrived is dropped there as a duplicate.
 *
 * <p>Subscriptions ask for {@code fully_durable}, and that option is the
 * subscriber's half of the no-loss guarantee on a replicated pair. Without
 * it AMPS delivers a message as soon as the instance the subscriber is
 * connected to has journaled it, which can be BEFORE the other instance has
 * it. If that instance then dies, the subscriber's most recent bookmark names
 * a message the survivor never received: the survivor cannot resume from it,
 * and when the publisher replays the same message the survivor gives it a new
 * bookmark, so the subscriber's store cannot recognise it either -- a
 * duplicate, or worse. With {@code fully_durable} a message is delivered only
 * once every synchronous replication destination has acknowledged it, so
 * every bookmark the subscriber ever holds exists on both instances. The cost
 * is latency (a replication round trip before delivery) and, while the other
 * instance is down, a pause until the survivor downgrades its link -- which
 * the instance configs must schedule. On an instance with no synchronous
 * destination the option changes nothing.
 *
 * <p>The client name is the identity all of this hangs off: the file-backed
 * store is named by it and the recovery point is filed under it. It has to be
 * stable across runs, and unique among connected clients -- AMPS refuses a
 * second logon under a name that is in use. The subscription id is the other
 * half of the key, and must be stable too.
 *
 * <pre>{@code
 * try (HaBookmarkSubscriber subscriber = HaBookmarkSubscriber.builder("orders-consumer")
 *         .uris("tcp://primary:9007/amps/json", "tcp://secondary:9007/amps/json")
 *         .stateDir(Path.of("state"))                       // omit for an in-memory store
 *         .recoveryPointTopic(RecoveryPointTopic.defaults()) // omit to keep bookmarks local
 *         .connect()) {
 *     subscriber.subscribe("orders", "orders-consumer", BookmarkStart.MOST_RECENT, message -> {
 *         handle(message.getData());                        // returning normally = processed
 *     });
 *     ...
 * }
 * }</pre>
 */
public final class HaBookmarkSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HaBookmarkSubscriber.class);

    /** The default suffix on a subscriber's name for the tracker that writes its recovery points. */
    public static final String DEFAULT_TRACKER_SUFFIX = "-bookmarks";

    private final HAClient client;
    private final ConnectionJournal journal;
    private final BookmarkStore store;
    private final RecoveryPointTopic recoveryPointTopic;
    private final HAClient tracker;
    private final ConnectionJournal trackerJournal;
    private final boolean fullyDurable;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    private HaBookmarkSubscriber(HAClient client, ConnectionJournal journal, BookmarkStore store,
                                 RecoveryPointTopic recoveryPointTopic, HAClient tracker,
                                 ConnectionJournal trackerJournal, boolean fullyDurable) {
        this.client = client;
        this.journal = journal;
        this.store = store;
        this.recoveryPointTopic = recoveryPointTopic;
        this.tracker = tracker;
        this.trackerJournal = trackerJournal;
        this.fullyDurable = fullyDurable;
    }

    /** @param clientName the client name; see the class comment on why it must be stable */
    public static Builder builder(String clientName) {
        return new Builder(clientName);
    }

    public String name() {
        return client.getName();
    }

    /** The underlying client, for commands this class does not wrap. */
    public HAClient client() {
        return client;
    }

    /** Every connection state change so far: where the client is, and how it got there. */
    public ConnectionJournal journal() {
        return journal;
    }

    public BookmarkStore bookmarkStore() {
        return store;
    }

    /** Where the bookmarks are replicated to, or {@code null} when they stay in the local store. */
    public RecoveryPointTopic recoveryPointTopic() {
        return recoveryPointTopic;
    }

    /** The tracker's connection journal, or {@code null} when bookmarks stay local. */
    public ConnectionJournal trackerJournal() {
        return trackerJournal;
    }

    /** The subscriptions issued so far and not unsubscribed. */
    public List<Subscription> subscriptions() {
        return List.copyOf(subscriptions);
    }

    /** {@link #subscribe(String, String, BookmarkStart, String, MessageProcessor)} without a filter. */
    public Subscription subscribe(String topic, String subId, BookmarkStart start, MessageProcessor processor)
            throws AMPSException {
        return subscribe(topic, subId, start, null, processor);
    }

    /**
     * Issues a bookmark subscription.
     *
     * @param topic     the topic (a transaction-logged one; a bookmark subscription needs the journal)
     * @param subId     the subscription id -- what the bookmark store, and the
     *                  recovery point topic, file the position under, so it must
     *                  be stable across runs
     * @param start     where to start; see {@link BookmarkStart}
     * @param filter    an AMPS content filter, or {@code null} for every message
     * @param processor what to do with each message; its normal return is what
     *                  moves the bookmark
     */
    public Subscription subscribe(String topic, String subId, BookmarkStart start, String filter,
                                  MessageProcessor processor) throws AMPSException {
        Connections.requireText(topic, "topic");
        Connections.requireText(subId, "subId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(processor, "processor");

        Field subIdField = new Field(subId);
        if (start.isEpoch()) {
            // Starting over means starting over: a remembered position would
            // otherwise filter the first part of the replay as already seen.
            store.purge(subIdField);
        }
        String from = start.isMostRecent() ? store.getMostRecent(subIdField).toString() : start.bookmark();
        Subscription subscription = new Subscription(topic, subId, from, processor);
        Command subscribe = new Command("subscribe")
                .setTopic(topic)
                .setSubId(subId)
                .setBookmark(start.bookmark());
        if (filter != null && !filter.isBlank()) {
            subscribe.setFilter(filter);
        }
        if (fullyDurable) {
            subscribe.setOptions(Message.Options.FullyDurable);
        }
        subscription.commandId = client.executeAsync(subscribe, subscription::onMessage);
        subscriptions.add(subscription);
        log.info("[{}] subscribed to {} as '{}' from {} (asked for '{}'{}{})", client.getName(), topic, subId,
                BookmarkStart.describe(from), start.bookmark(), fullyDurable ? ", fully_durable" : "",
                filter == null || filter.isBlank() ? "" : ", filter " + filter);
        return subscription;
    }

    /**
     * The client first, so that no subscription is still discarding into the
     * store; then the store, which writes the final recovery point and closes
     * the tracker behind the adapter.
     */
    @Override
    public void close() {
        client.close();
        try {
            store.close();
        } catch (Exception e) {
            log.warn("[{}] closing the bookmark store: {}", client.getName(), e.toString());
            if (tracker != null) {
                tracker.close();
            }
        }
    }

    /** One bookmark subscription: where it started, where it is, what it has seen. */
    public final class Subscription {

        private final String topic;
        private final String subId;
        private final String startedFrom;
        private final MessageProcessor processor;
        private final AtomicLong received = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private volatile CommandId commandId;
        private volatile String lastBookmark;

        private Subscription(String topic, String subId, String startedFrom, MessageProcessor processor) {
            this.topic = topic;
            this.subId = subId;
            this.startedFrom = startedFrom;
            this.processor = processor;
        }

        private void onMessage(Message message) {
            if (message.getCommand() != Message.Command.Publish || message.isDataNull()) {
                return;
            }
            received.incrementAndGet();
            lastBookmark = message.getBookmark();
            try {
                processor.process(message);
            } catch (Exception e) {
                failures.incrementAndGet();
                log.warn("[{}] '{}' did not process bookmark {}; it stays undiscarded and is delivered again by a "
                        + "restart from MOST_RECENT: {}", client.getName(), subId, message.getBookmark(), e.toString());
                return;
            }
            // Processed: only now may the bookmark advance past it.
            try {
                store.discard(message);
            } catch (AMPSException e) {
                log.warn("[{}] could not discard bookmark {}: {}", client.getName(), message.getBookmark(), e.toString());
            }
        }

        public String topic() {
            return topic;
        }

        public String subId() {
            return subId;
        }

        /**
         * The bookmark the subscription was issued with: the literal for
         * {@code EPOCH}, {@code NOW} and {@link BookmarkStart#at}, the store's
         * position for {@code MOST_RECENT} -- which is {@code EPOCH} when it
         * had none.
         */
        public String startedFrom() {
            return startedFrom;
        }

        /** The bookmark of the last message handed to the processor, or {@code null} before the first. */
        public String lastBookmark() {
            return lastBookmark;
        }

        /** Publish messages handed to the processor, redeliveries included. */
        public long received() {
            return received.get();
        }

        /** Messages the processor threw on, and whose bookmarks were therefore kept. */
        public long failures() {
            return failures.get();
        }

        /** Stops the subscription; the store keeps its position for a later {@code MOST_RECENT}. */
        public void unsubscribe() throws DisconnectedException {
            CommandId id = commandId;
            if (id != null && subscriptions.remove(this)) {
                client.unsubscribe(id);
            }
        }

        @Override
        public String toString() {
            return "subscription '" + subId + "' to " + topic + " from " + BookmarkStart.describe(startedFrom)
                    + ", received " + received.get() + ", last bookmark " + lastBookmark;
        }
    }

    public static final class Builder {

        private final String clientName;
        private final List<String> uris = new ArrayList<>();
        private Path stateDir;
        private RecoveryPointTopic recoveryPointTopic;
        private String trackerSuffix = DEFAULT_TRACKER_SUFFIX;
        private HaConnectionSettings settings = HaConnectionSettings.defaults();
        private boolean fullyDurable = true;

        private Builder(String clientName) {
            Connections.requireText(clientName, "clientName");
            this.clientName = clientName;
        }

        /** Every instance, in the order to try them. */
        public Builder uris(List<String> uris) {
            this.uris.clear();
            this.uris.addAll(Objects.requireNonNull(uris, "uris"));
            return this;
        }

        /** Every instance, in the order to try them. */
        public Builder uris(String... uris) {
            return uris(List.of(uris));
        }

        /**
         * Where the file-backed bookmark store lives, named after the client.
         * Without it the store is in memory. With a recovery point topic the
         * file is a local copy and the topic its backup: a machine that has
         * no such file recovers from the topic.
         */
        public Builder stateDir(Path stateDir) {
            this.stateDir = stateDir;
            return this;
        }

        /**
         * The SOW topic to replicate the bookmarks to; {@code null} (the
         * default) keeps them in the local store only. The topic must exist
         * on every instance -- see {@link RecoveryPointTopic}.
         */
        public Builder recoveryPointTopic(RecoveryPointTopic topic) {
            this.recoveryPointTopic = topic;
            return this;
        }

        /** The suffix on the client name for the tracker; {@value #DEFAULT_TRACKER_SUFFIX} by default. */
        public Builder trackerSuffix(String suffix) {
            Connections.requireText(suffix, "trackerSuffix");
            this.trackerSuffix = suffix;
            return this;
        }

        public Builder settings(HaConnectionSettings settings) {
            this.settings = Objects.requireNonNull(settings, "settings");
            return this;
        }

        /**
         * Whether subscriptions ask for {@code fully_durable}; {@code true} by
         * default, and the class comment says why it should stay so on a
         * replicated pair.
         */
        public Builder fullyDurable(boolean fullyDurable) {
            this.fullyDurable = fullyDurable;
            return this;
        }

        /**
         * Builds the client and, with a recovery point topic, the tracker;
         * connects both and logs on. The recovery point, when there is one, is
         * read while the store is built, before the subscriber connects.
         */
        public HaBookmarkSubscriber connect() throws AMPSException, IOException {
            List<String> servers = Connections.requireUris(uris);
            HAClient client = new HAClient(clientName);
            ConnectionJournal journal = new ConnectionJournal(clientName, client::getURI);
            HAClient tracker = null;
            ConnectionJournal trackerJournal = null;
            BookmarkStore store = null;
            try {
                RecoveryPointAdapter adapter = null;
                if (recoveryPointTopic != null) {
                    String trackerName = clientName + trackerSuffix;
                    tracker = new HAClient(trackerName);
                    trackerJournal = new ConnectionJournal(trackerName, tracker::getURI);
                    tracker.setPublishStore(new MemoryPublishStore(settings.memoryStoreBlocks()));
                    tracker.setFailedWriteHandler(Connections.loggingFailedWriteHandler(trackerName, "recovery point"));
                    // Connected before the store is built: building the store
                    // is what reads the recovery point back through the adapter.
                    Connections.connect(tracker, trackerJournal, servers, settings);
                    adapter = adapter(tracker, clientName, recoveryPointTopic);
                }
                store = bookmarkStore(clientName, stateDir, adapter);
                client.setBookmarkStore(store);
                Connections.connect(client, journal, servers, settings);
                log.info("[{}] subscribing via {} with a {} bookmark store, bookmarks {}", clientName,
                        journal.lastLogonUri(), stateDir == null ? "memory" : "file",
                        recoveryPointTopic == null ? "local only"
                                : "replicated to '" + recoveryPointTopic.topic() + "' via " + trackerJournal.lastLogonUri());
                return new HaBookmarkSubscriber(client, journal, store, recoveryPointTopic, tracker, trackerJournal,
                        fullyDurable);
            } catch (AMPSException | IOException | RuntimeException e) {
                client.close();
                if (store != null) {
                    try {
                        store.close(); // and with it the adapter and its tracker
                    } catch (Exception ignored) {
                        // best effort on the way out
                    }
                }
                if (tracker != null) {
                    tracker.close();
                }
                throw e;
            }
        }

        /**
         * The adapter over the tracker. It owns the tracker (closing the store
         * closes all three), it stores bookmarks rather than timestamps, and a
         * failed write is logged rather than thrown: the next discard writes
         * the position again, and it must never fail the subscriber's own
         * discard.
         */
        private static SOWRecoveryPointAdapter adapter(HAClient tracker, String clientName, RecoveryPointTopic topic)
                throws AMPSException {
            SOWRecoveryPointAdapter adapter = new SOWRecoveryPointAdapter(tracker, clientName,
                    true, false, false, Math.max(1L, topic.timeout().toMillis()), topic.topic(),
                    topic.clientNameField(), topic.subIdField(), topic.bookmarkField());
            adapter.setExceptionListener(e -> log.warn(
                    "[{}] recovery point adapter: {} (is '{}' configured on every instance as a JSON SOW topic keyed on /{} and /{}?)",
                    clientName, e.toString(), topic.topic(), topic.clientNameField(), topic.subIdField()));
            return adapter;
        }

        /**
         * The local store, seeded from the adapter's recovery point when there
         * is one: memory when {@code stateDir} is null, otherwise a file with
         * the adapter as its backup -- "in case the file is deleted", as the
         * library puts it, or was never on this machine.
         */
        private static BookmarkStore bookmarkStore(String clientName, Path stateDir, RecoveryPointAdapter adapter)
                throws AMPSException, IOException {
            if (stateDir == null) {
                return adapter == null ? new MemoryBookmarkStore() : new MemoryBookmarkStore(1, adapter);
            }
            Files.createDirectories(stateDir);
            String path = stateDir.resolve(clientName + ".bookmarks").toString();
            return adapter == null ? new LoggedBookmarkStore(path) : new LoggedBookmarkStore(path, 1, false, adapter);
        }
    }
}
