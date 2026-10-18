package com.demo.amps.ha;

import com.crankuptheamps.client.BookmarkStore;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.FixedDelayStrategy;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.LoggedBookmarkStore;
import com.crankuptheamps.client.MemoryBookmarkStore;
import com.crankuptheamps.client.MemoryPublishStore;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.PublishStore;
import com.crankuptheamps.client.RecoveryPointAdapter;
import com.crankuptheamps.client.SOWRecoveryPointAdapter;
import com.crankuptheamps.client.exception.StoreException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two HA client shapes this demo needs, built the same way every time.
 *
 * <p>Both are an {@link HAClient} over a {@link DefaultServerChooser} that
 * lists BOTH instances: when the connection drops, the client redials the
 * next URI in the list, logs on, and then does the thing that makes the
 * failover lossless for its side:
 *
 * <ul>
 *   <li>the <b>publisher</b> holds a publish store. Every publish is written
 *       there before it is sent and removed only when AMPS acknowledges it as
 *       persisted; after a reconnect the client replays whatever is still in
 *       the store. Because the pair replicates with {@code SyncType sync}, an
 *       acknowledged message is on both instances, and a replayed one that had
 *       in fact reached the survivor is dropped by the server as a duplicate of
 *       the same client name and sequence number.</li>
 *   <li>the <b>consumer</b> holds a bookmark store, backed by a
 *       {@link SOWRecoveryPointAdapter}. Its bookmark subscription is re-issued
 *       after a reconnect from the most recent bookmark the store has, and the
 *       store filters any redelivery of a message it has already seen. The
 *       adapter writes that position, as it moves, to a SOW topic on the pair
 *       -- keyed on the client name and the subscription id, journaled and
 *       replicated like the orders -- and a store created later under the same
 *       client name reads it back before subscribing. So a consumer started on
 *       another machine, with no file to its name and against whichever
 *       instance survived, resumes where the previous one stopped. Bookmarks
 *       survive replication unchanged, so the position means the same thing on
 *       the other instance. The subscription itself must ask for
 *       {@code fully_durable} -- see {@link OrderConsumer}.</li>
 * </ul>
 *
 * <p>The adapter may not share the consumer's own client (it queries and
 * publishes while that client is busy subscribing, and the library refuses the
 * same name), so a consumer with replicated bookmarks is two HA clients: the
 * consumer, and a <b>tracker</b> named after it that does nothing but write
 * recovery points. The tracker carries a publish store for the same reason the
 * publisher does: a recovery point whose acknowledgment died with an instance
 * is replayed to the survivor, and one that had arrived is dropped there as a
 * duplicate.
 *
 * <p>The client name is the identity all of this hangs off: AMPS de-duplicates
 * a replay by (client name, sequence), the file-backed stores are named by it,
 * and the recovery point is filed under it. It therefore has to be stable
 * across runs, and unique among connected clients -- AMPS refuses a second
 * logon under a name that is in use.
 */
public final class HaClients {

    private static final Logger log = LoggerFactory.getLogger(HaClients.class);

    /** How long a first connect keeps trying before it gives up and throws. */
    private static final int FIRST_CONNECT_BUDGET_MS = 30_000;

    /** Pause between redial attempts, first connect and reconnects alike. */
    private static final int RECONNECT_DELAY_MS = 250;

    private static final int LOGON_TIMEOUT_MS = 10_000;

    /**
     * Heartbeat interval. A killed server does not always close the TCP
     * connection in a way the client notices at once (a port forwarder in the
     * middle, as with podman machine, may hold it open), and the heartbeat is
     * what bounds the time to notice: the client declares the connection dead
     * after two missed intervals.
     */
    private static final int HEARTBEAT_SECONDS = 1;

    /** Blocks the in-memory publish store starts with; it grows on demand. */
    private static final int MEMORY_STORE_BLOCKS = 10_000;

    /** The suffix on a consumer's name for the tracker that writes its recovery points. */
    public static final String TRACKER_SUFFIX = "-bookmarks";

    /**
     * Bound on the adapter's SOW query when a store is created, and on its
     * flush when the store is closed; the library's default is to wait forever.
     */
    private static final long RECOVERY_POINT_TIMEOUT_MS = 10_000;

    private HaClients() {
    }

