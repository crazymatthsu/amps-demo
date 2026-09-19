package com.demo.amps.connectors.hazelcast;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.demo.amps.connectors.source.RecordHandler;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceRecord;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientConnectionStrategyConfig;
import com.hazelcast.cluster.Address;
import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.topic.ITopic;
import com.hazelcast.topic.Message;
import com.hazelcast.topic.ReliableMessageListener;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordSource} over a Hazelcast topic.
 *
 * <p>Always a Hazelcast <em>client</em> ({@link HazelcastClient#newHazelcastClient}), never an
 * embedded member. A connector that joined the cluster would own a share of its partitions, so
 * restarting the connector would migrate data and a connector bug would become a cluster bug;
 * a client is a subscriber the cluster can lose without noticing.
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
 * AMPS has confirmed a batch. Records therefore carry no
 * {@link com.demo.amps.connectors.source.Acknowledgment}, and no key either: a topic message
 * is a payload and nothing more, so a keyed AMPS topic gets its key from the payload.
 *
 * <h2>Connecting is this source's own job</h2>
 *
 * <p>The client is built on the source's thread with {@code async-start} off, so the instance
 * that exists is an instance that is logged on and {@link #isConnected()} is not a guess. A
 * cluster that is not up yet fails the attempt, which this loop retries after
 * {@code reconnect-delay} -- the same treatment {@code TcpRecordSource} gives a peer that is
 * not listening yet. Once connected, Hazelcast's own {@code ReconnectMode.ON} rides out a
 * blip and re-registers the listener; only a client that actually stops is rebuilt here.
 */
public class HazelcastRecordSource implements RecordSource {

    private static final Logger log = LoggerFactory.getLogger(HazelcastRecordSource.class);

    /** Attribute carrying the cluster-side publish timestamp, in epoch milliseconds. */
    public static final String ATTRIBUTE_PUBLISH_TIME = "publishTime";

    /** Attribute carrying the member that published the message, as {@code host:port}. */
    public static final String ATTRIBUTE_MEMBER = "member";

    /** How long {@link #close()} waits for the source thread before giving up on it. */
    private static final long CLOSE_JOIN_MILLIS = 5_000;

    /**
     * How often the connected source thread re-checks the client while waiting for it to stop.
     * The lifecycle listener rings the monitor too; this is only the belt to its braces.
     */
    private static final long WATCH_POLL_MILLIS = 500;

    private final ConnectorProperties connector;
    private final HazelcastSourceProperties source;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** One WARN per source for non-String payloads: the second one says nothing new. */
    private final AtomicBoolean warnedAboutPayloadType = new AtomicBoolean(false);

    /** Monitor the reconnect backoff and the connected watch wait on, so {@code close()} cuts them short. */
    private final Object backoff = new Object();

    private volatile HazelcastInstance client;
    private volatile ITopic<Object> topic;
    private volatile UUID listener;
    private volatile Thread thread;

    public HazelcastRecordSource(ConnectorProperties connector) {
        this.connector = connector;
        this.source = connector.getSource().getHazelcast();
    }

    @Override
    public void start(RecordHandler handler) {
        log.info("[{}] starting Hazelcast source: {} topic '{}' on cluster '{}' at {}",
                connector.getName(), source.isReliable() ? "reliable" : "plain",
                source.getTopic(), source.getClusterName(), source.getMembers());
        Thread runner = new Thread(() -> run(handler), connector.getName() + "-hazelcast");
        runner.setDaemon(true);
        this.thread = runner;
        runner.start();
    }

    /**
     * Connect, subscribe, then watch the client until it stops; back off and rebuild it.
     *
     * <p>One iteration of the loop is one client's lifetime. The thread stays parked while the
     * client is healthy rather than exiting, because a client that shuts itself down (it ran
     * out of cluster-connect timeout) has to be replaced by something, and a thread that is
     * already there is cheaper than a watchdog that is not.
     */
    private void run(RecordHandler handler) {
        while (!closed.get()) {
            try {
                connect(handler);
                watch();
            } catch (RuntimeException e) {
                if (!closed.get()) {
                    // A cluster that is not up yet is the normal case here, and its stack
                    // trace says nothing the message does not: keep the loop's log readable
                    // and put the trace behind DEBUG.
                    log.warn("[{}] Hazelcast cluster '{}' unreachable at {}: {}",
                            connector.getName(), source.getClusterName(), source.getMembers(),
                            e.toString());
                    log.debug("[{}] Hazelcast connect failed", connector.getName(), e);
                }
            } finally {
                connected.set(false);
                disconnect();
            }
            if (!closed.get()) {
                log.info("[{}] reconnecting to Hazelcast in {}",
                        connector.getName(), source.getReconnectDelay());
                if (!sleep(source.getReconnectDelay())) {
                    break;
                }
            }
        }
        connected.set(false);
        log.info("[{}] Hazelcast source stopped", connector.getName());
    }

    /** Build the client, subscribe, and only then report connected. */
    private void connect(RecordHandler handler) {
        HazelcastInstance connecting = HazelcastClient.newHazelcastClient(clientConfig());
        this.client = connecting;
        if (closed.get()) {
            // close() publishes `closed` and then reads `client`; this reads them the other
            // way round, so between the two at least one of us sees the other.
            return;
        }
        connecting.getLifecycleService().addLifecycleListener(this::onLifecycleEvent);
        ITopic<Object> subscribed = source.isReliable()
                ? connecting.getReliableTopic(source.getTopic())
                : connecting.getTopic(source.getTopic());
        this.topic = subscribed;
        this.listener = source.isReliable()
                ? subscribed.addMessageListener(new ReplayingListener(handler))
                : subscribed.addMessageListener(message -> deliver(message, handler));
        connected.set(true);
        log.info("[{}] subscribed to {} topic '{}' on cluster '{}'{}", connector.getName(),
                source.isReliable() ? "reliable" : "plain", source.getTopic(),
                source.getClusterName(),
                source.isReliable() ? " from " + source.getReliableFrom() : "");
    }

    /**
     * The client configuration, kept in one place so a test can read what a connector dials.
     *
     * <p>{@code cluster-connect-timeout} is pinned to {@code connection-timeout} rather than
     * left at its default, which for {@link ClientConnectionStrategyConfig.ReconnectMode#ON}
     * is infinite: an infinite first attempt would park this thread forever on a cluster that
     * never comes up, and {@link #isConnected()} would have nothing to report. Bounded, a
     * failed attempt falls into this source's own backoff instead.
     *
     * @return the client configuration for this connector
     */
    ClientConfig clientConfig() {
        ClientConfig config = new ClientConfig();
        config.setClusterName(source.getClusterName());
        config.setInstanceName(connector.getName() + "-hazelcast");
        // The connector's logging, not Hazelcast's own JUL default.
        config.setProperty("hazelcast.logging.type", "slf4j");
        config.getNetworkConfig()
                .setAddresses(source.getMembers())
                .setConnectionTimeout((int) source.getConnectionTimeout().toMillis());
        ClientConnectionStrategyConfig strategy = config.getConnectionStrategyConfig();
        // Off: newHazelcastClient() must either hand back a logged-on client or throw, so
        // that "the source thread returned" and "the source is connected" mean the same thing.
        strategy.setAsyncStart(false);
        // ON: a blip is Hazelcast's problem -- it reconnects and re-registers the listener
        // without the connector noticing. Only a client that gives up entirely lands back in
        // the loop above.
        strategy.setReconnectMode(ClientConnectionStrategyConfig.ReconnectMode.ON);
        strategy.getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(source.getConnectionTimeout().toMillis());
        return config;
    }

    /** Park until the client stops running or {@link #close()} says to stop. */
    private void watch() {
        HazelcastInstance running = this.client;
        if (running == null) {
            return;
        }
        synchronized (backoff) {
            while (!closed.get() && running.getLifecycleService().isRunning()) {
                try {
                    backoff.wait(WATCH_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Track the client's own view of the cluster, so {@link #isConnected()} is honest between
     * a blip and the reconnect Hazelcast performs by itself.
     */
    private void onLifecycleEvent(LifecycleEvent event) {
        switch (event.getState()) {
            case CLIENT_CONNECTED -> {
                connected.set(true);
                log.info("[{}] Hazelcast client connected", connector.getName());
            }
            case CLIENT_DISCONNECTED -> {
                connected.set(false);
                log.warn("[{}] Hazelcast client disconnected from cluster '{}'",
                        connector.getName(), source.getClusterName());
            }
            case SHUTDOWN -> {
                connected.set(false);
                synchronized (backoff) {
                    // Wake the watch: this client is finished, so the loop can rebuild it.
                    backoff.notifyAll();
                }
            }
            default -> {
                // STARTING/STARTED/MERGING/... say nothing about the subscription.
            }
        }
    }

    // ---- delivery ---------------------------------------------------------------------

    /**
     * A reliable-topic listener: same delivery, plus the ringbuffer position that makes
     * {@code reliable-from} mean something.
     */
    private final class ReplayingListener implements ReliableMessageListener<Object> {

        private final RecordHandler handler;

        /** Last sequence Hazelcast handed us; kept so a restarted listener could resume. */
        private volatile long sequence = -1;

        private ReplayingListener(RecordHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onMessage(Message<Object> message) {
            deliver(message, handler);
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
     */
    private void deliver(Message<Object> message, RecordHandler handler) {
        try {
            Object payload = message.getMessageObject();
            if (!(payload instanceof String) && warnedAboutPayloadType.compareAndSet(false, true)) {
                // Still published -- a bridge that silently dropped a feed's messages because
                // they were published as objects would be worse than one that publishes their
                // rendering. But say so once: toString() is the publisher's, not a contract.
                log.warn("[{}] Hazelcast topic '{}' publishes {} rather than String; "
                                + "bridging its toString() -- decoding may fail",
                        connector.getName(), source.getTopic(), payload.getClass().getName());
            }
            handler.onRecord(SourceRecord.of(String.valueOf(payload))
                    .withAttributes(attributesOf(message)));
        } catch (RuntimeException e) {
            // One bad record is not a reason to drop the subscription.
            log.error("[{}] failed to handle Hazelcast message", connector.getName(), e);
        }
    }

    /** Publish time and publisher: the only transport metadata a topic message carries. */
    private static Map<String, String> attributesOf(Message<Object> message) {
        Map<String, String> attributes = new LinkedHashMap<>(4);
        attributes.put(ATTRIBUTE_PUBLISH_TIME, Long.toString(message.getPublishTime()));
        Member publisher = message.getPublishingMember();
        if (publisher != null) {
            Address address = publisher.getAddress();
            attributes.put(ATTRIBUTE_MEMBER, address == null
                    ? publisher.getUuid().toString()
                    : address.getHost() + ":" + address.getPort());
        }
        return attributes;
    }

    // ---- lifecycle ---------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return connected.get();
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
            log.warn("[{}] Hazelcast source thread did not stop within {}ms",
                    connector.getName(), CLOSE_JOIN_MILLIS);
        }
    }

    /**
     * Remove the listener and shut the client down. Idempotent, and called from both
     * {@link #close()} and the source thread's own {@code finally} -- whichever gets there
     * first does the work.
     */
    private void disconnect() {
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
        HazelcastInstance running = this.client;
        this.client = null;
        if (running != null) {
            try {
                running.shutdown();
            } catch (RuntimeException e) {
                log.debug("[{}] Hazelcast client shutdown failed", connector.getName(), e);
            }
        }
    }

    /**
     * Wait out the reconnect backoff, returning early when {@link #close()} rings the monitor.
     *
     * @param delay the configured backoff
     * @return {@code false} if the source should stop instead of reconnecting
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
}
