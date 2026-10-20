package com.demo.amps.ha.api;

import com.crankuptheamps.client.FailedWriteHandler;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.MemoryPublishStore;
import com.crankuptheamps.client.PublishStore;
import com.crankuptheamps.client.Store;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.DisconnectedException;
import com.crankuptheamps.client.exception.TimedOutException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A publisher that survives losing the instance it is connected to: an
 * {@link HAClient} over every instance of a pair, with a publish store.
 *
 * <p>Every publish is written to the store before it is sent and removed only
 * when AMPS acknowledges it as persisted; after a reconnect the client replays
 * whatever is still in the store. When the instances replicate synchronously,
 * an acknowledged message is on both of them, and a replayed one that had in
 * fact reached the survivor is dropped by the server as a duplicate of the
 * same client name and sequence number -- which the failed-write handler logs
 * as expected rather than as a problem.
 *
 * <p>Two behaviours here are the difference between "survives a failover" and
 * "quietly duplicates":
 *
 * <ul>
 *   <li>A publish that ends in {@link DisconnectedException} is <b>not</b>
 *       retried. With a publish store the client stores the message before it
 *       attempts the send, and replays the store after it reconnects; a retry
 *       would store the same message a second time under a new sequence
 *       number, which the server has no way to recognise as a duplicate.
 *       {@link #publish} therefore returns normally in that case, after a log
 *       line. (In practice the HA client blocks the publishing thread through
 *       the reconnect and the exception is rare; the handling is here for the
 *       case where it does escape.)</li>
 *   <li>{@link #flush} uses {@code publishFlush}, which waits for the store to
 *       drain, not {@code flush}, which in this client version does nothing.
 *       With synchronous replication that drain is the moment every message is
 *       on BOTH instances -- or, while one instance is down, the moment the
 *       survivor's replication link was downgraded and it acknowledged alone.</li>
 * </ul>
 *
 * <p>The client name is the identity all of this hangs off: AMPS de-duplicates
 * a replay by (client name, sequence) and the file-backed store is named by
 * it. It therefore has to be stable across runs, and unique among connected
 * clients -- AMPS refuses a second logon under a name that is in use.
 *
 * <pre>{@code
 * try (HaPublisher publisher = HaPublisher.builder("orders-publisher")
 *         .uris("tcp://primary:9007/amps/json", "tcp://secondary:9007/amps/json")
 *         .stateDir(Path.of("state"))          // omit for an in-memory store
 *         .connect()) {
 *     publisher.publish("orders", "{\"id\":1}");
 *     publisher.flush(Duration.ofSeconds(30));  // every message acknowledged as persisted
 * }
 * }</pre>
 */
public final class HaPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HaPublisher.class);

    private final HAClient client;
    private final ConnectionJournal journal;

    private HaPublisher(HAClient client, ConnectionJournal journal) {
        this.client = client;
        this.journal = journal;
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

    /**
     * Publishes {@code data} to {@code topic}. Returns normally when the
     * message is in the store but could not be sent because the connection
     * was down at that moment: the client replays it after reconnecting, and
     * the caller must not publish it again.
     */
    public void publish(String topic, String data) throws AMPSException {
        try {
            client.publish(topic, data);
        } catch (DisconnectedException e) {
            storedNotSent(topic, e);
        }
    }

    /** {@link #publish}, as a delta publish: the document merges into the SOW record with the same key. */
    public void deltaPublish(String topic, String data) throws AMPSException {
        try {
            client.deltaPublish(topic, data);
        } catch (DisconnectedException e) {
            storedNotSent(topic, e);
        }
    }

    private void storedNotSent(String topic, DisconnectedException e) {
        log.info("[{}] a message to {} is in the publish store, to be replayed after the reconnect ({})",
                client.getName(), topic, e.getMessage());
    }

    /**
     * Waits until every message published so far has been acknowledged as
     * persisted.
     *
     * @return {@code true} when the store drained, {@code false} on a timeout
     *     or a disconnect -- in which case nothing is lost, the store still
     *     holds the unacknowledged messages and the client will replay them
     */
    public boolean flush(Duration timeout) {
        try {
            client.publishFlush(Math.max(1L, timeout.toMillis()));
            return true;
        } catch (TimedOutException e) {
            log.warn("[{}] {} message(s) still unacknowledged after {}", client.getName(), unpersisted(), timeout);
            return false;
        } catch (DisconnectedException e) {
            log.warn("[{}] disconnected while waiting for acknowledgments: {}", client.getName(), e.toString());
            return false;
        }
    }

    /** Messages the server has not yet acknowledged as persisted. */
    public long unpersisted() {
        Store store = client.getPublishStore();
        return store == null ? 0 : store.unpersistedCount();
    }

    /**
     * Closes the client. Messages still unacknowledged stay in a file-backed
     * store and are replayed by the next publisher of the same name; an
     * in-memory store loses them.
     */
    @Override
    public void close() {
        client.close();
    }

    public static final class Builder {

        private final String clientName;
        private final List<String> uris = new ArrayList<>();
        private Path stateDir;
        private HaConnectionSettings settings = HaConnectionSettings.defaults();
        private FailedWriteHandler failedWriteHandler;

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
         * Where the file-backed publish store lives, named after the client.
         * Without it the store is in memory and lost with the process, and so
         * are any messages not yet acknowledged when the process ends.
         */
        public Builder stateDir(Path stateDir) {
            this.stateDir = stateDir;
            return this;
        }

        public Builder settings(HaConnectionSettings settings) {
            this.settings = Objects.requireNonNull(settings, "settings");
            return this;
        }

        /**
         * What to do with a message the server refused, instead of the
         * default that logs a Duplicate at info and anything else at warn.
         */
        public Builder failedWriteHandler(FailedWriteHandler handler) {
            this.failedWriteHandler = handler;
            return this;
        }

        /** Builds the client, connects, logs on. */
        public HaPublisher connect() throws AMPSException, IOException {
            List<String> servers = Connections.requireUris(uris);
            HAClient client = new HAClient(clientName);
            ConnectionJournal journal = new ConnectionJournal(clientName, client::getURI);
            try {
                if (stateDir == null) {
                    client.setPublishStore(new MemoryPublishStore(settings.memoryStoreBlocks()));
                } else {
                    Files.createDirectories(stateDir);
                    client.setPublishStore(new PublishStore(stateDir.resolve(clientName + ".publish").toString()));
                }
                client.setFailedWriteHandler(failedWriteHandler != null
                        ? failedWriteHandler
                        : Connections.loggingFailedWriteHandler(clientName, "message"));
                Connections.connect(client, journal, servers, settings);
                log.info("[{}] publishing via {} with a {} publish store", clientName, journal.lastLogonUri(),
                        stateDir == null ? "memory" : "file");
                return new HaPublisher(client, journal);
            } catch (AMPSException | IOException | RuntimeException e) {
                client.close();
                throw e;
            }
        }
    }
}