    /**
     * Where a consumer's bookmarks are replicated to: the SOW topic, and the
     * tracker client that writes them, with the journal of its own failovers.
     */
    public record ReplicatedBookmarks(String topic, HAClient tracker, ConnectionJournal journal) {
    }

    /**
     * A connected HA client and the journal recording its state changes. A
     * consumer also carries its bookmark store and, unless its bookmarks are
     * local only, where they are replicated to; a publisher carries neither.
     */
    public record Connected(HAClient client, ConnectionJournal journal, BookmarkStore bookmarkStore,
                            ReplicatedBookmarks replicatedBookmarks) implements AutoCloseable {

        /** A publisher: client and journal only. */
        Connected(HAClient client, ConnectionJournal journal) {
            this(client, journal, null, null);
        }

        /**
         * The client first, so that no handler is still discarding into the
         * store; then the store, which writes the final recovery point and
         * closes the tracker behind the adapter.
         */
        @Override
        public void close() {
            client.close();
            if (bookmarkStore != null) {
                try {
                    bookmarkStore.close();
                } catch (Exception e) {
                    log.warn("[{}] closing the bookmark store: {}", client.getName(), e.toString());
                    if (replicatedBookmarks != null) {
                        replicatedBookmarks.tracker().close();
                    }
                }
            }
        }
    }

