package com.demo.amps.connectors.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/**
 * The AMPS side of one connector whose <em>source</em> is an AMPS topic: which topic, how it is
 * subscribed, and -- optionally -- which server, when it is not the one the application
 * publishes into.
 *
 * <p>Lives under {@code source.amps}; its presence is what selects the AMPS source
 * ({@link SourceProperties}). It exists for two jobs that look different and are the same
 * code: bridging a topic from one AMPS instance to another (or to a differently shaped topic
 * on the same one), and feeding the framework's control channel, which is a subscription like
 * any other. The message type is not configured here because the connector's {@code format}
 * already says what the payload is, and in AMPS the message type belongs to the connection
 * URI -- so {@code format: FIX} subscribes through {@code /amps/fix}. {@code TEXT} has no AMPS
 * message type and is refused.
 *
 * <h2>Three ways to read a topic</h2>
 *
 * <table border="1">
 *   <caption>What each mode delivers, and what a reconnect costs</caption>
 *   <tr><th>{@code mode}</th><th>AMPS command</th><th>delivers</th><th>after a reconnect</th></tr>
 *   <tr><td>{@link Mode#SUBSCRIBE}</td><td>{@code subscribe}</td>
 *       <td>every publish from now on; nothing that happened before</td>
 *       <td>the subscription is re-issued live, so whatever was published during the outage
 *           is missed -- the socket-feed shape</td></tr>
 *   <tr><td>{@link Mode#SOW_AND_SUBSCRIBE}</td><td>{@code sow_and_subscribe} + {@code oof}</td>
 *       <td>the SOW's current records, then every publish; a record leaving the SOW (a
 *           {@code sow_delete}, an expiry, a filter it stopped matching) arrives as an
 *           out-of-focus message and becomes a {@code DELETE}</td>
 *       <td>the SOW is read again -- duplicate upserts, which a keyed target absorbs; the
 *           repair is the same as the Hazelcast map source's snapshot</td></tr>
 *   <tr><td>{@link Mode#BOOKMARK}</td><td>{@code subscribe} with a bookmark</td>
 *       <td>the transaction log from {@code bookmark} onwards, then live; a journalled
 *           {@code sow_delete} arrives as one and becomes a {@code DELETE}</td>
 *       <td>resumes from the last message this process delivered, because the client keeps an
 *           in-memory bookmark store; nothing is missed</td></tr>
 * </table>
 *
 * <p>The bookmark store is in memory, so a <em>restart</em> starts over from {@code bookmark}:
 * {@link Bookmark#EPOCH} replays the whole log again, {@link Bookmark#NOW} skips everything
 * the process was not there for, and {@link Bookmark#MOST_RECENT} -- the default -- has nothing
 * to resume from on a fresh start and therefore behaves as {@code EPOCH} then, and as "where I
 * left off" after every reconnect. Replaying is the at-least-once trade the whole framework
 * makes, and a keyed target makes it invisible; a persistent bookmark store, which would turn
 * a restart into a resume, is a follow-up. A bookmark can only be replayed from a topic the
 * server journals ({@code <TransactionLog>} in the flow configuration), so the mode also
 * documents what the flow has to provide.
 */
public class AmpsSourceProperties {

    /** How the topic is read. */
    public enum Mode {
        /** Live messages only: {@code subscribe}. */
        SUBSCRIBE,
        /** The SOW's records first, then live, with out-of-focus deletes: {@code sow_and_subscribe}. */
        SOW_AND_SUBSCRIBE,
        /** The transaction log from {@link AmpsSourceProperties#getBookmark()} onwards, then live. */
        BOOKMARK
    }

    /** Where a {@link Mode#BOOKMARK} subscription starts. */
    public enum Bookmark {
        /** The beginning of the transaction log: everything the topic ever carried. */
        EPOCH,
        /**
         * The last message this process delivered -- and, on a fresh start where there is no
         * such message, the epoch.
         */
        MOST_RECENT,
        /** Now: nothing already journalled, only what is published from here on. */
        NOW
    }

    /**
     * Another AMPS instance to subscribe to, when the source is not the server every
     * connector publishes into ({@code amps-connectors.amps}).
     *
     * <p>Only the address: the client name prefix and the timeouts stay the application's,
     * because they describe this process rather than the server it dials.
     */
    public static class Endpoint {

        /** AMPS host. */
        @NotBlank
        private String host = "localhost";

        /** AMPS client port -- the one the flow's Transport declares. */
        @Min(1)
        @Max(65535)
        private int port = 9007;

        /** Transport scheme: {@code tcp} or {@code tcps}. */
        @NotBlank
        private String transport = "tcp";

        /**
         * The connection URI for a message type, shaped exactly as
         * {@link AmpsServerProperties#uri(String)} shapes the shared block's.
         *
         * @param messageType {@code json}, {@code fix} or {@code nvfix}
         * @return e.g. {@code tcp://amps-2:9007/amps/json}
         */
        public String uri(String messageType) {
            return transport + "://" + host + ":" + port + "/amps/" + messageType;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }
    }

    /** The topic to subscribe to; a regular expression subscribes to every topic it matches. */
    @NotBlank
    private String topic;

    /**
     * An AMPS content filter ({@code /35 = 'D'}), evaluated server-side, so what the connector
     * never needs never crosses the wire. Absent means every message.
     */
    private String filter;

    /** How the topic is read. */
    @NotNull
    private Mode mode = Mode.SUBSCRIBE;

    /** Where a {@link Mode#BOOKMARK} subscription starts; ignored by the other modes. */
    @NotNull
    private Bookmark bookmark = Bookmark.MOST_RECENT;

    /**
     * Extra AMPS subscription options, appended verbatim ({@code conflation=250ms},
     * {@code no_empties}, ...). {@code oof} is already set for {@link Mode#SOW_AND_SUBSCRIBE}.
     */
    private String options;

    /**
     * The server to read from, when it is not the application's own. Absent means the shared
     * {@code amps-connectors.amps} block -- a bridge between two topics of one instance.
     */
    @Valid
    private Endpoint server;

    /** How long a logon, and the subscription's own acknowledgment, may take. */
    @NotNull
    private Duration timeout = Duration.ofSeconds(10);

    /** Backoff between connection attempts, before the first logon and after a drop. */
    @NotNull
    private Duration reconnectDelay = Duration.ofSeconds(5);

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Bookmark getBookmark() {
        return bookmark;
    }

    public void setBookmark(Bookmark bookmark) {
        this.bookmark = bookmark;
    }

    public String getOptions() {
        return options;
    }

    public void setOptions(String options) {
        this.options = options;
    }

    public Endpoint getServer() {
        return server;
    }

    public void setServer(Endpoint server) {
        this.server = server;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(Duration reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }
}
