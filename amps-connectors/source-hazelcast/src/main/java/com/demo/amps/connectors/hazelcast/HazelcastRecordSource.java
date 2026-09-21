package com.demo.amps.connectors.hazelcast;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.demo.amps.connectors.source.RecordHandler;
import com.demo.amps.connectors.source.RecordSource;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientConnectionStrategyConfig;
import com.hazelcast.cluster.Address;
import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordSource} over a Hazelcast topic or a Hazelcast map.
 *
 * <p>Always a Hazelcast <em>client</em> ({@link HazelcastClient#newHazelcastClient}), never an
 * embedded member. A connector that joined the cluster would own a share of its partitions, so
 * restarting the connector would migrate data and a connector bug would become a cluster bug;
 * a client is a subscriber the cluster can lose without noticing.
 *
 * <h2>Two structures, and they are not two spellings of one feed</h2>
 *
 * <table border="1">
 *   <caption>What each structure gives the pipeline</caption>
 *   <tr><th></th><th>{@code topic:} ({@link TopicSubscription})</th>
 *       <th>{@code map:} ({@link MapSubscription})</th></tr>
 *   <tr><td>a record is</td><td>one published message</td>
 *       <td>one entry event, or one row of the snapshot</td></tr>
 *   <tr><td>the key</td><td>none: a keyed AMPS topic keys on the payload</td>
 *       <td>the entry key, as {@code String.valueOf(key)}</td></tr>
 *   <tr><td>removals</td><td>none -- a topic cannot say a thing stopped existing</td>
 *       <td>remove / evict / expire become a {@code DELETE} with an empty body</td></tr>
 *   <tr><td>what a (re)connect sees</td>
 *       <td>nothing, unless {@code reliable: true} replays a ringbuffer</td>
 *       <td>the whole map, unless {@code snapshot: false}</td></tr>
 *   <tr><td>delivery</td><td>at-most-once (plain) / ringbuffer-bounded (reliable)</td>
 *       <td>at-most-once events, unordered across partitions, repaired by the snapshot</td></tr>
 *   <tr><td>attributes</td><td>{@code publishTime}, {@code member}</td>
 *       <td>{@code map}, {@code event}, {@code member}</td></tr>
 *   <tr><td>seqno</td><td>the ringbuffer sequence (reliable); a delivery counter (plain)</td>
 *       <td>a delivery counter over events and snapshot rows alike</td></tr>
 *   <tr><td>acknowledgment</td><td colspan="2">none: neither structure has a position the
 *       connector could ask Hazelcast to go back to</td></tr>
 *   <tr><td>a typed value</td><td colspan="2">an {@code IdentifiedDataSerializable} is
 *       handed through as the object under its ids with {@code typed-values: OBJECT}, else
 *       rendered as JSON; its ids ride along as {@code factoryId}/{@code classId} either way
 *       ({@link HazelcastValues})</td></tr>
 * </table>
 *
 * <p>Exactly one of them is configured -- the validator refuses both and neither -- and the
 * choice is made once, here, when the source is built.
 *
 * <p>A value that is an {@code IdentifiedDataSerializable} can only reach either listener
 * once the <em>client</em> can deserialize it, and a client is built from configuration, not
 * from the application's classpath: the {@code DataSerializableFactory} beans named under
 * {@code serialization-factories} are registered on the client's serialization config here,
 * and a connector that names a factory nobody supplied is refused when the source is built
 * rather than when the first value fails to arrive.
 *
 * <h2>Connecting is this source's own job</h2>
 *
 * <p>The client is built on the source's thread with {@code async-start} off, so the instance
 * that exists is an instance that is logged on and {@link #isConnected()} is not a guess. A
 * cluster that is not up yet fails the attempt, which this loop retries after
 * {@code reconnect-delay} -- the same treatment {@code TcpRecordSource} gives a peer that is
 * not listening yet. Once connected, Hazelcast's own {@code ReconnectMode.ON} rides out a
 * blip and re-registers the listener; only a client that actually stops is rebuilt here.
 *
 * <p>A blip is invisible to this loop but not to a map feed, because the entry events raised
 * while the client was away were never queued for it. So a reconnect the client handles by
 * itself still wakes the source thread, which asks the subscription to
 * {@link HazelcastSubscription#refresh} -- for a map, that is the snapshot again, which is the
 * whole reason a lost event costs accuracy only until the next connect. A topic has nothing to
 * re-read and does nothing.
 *
 * <h2>Closing waits for the handler</h2>
 *
 * <p>Events reach the handler on Hazelcast's event threads, and {@code shutdown()} ends those
 * threads by interrupting them -- which, for a delivery that is inside the pipeline at that
 * moment, means a record lost at the aggregator's lock. So every delivery is counted through a
 * {@link DeliveryGate}, and {@link #close()} removes the listener, waits for the count to
 * reach zero, and only then shuts the client down; the join that follows shares the same
 * {@value #CLOSE_JOIN_MILLIS}ms budget.
 */
public class HazelcastRecordSource implements RecordSource {

    private static final Logger log = LoggerFactory.getLogger(HazelcastRecordSource.class);

    /** Attribute carrying the cluster-side publish timestamp, in epoch milliseconds. Topics only. */
    public static final String ATTRIBUTE_PUBLISH_TIME = "publishTime";

    /** Attribute carrying the member the record came from, as {@code host:port}. */
    public static final String ATTRIBUTE_MEMBER = "member";

    /** Attribute carrying the map the entry belongs to. Maps only. */
    public static final String ATTRIBUTE_MAP = "map";

    /**
     * Attribute carrying what happened to the entry: {@code ADDED}, {@code UPDATED},
     * {@code REMOVED}, {@code EVICTED}, {@code EXPIRED} or {@code SNAPSHOT}. Maps only.
     */
    public static final String ATTRIBUTE_EVENT = "event";

    /** Attribute carrying an {@code IdentifiedDataSerializable} value's factory id. Typed values only. */
    public static final String ATTRIBUTE_FACTORY_ID = "factoryId";

    /** Attribute carrying an {@code IdentifiedDataSerializable} value's class id. Typed values only. */
    public static final String ATTRIBUTE_CLASS_ID = "classId";

    /** How long {@link #close()} waits for the source thread before giving up on it. */
    private static final long CLOSE_JOIN_MILLIS = 5_000;

    /**
     * How often the connected source thread re-checks the client while waiting for it to stop.
     * The lifecycle listener rings the monitor too; this is only the belt to its braces.
     */
    private static final long WATCH_POLL_MILLIS = 500;

    private final ConnectorProperties connector;
    private final HazelcastSourceProperties source;
    private final HazelcastSubscription subscription;

    /** The factories the client registers, by factory id: what deserializes a typed value. */
    private final Map<Integer, DataSerializableFactory> factories;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** Set by the lifecycle listener when Hazelcast reconnects a client this loop kept. */
    private final AtomicBoolean reconnected = new AtomicBoolean(false);

    /** Monitor the reconnect backoff and the connected watch wait on, so {@code close()} cuts them short. */
    private final Object backoff = new Object();

    /** Counts deliveries inside the handler, so the client is not shut down under one. */
    private final DeliveryGate gate;

    private volatile HazelcastInstance client;
    private volatile Thread thread;

    /**
     * A source for a connector whose values need no factory of the application's.
     *
     * @param connector the connector configuration
     * @throws IllegalArgumentException if the connector names a serialization factory -- it
     *     needs the constructor that takes them, which is what {@link HazelcastSourceFactory}
     *     calls
     */
    public HazelcastRecordSource(ConnectorProperties connector) {
        this(connector, Map.of());
    }

    /**
     * @param connector the connector configuration
     * @param factories the application's {@code DataSerializableFactory} beans by factory id,
     *     resolved from {@code serialization-factories}; registered on every client this
     *     source builds
     * @throws IllegalArgumentException if the connector names a factory id these do not
     *     supply -- a client without it would fail on the first typed value, and say so on a
     *     Hazelcast event thread rather than at start
     */
    public HazelcastRecordSource(
            ConnectorProperties connector, Map<Integer, DataSerializableFactory> factories) {
        this.connector = connector;
        this.source = connector.getSource().getHazelcast();
        this.factories = new LinkedHashMap<>(factories);
        for (Map.Entry<Integer, String> named : source.getSerializationFactories().entrySet()) {
            if (!this.factories.containsKey(named.getKey())) {
                throw new IllegalArgumentException("connector '" + connector.getName()
                        + "': source.hazelcast.serialization-factories names factory "
                        + named.getKey() + " ('" + named.getValue() + "'), but no factory was "
                        + "supplied for it -- the source has to be built through "
                        + "HazelcastSourceFactory, which resolves the bean");
            }
        }
        // Once, here: which structure a connector reads is configuration, not something to
        // re-decide per event or per reconnect.
        this.subscription = source.getMap() != null
                ? new MapSubscription(connector)
                : new TopicSubscription(connector);
        this.gate = new DeliveryGate(connector.getName());
    }

    @Override
    public void start(RecordHandler handler) {
        log.info("[{}] starting Hazelcast source: {} on cluster '{}' at {}",
                connector.getName(), subscription.describe(), source.getClusterName(),
                source.getMembers());
        // Every path to the handler -- entry events, topic messages, the snapshot -- goes
        // through the gate, so close() can wait for the ones in flight before the client goes.
        RecordHandler guarded = gate.guard(handler);
        Thread runner = new Thread(() -> run(guarded), connector.getName() + "-hazelcast");
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
                watch(handler);
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
                disconnect(System.currentTimeMillis() + CLOSE_JOIN_MILLIS);
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

    /**
     * Build the client, subscribe, and only then report connected.
     *
     * <p>A map subscription reads the whole map before this returns, on this thread, so
     * {@link #isConnected()} turns true when the connector is genuinely caught up rather than
     * when its socket opened.
     */
    private void connect(RecordHandler handler) {
        HazelcastInstance connecting = HazelcastClient.newHazelcastClient(clientConfig());
        this.client = connecting;
        if (closed.get()) {
            // close() publishes `closed` and then reads `client`; this reads them the other
            // way round, so between the two at least one of us sees the other.
            return;
        }
        // Added after the client is logged on, so the CLIENT_CONNECTED of this first connect
        // has already been and gone: any event this listener sees is a genuine reconnect.
        connecting.getLifecycleService().addLifecycleListener(this::onLifecycleEvent);
        reconnected.set(false);
        subscription.subscribe(connecting, handler);
        connected.set(true);
        log.info("[{}] subscribed to {} on cluster '{}'",
                connector.getName(), subscription.describe(), source.getClusterName());
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
     * <p>The serialization factories are the application's, registered here because a
     * client deserializes every value before its listener sees it: without the factory for an
     * {@code IdentifiedDataSerializable} the client cannot deliver the value at all, in either
     * {@code typed-values} mode.
     *
     * @return the client configuration for this connector
     */
    ClientConfig clientConfig() {
        ClientConfig config = new ClientConfig();
        config.setClusterName(source.getClusterName());
        config.setInstanceName(connector.getName() + "-hazelcast");
        // The connector's logging, not Hazelcast's own JUL default.
        config.setProperty("hazelcast.logging.type", "slf4j");
        factories.forEach((id, factory) ->
                config.getSerializationConfig().addDataSerializableFactory(id, factory));
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

    /**
     * Park until the client stops running or {@link #close()} says to stop, refreshing the
     * subscription whenever Hazelcast reconnected the client underneath it.
     *
     * @param handler where a refresh's records go
     */
    private void watch(RecordHandler handler) {
        HazelcastInstance running = this.client;
        if (running == null) {
            return;
        }
        while (!closed.get() && running.getLifecycleService().isRunning()) {
            if (reconnected.compareAndSet(true, false)) {
                // Outside the monitor: re-reading a large map takes a while, and close() must
                // not queue behind it.
                subscription.refresh(running, handler);
                continue;
            }
            synchronized (backoff) {
                if (closed.get() || reconnected.get()) {
                    continue;
                }
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
                // This client was already logged on when the listener was added, so this is a
                // reconnect: whatever the feed raised while it was away was raised to nobody.
                reconnected.set(true);
                synchronized (backoff) {
                    backoff.notifyAll();
                }
                log.info("[{}] Hazelcast client reconnected", connector.getName());
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

    // ---- lifecycle ---------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * How many times the whole map was cleared or evicted under this source.
     *
     * <p>Those are the events that remove entries without naming one of them, so they publish
     * no deletes and the SOW keeps records the map no longer has. Always {@code 0} for a topic
     * connector, which has no such event.
     *
     * @return the count of map-wide clears and evictions
     */
    public long mapWideEvents() {
        return subscription instanceof MapSubscription map ? map.mapWideEvents() : 0;
    }

    @Override
    public void close() {
        closed.set(true);
        connected.set(false);
        // Before anything else: a delivery that has not reached the handler yet is turned
        // away, and the ones that have are what disconnect() waits for.
        gate.close();
        synchronized (backoff) {
            backoff.notifyAll();
        }
        // One budget for the whole close: the wait for in-flight deliveries and the join of
        // the source thread share it, the way the TCP source shares its join across readers.
        long deadline = System.currentTimeMillis() + CLOSE_JOIN_MILLIS;
        disconnect(deadline);

        Thread runner = this.thread;
        this.thread = null;
        if (runner == null || runner == Thread.currentThread()) {
            return;
        }
        try {
            runner.join(Math.max(1L, deadline - System.currentTimeMillis()));
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
     * Remove the listener, wait for the deliveries already inside the handler, and shut the
     * client down. Idempotent, and called from both {@link #close()} and the source thread's
     * own {@code finally} -- whichever gets there first does the work.
     *
     * <p>The wait sits between the other two on purpose. With the listener gone nothing new is
     * dispatched, so the count can only fall; and {@code shutdown()} interrupts the event
     * threads, so it must not run while one of them is inside the pipeline. On the reconnect
     * path the gate stays open -- the client being replaced is the only thing that stops.
     *
     * @param deadlineMillis how long, as an absolute time, to wait for the handler
     */
    private void disconnect(long deadlineMillis) {
        subscription.unsubscribe();
        HazelcastInstance running = this.client;
        this.client = null;
        if (running != null) {
            if (!gate.awaitIdle(deadlineMillis)) {
                log.warn("[{}] {} Hazelcast delivery(ies) still inside the handler after {}ms; "
                                + "shutting the client down under them",
                        connector.getName(), gate.inFlight(), CLOSE_JOIN_MILLIS);
            }
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

    /**
     * A cluster member as {@code host:port}, which is what both subscriptions report.
     *
     * @param member the member, possibly {@code null} for a local publish
     * @return the address, its UUID when the member has no address, or {@code null}
     */
    static String addressOf(Member member) {
        if (member == null) {
            return null;
        }
        Address address = member.getAddress();
        return address == null
                ? member.getUuid().toString()
                : address.getHost() + ":" + address.getPort();
    }
}