    /**
     * A publisher: HA client with a publish store, connected and logged on.
     *
     * @param name     the client name (see the class comment on why it must be stable)
     * @param uris     both instances, in the order to try them
     * @param stateDir where the file-backed store lives, or {@code null} for an
     *                 in-memory store that is lost with the process
     */
    public static Connected publisher(String name, List<String> uris, Path stateDir) throws Exception {
        HAClient client = new HAClient(name);
        ConnectionJournal journal = new ConnectionJournal(name, client::getURI);
        try {
            if (stateDir == null) {
                client.setPublishStore(new MemoryPublishStore(MEMORY_STORE_BLOCKS));
            } else {
                Files.createDirectories(stateDir);
                client.setPublishStore(new PublishStore(stateDir.resolve(name + ".publish").toString()));
            }
            // What the server refused. After a replay, Duplicate is the EXPECTED
            // answer for a message that had reached the survivor before the crash:
            // it is the server-side half of "at least once, seen once".
            client.setFailedWriteHandler((message, reason) -> {
                long sequence = message.isSequenceNull() ? 0 : message.getSequence();
                if (reason == Message.Reason.Duplicate) {
                    log.info("[{}] sequence {} dropped by the server as a duplicate (already there, expected after a replay)",
                            name, sequence);
                } else {
                    log.warn("[{}] sequence {} refused by the server, reason {}", name, sequence, reason);
                }
            });
            connect(client, journal, uris);
            log.info("[{}] publishing via {} with a {} publish store", name, journal.lastLogonUri(),
                    stateDir == null ? "memory" : "file");
            return new Connected(client, journal);
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    /**
     * A consumer: HA client with a bookmark store, connected and logged on.
     *
     * @param name               the client name; its recovery point is filed under it
     * @param uris               both instances, in the order to try them
     * @param stateDir           where the file-backed store lives, or {@code null} for an
     *                           in-memory store. With a recovery point topic the file is
     *                           a local copy and the topic its backup: a machine that
     *                           has no such file recovers from the topic
     * @param recoveryPointTopic the SOW topic to replicate the bookmarks to, or
     *                           {@code null} to keep them in the local store only
     */
    public static Connected consumer(String name, List<String> uris, Path stateDir, String recoveryPointTopic)
            throws Exception {
        HAClient client = new HAClient(name);
        ConnectionJournal journal = new ConnectionJournal(name, client::getURI);
        ReplicatedBookmarks replicated = null;
        BookmarkStore store = null;
        try {
            RecoveryPointAdapter adapter = null;
            if (recoveryPointTopic != null) {
                // Connected before the store is built: building the store is
                // what reads the recovery point back through the adapter.
                replicated = tracker(name, uris, recoveryPointTopic);
                adapter = adapter(replicated, name);
            }
            store = bookmarkStore(name, stateDir, adapter);
            client.setBookmarkStore(store);
            connect(client, journal, uris);
            log.info("[{}] consuming via {} with a {} bookmark store, bookmarks {}", name, journal.lastLogonUri(),
                    stateDir == null ? "memory" : "file",
                    replicated == null ? "local only"
                            : "replicated to '" + recoveryPointTopic + "' via " + replicated.journal().lastLogonUri());
            return new Connected(client, journal, store, replicated);
        } catch (Exception e) {
            client.close();
            if (store != null) {
                try {
                    store.close(); // and with it the adapter and its tracker
                } catch (Exception ignored) {
                    // best effort on the way out
                }
            }
            if (replicated != null) {
                replicated.tracker().close();
            }
            throw e;
        }
    }

    /** The tracker: a second HA client, over the same instances, that only writes recovery points. */
    private static ReplicatedBookmarks tracker(String consumerName, List<String> uris, String topic) throws Exception {
        String trackerName = consumerName + TRACKER_SUFFIX;
        HAClient tracker = new HAClient(trackerName);
        ConnectionJournal journal = new ConnectionJournal(trackerName, tracker::getURI);
        try {
            tracker.setPublishStore(new MemoryPublishStore(MEMORY_STORE_BLOCKS));
            tracker.setFailedWriteHandler((message, reason) -> {
                if (reason == Message.Reason.Duplicate) {
                    log.debug("[{}] a replayed recovery point was already on the server", trackerName);
                } else {
                    log.warn("[{}] recovery point refused by the server, reason {}", trackerName, reason);
                }
            });
            connect(tracker, journal, uris);
            return new ReplicatedBookmarks(topic, tracker, journal);
        } catch (Exception e) {
            tracker.close();
            throw e;
        }
    }

    /**
     * The adapter over the tracker. It owns the tracker (closing the store
     * closes all three), it stores bookmarks rather than timestamps, and a
     * failed write is logged rather than thrown: the next discard writes the
     * position again, and it must never fail the consumer's own discard.
     */
    private static SOWRecoveryPointAdapter adapter(ReplicatedBookmarks replicated, String consumerName)
            throws StoreException {
        SOWRecoveryPointAdapter adapter = new SOWRecoveryPointAdapter(replicated.tracker(), consumerName,
                true, false, false, RECOVERY_POINT_TIMEOUT_MS, replicated.topic(),
                RecoveryPoints.CLIENT_NAME_FIELD, RecoveryPoints.SUB_ID_FIELD, RecoveryPoints.BOOKMARK_FIELD);
        adapter.setExceptionListener(e -> log.warn(
                "[{}] recovery point adapter: {} (is '{}' configured on every instance as a JSON SOW topic keyed on /{} and /{}?)",
                consumerName, e.toString(), replicated.topic(), RecoveryPoints.CLIENT_NAME_FIELD,
                RecoveryPoints.SUB_ID_FIELD));
        return adapter;
    }

    /**
     * The local store, seeded from the adapter's recovery point when there is
     * one: memory when {@code stateDir} is null, otherwise a file with the
     * adapter as its backup -- "in case the file is deleted", as the library
     * puts it, or was never on this machine.
     */
    private static BookmarkStore bookmarkStore(String name, Path stateDir, RecoveryPointAdapter adapter)
            throws Exception {
        if (stateDir == null) {
            return adapter == null ? new MemoryBookmarkStore() : new MemoryBookmarkStore(1, adapter);
        }
        Files.createDirectories(stateDir);
        String path = stateDir.resolve(name + ".bookmarks").toString();
        return adapter == null ? new LoggedBookmarkStore(path) : new LoggedBookmarkStore(path, 1, false, adapter);
    }

    private static void connect(HAClient client, ConnectionJournal journal, List<String> uris) throws Exception {
        client.addConnectionStateListener(journal);
        // Exceptions the client absorbs on its own threads would otherwise vanish.
        client.setExceptionListener(e -> log.warn("[{}] client reported: {}", client.getName(), e.toString()));
        client.setServerChooser(new DefaultServerChooser().addAll(uris));
        client.setTimeout(LOGON_TIMEOUT_MS);
        // Bounded for the FIRST connect only: against a pair that is not there
        // at all, connectAndLogon would otherwise block forever. Once connected,
        // reconnects never give up -- an outage is what an HA client is for.
        client.setReconnectDelayStrategy(new FixedDelayStrategy(RECONNECT_DELAY_MS, FIRST_CONNECT_BUDGET_MS));
        client.connectAndLogon();
        client.setReconnectDelayStrategy(new FixedDelayStrategy(RECONNECT_DELAY_MS));
        client.setHeartbeat(HEARTBEAT_SECONDS);
    }
}
