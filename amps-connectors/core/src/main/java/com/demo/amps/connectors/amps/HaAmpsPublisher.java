package com.demo.amps.connectors.amps;

import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.ConnectionInfo;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.FixedDelayStrategy;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.MemoryPublishStore;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.PublishStore;
import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.connectors.codec.Payloads;
import com.demo.amps.connectors.config.AmpsServerProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link AmpsPublisher}: one {@link HAClient} per connector, with the publish store
 * the configuration asked for.
 *
 * <p>An HA client rather than a plain one because a connector is a long-lived process against
 * a server that will be restarted under it. The plain {@code Client} does not reconnect -- once
 * AMPS goes away every later publish on it fails, forever -- while the HA client redials
 * through its {@link DefaultServerChooser} and, with a publish store, replays everything AMPS
 * had not yet acknowledged as persisted. That replay is the server half of the at-least-once
 * contract; the source half is the acknowledgment a batch makes after {@link #flush}.
 *
 * <p>The client name is derived from configuration ({@code <prefix>-<connector>}), never
 * generated: it is the identity AMPS and the publish store use to correlate a publisher across
 * runs, and a random one would replay nothing and would let AMPS' duplicate detection do
 * nothing either.
 *
 * <p>The message type belongs to the URI, not to the publish call, which is why this takes one:
 * a client logged on via {@code /amps/fix} publishes FIX-typed topics, and a JSON connector in
 * the same application needs its own connection.
 *
 * <p>Every command answers with the client sequence number the publish store assigned it --
 * what {@code Client.publish} itself returns, and what {@code Command.getClientSequenceNumber()}
 * holds after {@code execute} for the commands built by hand -- so the batch publisher can key
 * a record by the number the server's persisted acks will count up to. Bytes are sent as
 * bytes, through the client's {@code byte[]} overloads, and anything else as text.
 *
 * <p>{@link #connect()} gives up after about {@code logon-timeout} against a server that is
 * not there, and throws; every reconnect after that first success is the HA client's own and
 * never gives up. The distinction matters because the two callers are different threads with
 * different jobs: the first connect runs on whoever is starting the connector, which has other
 * connectors to start and a status line to log, while a reconnect runs on the client's own
 * thread and has nothing better to do than keep trying.
 */
public final class HaAmpsPublisher implements AmpsPublisher {

    private static final Logger log = LoggerFactory.getLogger(HaAmpsPublisher.class);

    /**
     * Blocks the in-memory publish store starts with. It grows on demand; this is only how
     * much a connector allocates before it has published anything.
     */
    private static final int MEMORY_STORE_BLOCKS = 10_000;

    private final AmpsServerProperties server;
    private final String connectorName;
    private final String messageType;
    private final String clientName;
    private final String uri;

    private volatile HAClient client;

    /**
     * @param server the application's AMPS server block
     * @param connectorName the connector's name, which makes the client name unique
     * @param messageType {@code json}, {@code fix} or {@code nvfix} -- part of the URI
     */
    public HaAmpsPublisher(
            AmpsServerProperties server, String connectorName, String messageType) {
        this.server = server;
        this.connectorName = connectorName;
        this.messageType = messageType;
        this.clientName = server.clientName(connectorName);
        this.uri = server.uri(messageType);
    }

    @Override
    public synchronized void connect() throws AMPSException {
        if (client != null) {
            return;
        }
        HAClient connecting = new HAClient(clientName);
        int reconnectDelay = (int) server.getReconnectDelay().toMillis();
        try {
            attachPublishStore(connecting);
            connecting.setServerChooser(new RememberingServerChooser().add(uri));
            // Bounded for the FIRST connect only. connectAndLogon() retries through the
            // server chooser until a strategy tells it to stop, and the plain fixed delay
            // never does -- so against a server that is down it would block this thread
            // forever, and with it the connector's start, the manager's retry tick, every
            // status line and a clean shutdown. Giving up after about logon-timeout turns
            // that into an exception the caller already knows how to handle: the manager
            // retries the connector on its five-second tick, and the alerts sink connects
            // on the first alert instead.
            connecting.setReconnectDelayStrategy(new FixedDelayStrategy(
                    reconnectDelay, (int) server.getLogonTimeout().toMillis()));
            connecting.setTimeout((int) server.getLogonTimeout().toMillis());
            if (server.getPublishBatchBytes() > 0) {
                // Coalescing small publishes into one write is measurably faster at high rates
                // and pure latency at low ones, which is why it is off by default.
                connecting.setPublishBatching(server.getPublishBatchBytes(),
                        (int) server.getPublishBatchDelay().toMillis());
            }
            connecting.connectAndLogon();
            // Connected: from here on the client reconnects on its own, and for as long as it
            // takes, because a server restart is exactly what an HA client is for. The bound
            // above must not apply to that, or a long outage would leave the client
            // disconnected for good with a publish store nobody replays.
            connecting.setReconnectDelayStrategy(new FixedDelayStrategy(reconnectDelay));
        } catch (AMPSException | RuntimeException e) {
            connecting.close();
            throw e;
        }
        client = connecting;
        log.info("[{}] publishing to {} as '{}' ({} store)", connectorName, uri, clientName,
                server.getPublishStore());
    }

    /** MEMORY, FILE or NONE -- the difference between replaying a reconnect and not. */
    private void attachPublishStore(HAClient connecting) throws AMPSException {
        switch (server.getPublishStore()) {
            case MEMORY -> connecting.setPublishStore(new MemoryPublishStore(MEMORY_STORE_BLOCKS));
            case FILE -> {
                Path directory = Path.of(server.getPublishStoreDir());
                try {
                    Files.createDirectories(directory);
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "cannot create publish store directory " + directory, e);
                }
                connecting.setPublishStore(
                        new PublishStore(directory.resolve(clientName + ".publish").toString()));
            }
            case NONE -> {
                // Fire and forget: nothing is replayed after a disconnect, so the connector
                // degrades to at-most-once. Configured deliberately, for a feed that would
                // rather lose a message than repeat one.
            }
        }
    }

    @Override
    public boolean isConnected() {
        HAClient current = client;
        return current != null && current.getConnectionInfo() != null;
    }

    @Override
    public long publish(String topic, Object data, String sowKey) {
        if (sowKey == null && !(data instanceof byte[])) {
            try {
                return required().publish(topic, Payloads.text(data));
            } catch (AMPSException e) {
                throw new IllegalStateException("publish to " + topic + " failed", e);
            }
        }
        return execute(withData(new Command("publish").setTopic(topic), data).setSowKey(sowKey),
                "publish to " + topic);
    }

    @Override
    public long deltaPublish(String topic, Object data, String sowKey) {
        if (sowKey == null && !(data instanceof byte[])) {
            try {
                return required().deltaPublish(topic, Payloads.text(data));
            } catch (AMPSException e) {
                throw new IllegalStateException("delta publish to " + topic + " failed", e);
            }
        }
        return execute(
                withData(new Command("delta_publish").setTopic(topic), data).setSowKey(sowKey),
                "delta publish to " + topic);
    }

    @Override
    public long sowDeleteByKey(String topic, String sowKey) {
        // There is no synchronous sowDeleteByKeys in the Java client -- the only overload takes
        // a MessageHandler -- so the command is built by hand. It goes through the publish
        // store like a publish does (AMPS gives sow_delete a client sequence number too), so
        // the batch's single flush covers it and order with the publishes around it is kept.
        return execute(new Command("sow_delete").setTopic(topic).setSowKeys(sowKey)
                        .setTimeout(server.getFlushTimeout().toMillis()),
                "sow delete of " + sowKey + " on " + topic);
    }

    @Override
    public long sowDeleteByFilter(String topic, String filter) {
        // Built by hand for the same reason as the delete by key -- the sequence number lives
        // on the Command -- but synchronous, exactly as the client's own
        // sowDelete(topic, filter, timeout) is: a stats ack is requested and waited for,
        // which is the only way to find out that a filter matched nothing. Deletes on a
        // server-keyed topic are rare enough that the round trip is worth the certainty.
        long timeout = Math.max(1L, server.getFlushTimeout().toMillis());
        Command command = new Command("sow_delete").setTopic(topic).setFilter(filter)
                .addAckType(Message.AckType.Stats).setTimeout(timeout);
        MessageStream acks;
        try {
            acks = required().execute(command);
        } catch (AMPSException e) {
            throw new IllegalStateException(
                    "sow delete on " + topic + " with filter [" + filter + "] failed", e);
        }
        if (acks != null) {
            try {
                acks.timeout((int) Math.min(Integer.MAX_VALUE, timeout)).next();
            } finally {
                acks.close();
            }
        }
        return command.getClientSequenceNumber();
    }

    /** Bytes go as bytes, so a codec's binary wire form is not re-encoded on the way out. */
    private static Command withData(Command command, Object data) {
        if (data instanceof byte[] bytes) {
            return command.setData(bytes, 0, bytes.length);
        }
        return command.setData(Payloads.text(data));
    }

    @Override
    public boolean flush(Duration timeout) {
        HAClient current = client;
        if (current == null) {
            return false;
        }
        try {
            // publishFlush, not flush: Client.flush(long) is deprecated in 5.3.5 and its body
            // does nothing at all, so a batch that trusted it would acknowledge its records
            // without anything having been persisted. publishFlush waits on the publish store
            // (or, with publish-store NONE, on a flush command's ack), which is exactly the
            // "everything so far is persisted" this returns.
            current.publishFlush(Math.max(1L, timeout.toMillis()));
            return true;
        } catch (AMPSException | RuntimeException e) {
            // Not data loss: the publish store still holds everything unacknowledged and
            // replays it after the reconnect. It does mean this batch's records stay
            // unacknowledged, so their sources will re-read them.
            log.warn("[{}] flush to {} did not complete within {}: {}",
                    connectorName, uri, timeout, e.toString());
            return false;
        }
    }

    @Override
    public synchronized void close() {
        HAClient current = client;
        client = null;
        if (current != null) {
            current.close();
            log.info("[{}] disconnected from {}", connectorName, uri);
        }
    }

    /**
     * A command with no ack type requested: sent asynchronously, like {@code publish} is.
     *
     * @return the client sequence number the publish store assigned, {@code 0} without one
     */
    private long execute(Command command, String what) {
        try {
            // The returned stream is the client's shared empty one -- a command with no ack
            // type never produces messages -- so there is nothing to drain or close.
            required().execute(command);
            return command.getClientSequenceNumber();
        } catch (AMPSException e) {
            throw new IllegalStateException(what + " failed", e);
        }
    }

    /**
     * A {@link DefaultServerChooser} that remembers the last failure it was told about.
     *
     * <p>The default one answers {@code getError()} with an empty string, and that string is
     * all the client puts in the exception when it gives up -- "the last connection error
     * was: " and nothing. Remembering it is what makes the log line say "connection refused"
     * rather than leaving an operator to guess.
     */
    private static final class RememberingServerChooser extends DefaultServerChooser {

        private volatile String error = "";

        @Override
        public void reportFailure(Exception exception, ConnectionInfo info) throws Exception {
            error = String.valueOf(exception);
            super.reportFailure(exception, info);
        }

        @Override
        public String getError() {
            return error;
        }
    }

    private HAClient required() {
        HAClient current = client;
        if (current == null) {
            throw new IllegalStateException(
                    "[" + connectorName + "] is not connected to " + uri);
        }
        return current;
    }

    @Override
    public String toString() {
        return "HaAmpsPublisher[" + uri + " as " + clientName + ", " + messageType + "]";
    }
}
