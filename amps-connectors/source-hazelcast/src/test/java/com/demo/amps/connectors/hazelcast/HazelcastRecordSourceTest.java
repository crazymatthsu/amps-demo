package com.demo.amps.connectors.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.demo.amps.connectors.source.Acknowledger;
import com.demo.amps.connectors.source.InboundRecord;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.cluster.Address;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.Ringbuffer;
import com.hazelcast.ringbuffer.impl.RingbufferService;
import com.hazelcast.topic.ITopic;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The Hazelcast source against a real embedded member the test starts itself.
 *
 * <p>A mock would prove nothing here: the questions worth asking are whether a plain topic
 * really does lose what was published before the subscription, whether a reliable one really
 * does replay it -- and number each message with the ringbuffer sequence it sits at --
 * whether a typed value really does arrive as the object once the client has its factory,
 * and whether the client really does wait for a cluster that is not up yet. All of them are
 * Hazelcast's behaviour rather than this driver's, so the driver is tested against Hazelcast.
 *
 * <p>One member for the whole class, on its own cluster name (this JVM's pid), bound to
 * loopback with every join mechanism off -- otherwise a colleague running Hazelcast on the
 * same subnet joins the test's cluster, or the test joins theirs. The one restart is the last
 * test's doing: "the member is not up yet" needs a member that is not up yet.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class HazelcastRecordSourceTest {

    /** Nobody else's cluster, even on a shared build host. */
    private static final String CLUSTER = "amps-connectors-test-" + ProcessHandle.current().pid();

    /** Away from Hazelcast's default 5701, so a developer's own member is not in the way. */
    private static final int BASE_PORT = 5901;

    private HazelcastInstance member;

    /** The port the member actually took, which is what the client is pointed at. */
    private int port;

    @BeforeAll
    void startMember() {
        member = Hazelcast.newHazelcastInstance(memberConfig(BASE_PORT, true));
        port = memberPort(member);
    }

    @AfterAll
    void stopMember() {
        HazelcastClient.shutdownAll();
        Hazelcast.shutdownAll();
    }

    // ---- fixtures ------------------------------------------------------------------

    /**
     * A single-member cluster that talks to nothing it was not told about.
     *
     * @param port the port to bind
     * @param autoIncrement whether to walk up from {@code port} when it is taken -- off when
     *     the client is already configured for one exact port
     */
    private Config memberConfig(int port, boolean autoIncrement) {
        Config config = new Config();
        config.setClusterName(CLUSTER);
        // A unit test is not a telemetry opportunity, and the call slows down startup.
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.logging.type", "slf4j");
        // The test shuts its own members down; a hook would fight the JVM's exit.
        config.setProperty("hazelcast.shutdownhook.enabled", "false");

        // The member never deserializes a Trade itself, but a test that reads one back
        // through the member would, and a member that knows the factory cannot surprise one.
        config.getSerializationConfig()
                .addDataSerializableFactory(Trades.FACTORY_ID, new Trades.Factory());

        NetworkConfig network = config.getNetworkConfig();
        network.setPort(port).setPortAutoIncrement(autoIncrement);
        network.getInterfaces().setEnabled(true).clear().addInterface("127.0.0.1");

        JoinConfig join = network.getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        return config;
    }

    private static int memberPort(HazelcastInstance instance) {
        Address address = instance.getCluster().getLocalMember().getAddress();
        return address.getPort();
    }

    /** A connector pointed at this test's member, with test-sized timeouts. */
    private ConnectorProperties connector(String name, String topic) {
        ConnectorProperties connector = TestConnectors.hazelcast(name, topic);
        HazelcastSourceProperties hazelcast = connector.getSource().getHazelcast();
        hazelcast.setClusterName(CLUSTER);
        hazelcast.setMembers(List.of("127.0.0.1:" + port));
        hazelcast.setConnectionTimeout(Duration.ofSeconds(2));
        // Production backs off for seconds; a test that waited them out would be a slow test.
        hazelcast.setReconnectDelay(Duration.ofMillis(500));
        return connector;
    }

    /** The same connector, reading the reliable (ringbuffer-backed) topic of that name. */
    private ConnectorProperties reliable(
            ConnectorProperties connector, HazelcastSourceProperties.ReliableFrom from) {
        HazelcastSourceProperties hazelcast = connector.getSource().getHazelcast();
        hazelcast.setReliable(true);
        hazelcast.setReliableFrom(from);
        return connector;
    }

    /**
     * The same connector with the {@link Trades} factory named, the way an application names
     * its bean, and reading typed values in {@code mode}.
     */
    private ConnectorProperties typed(
            ConnectorProperties connector, HazelcastSourceProperties.TypedValues mode) {
        HazelcastSourceProperties hazelcast = connector.getSource().getHazelcast();
        hazelcast.setSerializationFactories(Map.of(Trades.FACTORY_ID, "tradeFactory"));
        hazelcast.setTypedValues(mode);
        return connector;
    }

    /** The source a {@link #typed} connector gets: the named factory supplied, as the factory bean would. */
    private static HazelcastRecordSource typedSource(ConnectorProperties connector) {
        return new HazelcastRecordSource(connector, Map.of(Trades.FACTORY_ID, new Trades.Factory()));
    }

    /** The ringbuffer behind a reliable topic, where its sequences live. */
    private Ringbuffer<Object> ringbufferOf(String topic) {
        return member.getRingbuffer(RingbufferService.TOPIC_RB_PREFIX + topic);
    }

    private static void awaitRecords(List<InboundRecord> received, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> received.size() >= count);
    }

    private static void awaitConnected(HazelcastRecordSource source, Duration timeout) {
        Awaitility.await().atMost(timeout).until(source::isConnected);
    }

    // ---- plain topic --------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("plain topic messages arrive as keyless upserts carrying publishTime + member")
    void plainTopicMessagesBecomeRecords() {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        long before = System.currentTimeMillis();
        try (HazelcastRecordSource source =
                new HazelcastRecordSource(connector("events-hazelcast", "events"))) {
            source.start(received::add);
            awaitConnected(source, Duration.ofSeconds(10));

            ITopic<Object> topic = member.getTopic("events");
            topic.publish("{\"id\":1}");
            // Not a String: bridged as its toString() rather than dropped, because a bridge
            // that silently swallowed a feed's messages would be worse than one that
            // publishes a rendering the decoder may then reject. Warned about once.
            topic.publish(42);
            awaitRecords(received, 2);

            assertThat(received).extracting(InboundRecord::data)
                    .containsExactly("{\"id\":1}", "42");
            InboundRecord record = received.get(0);
            assertThat(record.data()).isEqualTo("{\"id\":1}");
            // No key, no ack and never a DELETE: a topic message is a payload and nothing
            // more, and there is no position the connector could ask Hazelcast to go back to.
            assertThat(record.key()).isNull();
            assertThat(record.acknowledger()).isSameAs(Acknowledger.NONE);
            assertThat(record.action()).isEqualTo(InboundRecord.Action.UPSERT);
            assertThat(record.attributes()).containsOnlyKeys("publishTime", "member");
            // Text, and numbered by delivery: a plain topic has no sequence of its own.
            assertThat(received).extracting(InboundRecord::type).containsOnly(PayloadType.UNSET);
            assertThat(received).extracting(InboundRecord::seqno).containsExactly(1L, 2L);
            assertThat(Long.parseLong(record.attributes().get("publishTime")))
                    .isGreaterThanOrEqualTo(before);
            assertThat(record.attributes().get("member")).isEqualTo("127.0.0.1:" + port);
        }
    }

    @Test
    @Order(2)
    @DisplayName("a plain topic replays nothing: what was published before the subscription is gone")
    void plainTopicReplaysNothing() {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        ITopic<String> topic = member.getTopic("plain-replay");
        topic.publish("before-anyone-listened");

        try (HazelcastRecordSource source =
                new HazelcastRecordSource(connector("plain-replay-hazelcast", "plain-replay"))) {
            source.start(received::add);
            awaitConnected(source, Duration.ofSeconds(10));
            topic.publish("after");
            awaitRecords(received, 1);

            // Fire-and-forget is the transport's contract, not a bug in the driver: a feed
            // that must survive a restart is configured `reliable: true`.
            Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                    .until(() -> received.size() == 1);
            assertThat(received).extracting(InboundRecord::data).containsExactly("after");
        }
    }

    // ---- reliable topic -------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("reliable + OLDEST replays the ringbuffer, including what predates the listener")
    void reliableOldestReplaysTheRingbuffer() {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        ITopic<String> topic = member.getReliableTopic("replay-oldest");
        topic.publish("early");
        long earlyAt = ringbufferOf("replay-oldest").tailSequence();

        ConnectorProperties connector = reliable(
                connector("replay-oldest-hazelcast", "replay-oldest"),
                HazelcastSourceProperties.ReliableFrom.OLDEST);
        try (HazelcastRecordSource source = new HazelcastRecordSource(connector)) {
            source.start(received::add);
            awaitConnected(source, Duration.ofSeconds(10));
            topic.publish("late");
            long lateAt = ringbufferOf("replay-oldest").tailSequence();
            awaitRecords(received, 2);

            // Initial sequence 0 is the head of the ringbuffer, so the backlog comes first
            // and in order -- this is the only way this transport survives a restart.
            assertThat(received).extracting(InboundRecord::data)
                    .containsExactly("early", "late");
            // And each record's seqno IS its ringbuffer sequence: the position Hazelcast hands
            // the listener just before the message, which is what reliable-from resumes by.
            assertThat(received).extracting(InboundRecord::seqno)
                    .containsExactly(earlyAt, lateAt);
            assertThat(earlyAt).isEqualTo(0L);
            assertThat(lateAt).isEqualTo(1L);
        }
    }

    @Test
    @Order(4)
    @DisplayName("reliable + NEWEST starts at the tail and skips the backlog")
    void reliableNewestSkipsTheBacklog() {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        ITopic<String> topic = member.getReliableTopic("replay-newest");
        topic.publish("early");

        ConnectorProperties connector = reliable(
                connector("replay-newest-hazelcast", "replay-newest"),
                HazelcastSourceProperties.ReliableFrom.NEWEST);
        try (HazelcastRecordSource source = new HazelcastRecordSource(connector)) {
            source.start(received::add);
            awaitConnected(source, Duration.ofSeconds(10));
            topic.publish("late");
            awaitRecords(received, 1);

            Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                    .until(() -> received.size() == 1);
            assertThat(received).extracting(InboundRecord::data).containsExactly("late");
            // Skipping the backlog does not renumber what follows: the seqno is still the
            // ringbuffer's own sequence, not a count of what this listener saw.
            assertThat(received.get(0).seqno())
                    .isEqualTo(ringbufferOf("replay-newest").tailSequence())
                    .isEqualTo(1L);
        }
    }

    // ---- typed values ---------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("typed-values: OBJECT hands an IdentifiedDataSerializable through as itself, under its ids")
    void objectModeHandsTheValueThroughUnderItsType() {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        ITopic<Object> topic = member.getReliableTopic("typed-object");
        Trades.Trade trade = new Trades.Trade("AAPL", 10);

        ConnectorProperties connector = typed(
                reliable(connector("typed-object-hazelcast", "typed-object"),
                        HazelcastSourceProperties.ReliableFrom.OLDEST),
                HazelcastSourceProperties.TypedValues.OBJECT);
        try (HazelcastRecordSource source = typedSource(connector)) {
            source.start(received::add);
            awaitConnected(source, Duration.ofSeconds(10));
            topic.publish(trade);
            topic.publish("{\"id\":1}");
            awaitRecords(received, 2);

            // The object itself -- deserialized by the factory the client was given, equal
            // by value to what was published -- tagged with the pair the codec is registered
            // under, so the pipeline never renders it. The ids also ride along as attributes.
            InboundRecord typed = received.get(0);
            assertThat(typed.data()).isInstanceOf(Trades.Trade.class).isEqualTo(trade);
            assertThat(typed.type()).isEqualTo(PayloadType.of(Trades.FACTORY_ID, Trades.TRADE_CLASS_ID));
            assertThat(typed.attributes())
                    .containsEntry("factoryId", "1000")
                    .containsEntry("classId", "7")
                    .containsKeys("publishTime", "member");
            assertThat(typed.seqno()).isEqualTo(0L);
            assertThat(typed.key()).isNull();

            // Text is still text: the mode only speaks to values that are objects.
            InboundRecord text = received.get(1);
            assertThat(text.data()).isEqualTo("{\"id\":1}");
            assertThat(text.type()).isEqualTo(PayloadType.UNSET);
            assertThat(text.attributes()).doesNotContainKeys("factoryId", "classId");
            assertThat(text.seqno()).isEqualTo(1L);
        }
    }

    @Test
    @Order(6)
    @DisplayName("typed-values: JSON, the default, renders the same value as JSON and keeps the ids as attributes")
    void jsonModeRendersTheValue() {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        ITopic<Object> topic = member.getTopic("typed-json");

        ConnectorProperties connector = typed(
                connector("typed-json-hazelcast", "typed-json"),
                HazelcastSourceProperties.TypedValues.JSON);
        try (HazelcastRecordSource source = typedSource(connector)) {
            source.start(received::add);
            awaitConnected(source, Duration.ofSeconds(10));
            topic.publish(new Trades.Trade("MSFT", 25));
            awaitRecords(received, 1);

            // The fallback a source that cannot see the codec registry needs: JSON of the
            // object is something format: JSON can always read, and the ids still say what
            // it was.
            InboundRecord rendered = received.get(0);
            assertThat(rendered.data()).isEqualTo("{\"symbol\":\"MSFT\",\"quantity\":25}");
            assertThat(rendered.type()).isEqualTo(PayloadType.UNSET);
            assertThat(rendered.attributes())
                    .containsEntry("factoryId", "1000")
                    .containsEntry("classId", "7");
            assertThat(rendered.seqno()).isEqualTo(1L);
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /**
     * The case the gate exists for. A plain-topic message is delivered on one of the client's
     * event threads, and {@code HazelcastInstance.shutdown()} ends those threads by
     * interrupting them; a handler that is inside the aggregator's {@code lockInterruptibly()}
     * at that moment loses its record. So the handler here blocks on an interruptible wait of
     * the same shape, and {@code close()} has to wait for it rather than shut the client down
     * under it.
     */
    @Test
    @Order(7)
    @DisplayName("close() waits for the message inside the handler instead of interrupting it")
    void closeLetsTheDeliveryInFlightFinish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        ITopic<String> topic = member.getTopic("draining");
        HazelcastRecordSource source =
                new HazelcastRecordSource(connector("draining-hazelcast", "draining"));
        source.start(record -> {
            entered.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the handler");
                }
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new IllegalStateException("interrupted inside the handler", e);
            }
            received.add(record);
        });
        awaitConnected(source, Duration.ofSeconds(10));

        topic.publish("{\"id\":1}");
        assertThat(entered.await(10, TimeUnit.SECONDS))
                .as("the message reached the handler")
                .isTrue();

        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            // Close while the handler is blocked, the way Connector.stop() does on shutdown.
            Future<?> closing = closer.submit(source::close);

            // It reports closed as soon as it starts, but it has not RETURNED -- and the
            // client is still up, because the event thread inside the handler is its.
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !source.isConnected());
            assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS))
                    .as("close() is still waiting for the blocked delivery")
                    .isInstanceOf(TimeoutException.class);
            assertThat(HazelcastClient.getAllHazelcastClients()).isNotEmpty();
            assertThat(interrupted).isFalse();
            assertThat(received).isEmpty();

            // Let the handler go: close() finishes, and the record it was carrying arrives.
            released.countDown();
            closing.get(10, TimeUnit.SECONDS);
        } finally {
            closer.shutdownNow();
        }
        assertThat(interrupted).as("the event thread was never interrupted").isFalse();
        assertThat(received).extracting(InboundRecord::data).containsExactly("{\"id\":1}");
        assertThat(HazelcastClient.getAllHazelcastClients()).isEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("close() removes the listener and shuts the client down")
    void closeShutsTheClientDown() {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        HazelcastRecordSource source =
                new HazelcastRecordSource(connector("closing-hazelcast", "closing"));
        source.start(received::add);
        awaitConnected(source, Duration.ofSeconds(10));
        assertThat(HazelcastClient.getAllHazelcastClients()).isNotEmpty();

        source.close();

        assertThat(source.isConnected()).isFalse();
        // A client left running would keep a connection, an event thread pool and a
        // subscription alive after the connector had stopped.
        assertThat(HazelcastClient.getAllHazelcastClients()).isEmpty();
        assertThat(Thread.getAllStackTraces().keySet())
                .as("no source thread is left behind")
                .noneMatch(thread -> thread.getName().equals("closing-hazelcast-hazelcast"));
        // Idempotent, the way ConnectorManager calls it.
        source.close();

        // The member noticed too, rather than holding a half-open client connection.
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> member.getClientService().getConnectedClients().isEmpty());
    }

    @Test
    @Order(9)
    @DisplayName("a cluster that is not up yet is waited for, not a failed start")
    void aMemberThatIsNotUpYetIsWaitedFor() {
        List<InboundRecord> received = new CopyOnWriteArrayList<>();
        member.shutdown();

        ConnectorProperties connector = connector("late-hazelcast", "late");
        try (HazelcastRecordSource source = new HazelcastRecordSource(connector)) {
            source.start(received::add);
            // start() returns whether or not there is a cluster: a broker that is down at
            // startup is a connector that reports false and keeps retrying, not a boot
            // failure that takes the whole application with it.
            assertThat(source.isConnected()).isFalse();

            // Exactly the port the client is configured for: auto-increment would silently
            // land the replacement somewhere the client is not looking.
            member = Hazelcast.newHazelcastInstance(memberConfig(port, false));

            awaitConnected(source, Duration.ofSeconds(20));
            member.<String>getTopic("late").publish("arrived");
            awaitRecords(received, 1);

            assertThat(received).extracting(InboundRecord::data).containsExactly("arrived");
        }
    }
}
