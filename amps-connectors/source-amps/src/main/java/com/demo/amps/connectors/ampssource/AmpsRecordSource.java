package com.demo.amps.connectors.ampssource;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.CommandId;
import com.crankuptheamps.client.ConnectionInfo;
import com.crankuptheamps.client.ConnectionStateListener;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.connectors.config.AmpsServerProperties;
import com.demo.amps.connectors.config.AmpsSourceProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.source.RecordHandler;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordSource} backed by the 60East AMPS Java client: one topic, read as a feed.
 *
 * <p>Reads its subscription from {@code source.amps}; the wire format stays on the connector,
 * because it describes the payload rather than the transport -- and in AMPS the payload's
 * type is part of the connection URI, so {@code format: FIX} is what puts {@code /amps/fix}
 * on the end of it. The server is the application's own ({@code amps-connectors.amps})
 * unless the block names another, which is what turns a connector into a bridge between two
 * instances.
 *
 * <h2>Its own client, under its own name</h2>
 *
 * <p>A connector that reads AMPS holds two connections to it: this one, subscribing, and the
 * publisher's, publishing. They cannot be the same client -- the pipeline runs on this
 * client's receive thread, and a batch's {@code publishFlush} waits for an acknowledgment
 * that the publisher's receive thread has to be free to read -- and they cannot share a
 * <em>name</em> either: AMPS refuses a logon whose client name is already in use
 * ({@code NameInUseException}), so a bridge between two topics of one instance would never
 * come up. The subscriber is therefore {@code <prefix>-<connector>-source}, beside the
 * publisher's {@code <prefix>-<connector>}.
 *
 * <h2>What arrives, and what it becomes</h2>
 *
 * <table border="1">
 *   <caption>AMPS message command to record</caption>
 *   <tr><th>command</th><th>record</th><th>key</th></tr>
 *   <tr><td>{@code sow}, {@code publish}, {@code delta_publish}</td><td>{@code UPSERT}</td>
 *       <td>the SowKey, when the topic has one</td></tr>
 *   <tr><td>{@code oof}, {@code sow_delete}</td><td>{@code DELETE}, payload kept</td>
 *       <td>the SowKey -- and the payload's key fields, for a target that deletes by
 *           filter</td></tr>
 *   <tr><td>group begin/end, acks, heartbeats</td><td colspan="2">nothing; they carry no
 *       record</td></tr>
 * </table>
 *
 * <p>Every record carries the {@code topic} it came from (a regular-expression subscription
 * spans several), the AMPS {@code command} that delivered it, and its {@code bookmark} when
 * the subscription has one -- the last is what a rule can log to say exactly where in the
 * journal a record sat.
 *
 * <h2>Connecting, reconnecting, resuming</h2>
 *
 * <p>{@link #start} returns as soon as the connect thread is running: an AMPS that is not up
 * yet is the normal case for a process that starts with the server, and it reports through
 * {@link #isConnected()} rather than a thrown {@code start}, like every other driver. The
 * thread logs on -- the {@link HAClient} itself retries every {@code reconnect-delay} until it
 * does -- issues the subscription, and is done: from then on the client redials a dropped
 * connection and re-issues the subscription by itself, and the listener it reports to is
 * what {@link #isConnected()} answers from.
 *
 * <p>What a reconnect <em>costs</em> is the mode's business, and the one difference worth
 * knowing before choosing: a {@code subscribe} re-issued live misses what was published
 * during the outage, a {@code sow_and_subscribe} re-reads the SOW (duplicate upserts, which a
 * keyed target absorbs), and a bookmark subscription resumes exactly, because the client
 * keeps an in-memory bookmark store and this source {@linkplain #dispatch discards} each
 * message from it once the pipeline has taken it. Discarding on delivery rather than on the
 * batch's acknowledgment is deliberate: a store that only lives as long as the process cannot
 * make a restart resume anyway, and within the process a delivered record is already in the
 * aggregator or the publish store, both of which survive the <em>source</em> reconnecting.
 * Records therefore carry no acknowledgment, and a restart replays from the configured
 * bookmark -- the at-least-once trade. A persistent store, discarded on acknowledgment, is
 * the follow-up that would turn a restart into a resume.
 *
 * <p>A handler that throws is one bad record: logged, counted, and the subscription carries
 * on. The receive thread never dies because the pipeline rejected something.
 */
public class AmpsRecordSource implements RecordSource {

    private static final Logger log = LoggerFactory.getLogger(AmpsRecordSource.class);

    /** Attribute carrying the AMPS topic a record was read from. */
    public static final String ATTRIBUTE_TOPIC = "topic";

    /** Attribute carrying the AMPS command that delivered the record ({@code sow}, {@code publish}, ...). */
    public static final String ATTRIBUTE_COMMAND = "command";

    /** Attribute carrying the record's bookmark, on a bookmark subscription. */
    public static final String ATTRIBUTE_BOOKMARK = "bookmark";

    /**
     * Records per round trip while a {@code sow_and_subscribe} reads the SOW. The server's
     * default is one, which makes a snapshot of a large topic a long conversation.
     */
    static final int SOW_BATCH_SIZE = 100;

    /** How long {@link #close()} waits for the connect thread before giving up on it. */
    private static final long CLOSE_JOIN_MILLIS = 5_000;

    /** What the subscriber is called, next to the publisher's {@code <prefix>-<connector>}. */
    private static final String CLIENT_NAME_SUFFIX = "-source";

    private final ConnectorProperties connector;
    private final AmpsSourceProperties source;
    private final String messageType;
    private final String uri;
    private final String clientName;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong handlerFailures = new AtomicLong();

    /** Monitor the reconnect backoff waits on, so {@link #close()} cuts it short. */
    private final Object backoff = new Object();

    private volatile HAClient client;
    private volatile CommandId subscription;
    private volatile Thread thread;

    /**
     * @param connector the connector configuration; its {@code source.amps} block is required
     * @param defaults the application's shared server block -- the server to dial when the
     *     source names none, and the owner of the client-name prefix either way
     * @throws IllegalArgumentException if the connector's format has no AMPS message type
     */
    public AmpsRecordSource(ConnectorProperties connector, AmpsServerProperties defaults) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.source = Objects.requireNonNull(connector.getSource().getAmps(),
                "connector '" + connector.getName() + "' has no source.amps block");
        if (connector.getFormat() == SourceFormat.TEXT) {
            throw new IllegalArgumentException("connector '" + connector.getName()
                    + "': format TEXT has no AMPS message type -- an AMPS topic is json, fix"
                    + " or nvfix, so a source.amps connector's format must be one of those");
        }
        this.messageType = messageTypeOf(connector.getFormat());
        AmpsSourceProperties.Endpoint server = source.getServer();
        this.uri = server != null ? server.uri(messageType) : defaults.uri(messageType);
        this.clientName = defaults.clientName(connector.getName() + CLIENT_NAME_SUFFIX);
    }

    /**
     * The AMPS message type a source format is read through.
     *
     * <p>The mirror image of the publisher's {@code amps.message-type}: there the target
     * chooses, here the source's own format does, because the topic being read already has
     * a type and the connector's {@code format} is the statement of what it is.
     *
     * @param format the connector's source format
     * @return {@code json}, {@code fix} or {@code nvfix}
     * @throws IllegalArgumentException for {@link SourceFormat#TEXT}, which no AMPS topic carries
     */
    static String messageTypeOf(SourceFormat format) {
        return switch (format) {
            case JSON -> "json";
            case FIX -> "fix";
            case NVFIX -> "nvfix";
            case TEXT -> throw new IllegalArgumentException(
                    "format TEXT has no AMPS message type (json, fix or nvfix)");
        };
    }

    /** The connection URI this source dials, e.g. {@code tcp://localhost:9007/amps/json}. */
    String uri() {
        return uri;
    }

    /** The client name this source logs on with, e.g. {@code amps-connectors-orders-source}. */
    String clientName() {
        return clientName;
    }

    /** The message type on the URI: {@code json}, {@code fix} or {@code nvfix}. */
    String messageType() {
        return messageType;
    }

    @Override
    public void start(RecordHandler handler) {
        log.info("[{}] starting AMPS source: {} as '{}' (topic '{}', {}{})",
                connector.getName(), uri, clientName, source.getTopic(), source.getMode(),
                source.getMode() == AmpsSourceProperties.Mode.BOOKMARK
                        ? " from " + source.getBookmark() : "");
        Thread runner = new Thread(() -> run(handler), connector.getName() + "-amps");
        runner.setDaemon(true);
        this.thread = runner;
        runner.start();
    }

    /**
     * Log on and subscribe; on any failure short of {@link #close()}, back off and try again
     * with a fresh client. Returns once the subscription is issued, because from then on the
     * {@link HAClient} redials and resubscribes by itself.
     */
    private void run(RecordHandler handler) {
        while (!closed.get()) {
            HAClient amps = null;
            try {
                amps = newClient();
                this.client = amps;
                if (closed.get()) {
                    // close() publishes `closed` and then reads `client`; this writes them
                    // the other way round, so between the two at least one of us sees the
                    // other. Without the check, a close landing in this window would leave
                    // a client nobody disconnects. Closing twice is harmless.
                    closeQuietly(amps);
                    break;
                }
                // Blocks, retrying every reconnect-delay, until it is logged on -- or until
                // close() disconnects the client from under it, which ends the loop and
                // brings it back here with `closed` set.
                amps.connectAndLogon();
                if (closed.get()) {
                    closeQuietly(amps);
                    break;
                }
                Command command = buildCommand();
                this.subscription = amps.executeAsync(command,
                        message -> dispatch(message, handler));
                log.info("[{}] subscribed to '{}' on {} ({}{})", connector.getName(),
                        source.getTopic(), uri, commandName(command.getCommand()),
                        command.getFilter() != null ? ", filter " + command.getFilter() : "");
                return;
            } catch (AMPSException | RuntimeException e) {
                connected.set(false);
                this.client = null;
                closeQuietly(amps);
                if (closed.get()) {
                    break;
                }
                // A server that is not up yet is the normal case here, and a subscription the
                // server refused (a topic the flow does not define, a filter that does not
                // parse, a bookmark it cannot replay) says the same thing every time: keep
                // the loop's log readable and put the trace behind DEBUG.
                log.warn("[{}] AMPS subscription to '{}' on {} failed: {}",
                        connector.getName(), source.getTopic(), uri, e.toString());
                log.debug("[{}] AMPS subscribe failed", connector.getName(), e);
            }
            if (!closed.get()) {
                log.info("[{}] retrying the AMPS subscription in {}",
                        connector.getName(), source.getReconnectDelay());
                if (!sleep(source.getReconnectDelay())) {
                    break;
                }
            }
        }
        connected.set(false);
        log.info("[{}] AMPS source stopped", connector.getName());
    }

    /**
     * A memory-backed HA client: the bookmark store that lets a bookmark subscription resume
     * after a reconnect, and a publish store this subscriber never uses.
     */
    private HAClient newClient() throws AMPSException {
        HAClient amps = HAClient.createMemoryBacked(clientName);
        amps.setServerChooser(new LoggingServerChooser().add(uri));
        amps.setTimeout((int) source.getTimeout().toMillis());
        amps.setReconnectDelay((int) source.getReconnectDelay().toMillis());
        amps.addConnectionStateListener(this::onConnectionState);
        amps.setExceptionListener(e -> log.warn("[{}] AMPS client error on {}: {}",
                connector.getName(), uri, e.toString()));
        return amps;
    }

    /**
     * The one-server chooser the client redials through, made to say so when it cannot.
     *
     * <p>The HA client's connect loop retries a refused connection silently -- it neither
     * logs nor tells the exception listener -- and the server chooser is the hook it does
     * call on every failed attempt. Without this, a connector whose AMPS is down would be
     * a status line saying {@code connected=false} and nothing else; with it, each attempt
     * is one warning, the same as the other drivers give.
     */
    private final class LoggingServerChooser extends DefaultServerChooser {

        @Override
        public void reportFailure(Exception failure, ConnectionInfo info) throws Exception {
            if (!closed.get()) {
                log.warn("[{}] cannot reach AMPS at {}: {} (retrying every {})",
                        connector.getName(), uri, failure.toString(), source.getReconnectDelay());
                log.debug("[{}] AMPS connection attempt failed", connector.getName(), failure);
            }
            super.reportFailure(failure, info);
        }
    }

    /**
     * The subscription this source issues, from its mode.
     *
     * <p>Package-private because it is the honest place to assert what the connector asks
     * the server for -- the command, the bookmark, the {@code oof} that makes a SOW's
     * removals visible -- without a server to ask.
     *
     * @return the command, ready for {@code executeAsync}
     */
    Command buildCommand() {
        Command command = switch (source.getMode()) {
            case SUBSCRIBE -> new Command("subscribe");
            // Out-of-focus delivery is what turns "this record left the SOW" -- a delete, an
            // expiry, a filter it stopped matching -- into a DELETE the target can act on.
            case SOW_AND_SUBSCRIBE -> new Command("sow_and_subscribe").setBatchSize(SOW_BATCH_SIZE);
            case BOOKMARK -> new Command("subscribe").setBookmark(bookmarkOf(source.getBookmark()));
        };
        command.setTopic(source.getTopic());
        command.setTimeout(source.getTimeout().toMillis());
        if (hasText(source.getFilter())) {
            command.setFilter(source.getFilter().trim());
        }
        String options = options();
        if (!options.isEmpty()) {
            command.setOptions(options);
        }
        return command;
    }

    /** {@code oof} for a SOW subscription, then whatever the configuration adds, verbatim. */
    private String options() {
        List<String> options = new ArrayList<>(2);
        List<String> configured = hasText(source.getOptions())
                ? Arrays.stream(source.getOptions().split(",")).map(String::trim)
                        .filter(option -> !option.isEmpty()).toList()
                : List.of();
        if (source.getMode() == AmpsSourceProperties.Mode.SOW_AND_SUBSCRIBE
                && !configured.contains("oof")) {
            options.add("oof");
        }
        options.addAll(configured);
        return String.join(",", options);
    }

    /**
     * The client's spelling of a bookmark.
     *
     * @param bookmark where the subscription starts
     * @return the bookmark string the {@code subscribe} command carries
     */
    static String bookmarkOf(AmpsSourceProperties.Bookmark bookmark) {
        return switch (bookmark) {
            case EPOCH -> Client.Bookmarks.EPOCH;
            case MOST_RECENT -> Client.Bookmarks.MOST_RECENT;
            case NOW -> Client.Bookmarks.NOW;
        };
    }

    /**
     * Turn one AMPS message into a {@link SourceRecord} and hand it over.
     *
     * <p>Runs on the client's receive thread, and the pipeline runs inside
     * {@link RecordHandler#onRecord}, which is the back-pressure: a connector that cannot keep
     * up stops reading its socket, and AMPS queues for it. The message object is the
     * client's and is reused for the next one, which is why everything the record needs is
     * copied out of it before the handler runs.
     *
     * <p>Package-private so a test can push a hand-built message through it.
     */
    void dispatch(Message message, RecordHandler handler) {
        int command = message.getCommand();
        String topic = message.getTopic();
        SourceRecord record = toRecord(command, message.getData(), message.getSowKey(),
                message.getBookmark(), hasText(topic) ? topic : source.getTopic());
        if (record == null) {
            return;
        }
        try {
            handler.onRecord(record);
        } catch (RuntimeException e) {
            // One bad record is not a reason to drop the subscription.
            handlerFailures.incrementAndGet();
            log.error("[{}] failed to handle AMPS {} from '{}'{}", connector.getName(),
                    commandName(command), record.attributes().get(ATTRIBUTE_TOPIC),
                    record.key() != null ? " (key " + record.key() + ")" : "", e);
        } finally {
            discard(message);
        }
    }

    /**
     * One AMPS message as a record, or {@code null} when it carries none.
     *
     * <p>Package-private and pure so the table in the class comment can be asserted without
     * a client: which commands are upserts, which are deletes, which are noise, and what
     * rides along as attributes.
     *
     * @param command the {@link Message.Command} the message arrived as
     * @param data the payload; {@code null} becomes {@code ""}
     * @param sowKey the SowKey, or {@code null}/blank for a topic without one
     * @param bookmark the bookmark, or {@code null}/blank when the subscription has none
     * @param topic the topic the message was read from
     * @return the record, or {@code null} for a command that carries none
     */
    static SourceRecord toRecord(
            int command, String data, String sowKey, String bookmark, String topic) {
        SourceRecord.Action action = switch (command) {
            case Message.Command.SOW, Message.Command.Publish, Message.Command.DeltaPublish ->
                    SourceRecord.Action.UPSERT;
            // The payload stays on a delete: an out-of-focus message carries the record's
            // last state, and a target that deletes by filter needs the key fields in it.
            case Message.Command.OOF, Message.Command.SOWDelete -> SourceRecord.Action.DELETE;
            // GroupBegin/GroupEnd/Ack/Heartbeat carry no record.
            default -> null;
        };
        if (action == null) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>(3);
        if (hasText(topic)) {
            attributes.put(ATTRIBUTE_TOPIC, topic);
        }
        attributes.put(ATTRIBUTE_COMMAND, commandName(command));
        if (hasText(bookmark)) {
            attributes.put(ATTRIBUTE_BOOKMARK, bookmark);
        }
        return new SourceRecord(data == null ? "" : data, hasText(sowKey) ? sowKey : null,
                action, attributes, null);
    }

    /**
     * The wire name of a {@link Message.Command}, for the {@code command} attribute and the
     * logs; the client has constants but no lookup.
     */
    static String commandName(int command) {
        return switch (command) {
            case Message.Command.SOW -> "sow";
            case Message.Command.Publish -> "publish";
            case Message.Command.DeltaPublish -> "delta_publish";
            case Message.Command.OOF -> "oof";
            case Message.Command.SOWDelete -> "sow_delete";
            case Message.Command.Subscribe -> "subscribe";
            case Message.Command.SOWAndSubscribe -> "sow_and_subscribe";
            case Message.Command.GroupBegin -> "group_begin";
            case Message.Command.GroupEnd -> "group_end";
            case Message.Command.Ack -> "ack";
            case Message.Command.Heartbeat -> "heartbeat";
            default -> "command#" + command;
        };
    }

    /**
     * Tell the bookmark store the message has been taken, so a reconnect resumes after it
     * and the store does not keep it. Only a bookmark subscription logs anything there.
     */
    private void discard(Message message) {
        if (source.getMode() != AmpsSourceProperties.Mode.BOOKMARK || !hasText(message.getBookmark())) {
            return;
        }
        HAClient amps = this.client;
        if (amps == null || amps.getBookmarkStore() == null) {
            return;
        }
        try {
            amps.getBookmarkStore().discard(message);
        } catch (AMPSException | RuntimeException e) {
            log.debug("[{}] bookmark discard failed: {}", connector.getName(), e.toString());
        }
    }

    /**
     * Track the client's own view of the connection, so {@link #isConnected()} is honest
     * between the logon and whatever happens after it.
     */
    private void onConnectionState(int state) {
        switch (state) {
            case ConnectionStateListener.LoggedOn -> {
                if (!closed.get()) {
                    connected.set(true);
                    log.info("[{}] logged on to {} as '{}'", connector.getName(), uri, clientName);
                }
            }
            case ConnectionStateListener.Resubscribed -> {
                // Broadcast after the first logon too, so this is not always "after a drop".
                if (!closed.get()) {
                    connected.set(true);
                    log.debug("[{}] subscriptions issued on {}", connector.getName(), uri);
                }
            }
            case ConnectionStateListener.Disconnected, ConnectionStateListener.Shutdown -> {
                connected.set(false);
                if (!closed.get()) {
                    log.warn("[{}] disconnected from {}; the client is redialling",
                            connector.getName(), uri);
                }
            }
            default -> {
                // Connected (not yet logged on), PublishReplayed, HeartbeatInitiated: no change.
            }
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    /** Records the handler threw on; each was logged, and the subscription carried on. */
    public long handlerFailures() {
        return handlerFailures.get();
    }

    @Override
    public void close() {
        closed.set(true);
        connected.set(false);
        synchronized (backoff) {
            backoff.notifyAll();
        }
        disconnect();

        Thread runner = this.thread;
        this.thread = null;
        if (runner == null || runner == Thread.currentThread()) {
            return;
        }
        try {
            runner.join(CLOSE_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (runner.isAlive()) {
            log.warn("[{}] AMPS connect thread did not stop within {}ms",
                    connector.getName(), CLOSE_JOIN_MILLIS);
        }
    }

    /**
     * Unsubscribe and close the client. Idempotent, and also what ends a
     * {@code connectAndLogon} still retrying on the connect thread: closing the client sets
     * its disconnected flag, which is the retry loop's exit condition.
     */
    private void disconnect() {
        HAClient amps = this.client;
        this.client = null;
        CommandId subscribed = this.subscription;
        this.subscription = null;
        if (amps == null) {
            return;
        }
        if (subscribed != null) {
            try {
                amps.unsubscribe(subscribed);
            } catch (AMPSException | RuntimeException e) {
                // Usually "not connected": the server drops the subscription with the
                // connection anyway.
                log.debug("[{}] unsubscribe on close failed: {}", connector.getName(), e.toString());
            }
        }
        closeQuietly(amps);
        log.info("[{}] disconnected from {}", connector.getName(), uri);
    }

    private void closeQuietly(HAClient amps) {
        if (amps == null) {
            return;
        }
        try {
            amps.close();
        } catch (RuntimeException e) {
            log.debug("[{}] AMPS client close failed", connector.getName(), e);
        }
    }

    /**
     * Wait out the reconnect backoff, returning early when {@link #close()} rings the monitor.
     *
     * @param delay the configured backoff
     * @return {@code false} if the source should stop instead of retrying
     */
    private boolean sleep(Duration delay) {
        synchronized (backoff) {
            if (closed.get()) {
                return false;
            }
            try {
                backoff.wait(Math.max(1L, delay.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !closed.get();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "AmpsRecordSource[" + uri + " as " + clientName + ", " + source.getMode()
                + " " + source.getTopic() + "]";
    }
}
