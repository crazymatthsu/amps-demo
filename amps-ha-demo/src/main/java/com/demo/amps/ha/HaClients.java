package com.demo.amps.ha;

import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.FixedDelayStrategy;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.LoggedBookmarkStore;
import com.crankuptheamps.client.MemoryBookmarkStore;
import com.crankuptheamps.client.MemoryPublishStore;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.PublishStore;
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
 *   <li>the <b>consumer</b> holds a bookmark store. Its bookmark subscription
 *       is re-issued after a reconnect from the most recent bookmark the store
 *       has, and the store filters any redelivery of a message it has already
 *       seen. Bookmarks survive replication unchanged, so the position means
 *       the same thing on the other instance. The subscription itself must ask
 *       for {@code fully_durable} -- see {@link OrderConsumer}.</li>
 * </ul>
 *
 * <p>The client name is the identity all of this hangs off: AMPS de-duplicates
 * a replay by (client name, sequence), and the file-backed stores are named by
 * it. It therefore has to be stable across runs, and unique among connected
 * clients -- AMPS refuses a second logon under a name that is in use.
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

    private HaClients() {
    }

    /** A connected HA client and the journal recording its state changes. */
    public record Connected(HAClient client, ConnectionJournal journal) implements AutoCloseable {
        @Override
        public void close() {
            client.close();
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
     * @param name     the client name
     * @param uris     both instances, in the order to try them
     * @param stateDir where the file-backed store lives, or {@code null} for an
     *                 in-memory store that is lost with the process
     */
    public static Connected consumer(String name, List<String> uris, Path stateDir) throws Exception {
        HAClient client = new HAClient(name);
        ConnectionJournal journal = new ConnectionJournal(name, client::getURI);
        try {
            if (stateDir == null) {
                client.setBookmarkStore(new MemoryBookmarkStore());
            } else {
                Files.createDirectories(stateDir);
                client.setBookmarkStore(new LoggedBookmarkStore(stateDir.resolve(name + ".bookmarks").toString()));
            }
            connect(client, journal, uris);
            log.info("[{}] consuming via {} with a {} bookmark store", name, journal.lastLogonUri(),
                    stateDir == null ? "memory" : "file");
            return new Connected(client, journal);
        } catch (Exception e) {
            client.close();
            throw e;
        }
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
