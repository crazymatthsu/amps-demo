package com.demo.amps.connectors.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.demo.amps.connectors.source.SourceRecord;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.cluster.Address;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * does replay it, and whether the client really does wait for a cluster that is not up yet.
 * All three are Hazelcast's behaviour rather than this driver's, so the driver is tested
 * against Hazelcast.
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

    private static void awaitRecords(List<SourceRecord> received, int count) {
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
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
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

            assertThat(received).extracting(SourceRecord::data)
                    .containsExactly("{\"id\":1}", "42");
            SourceRecord record = received.get(0);
            assertThat(record.data()).isEqualTo("{\"id\":1}");
            // No key, no ack and never a DELETE: a topic message is a payload and nothing
            // more, and there is no position the connector could ask Hazelcast to go back to.
            assertThat(record.key()).isNull();
            assertThat(record.ack()).isNull();
            assertThat(record.action()).isEqualTo(SourceRecord.Action.UPSERT);
            assertThat(record.attributes()).containsOnlyKeys("publishTime", "member");
            assertThat(Long.parseLong(record.attributes().get("publishTime")))
                    .isGreaterThanOrEqualTo(before);
            assertThat(record.attributes().get("member")).isEqualTo("127.0.0.1:" + port);
        }
    }

    @Test
    @Order(2)
    @DisplayName("a plain topic replays nothing: what was published before the subscription is gone")
    void plainTopicReplaysNothing() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
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
            assertThat(received).extracting(SourceRecord::data).containsExactly("after");
        }
    }

    // ---- reliable topic -------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("reliable + OLDEST replays the ringbuffer, including what predates the listener")
    void reliableOldestReplaysTheRingbuffer() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        ITopic<String> topic = member.getReliableTopic("replay-oldest");
        topic.publish("early");

        ConnectorProperties connector = reliable(
                connector("replay-oldest-hazelcast", "replay-oldest"),
                HazelcastSourceProperties.ReliableFrom.OLDEST);
        try (HazelcastRecordSource source = new HazelcastRecordSource(connector)) {
            source.start(received::add);
            awaitConnected(source, Duration.ofSeconds(10));
            topic.publish("late");
            awaitRecords(received, 2);

            // Initial sequence 0 is the head of the ringbuffer, so the backlog comes first
            // and in order -- this is the only way this transport survives a restart.
            assertThat(received).extracting(SourceRecord::data)
                    .containsExactly("early", "late");
        }
    }

    @Test
    @Order(4)
    @DisplayName("reliable + NEWEST starts at the tail and skips the backlog")
    void reliableNewestSkipsTheBacklog() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
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
            assertThat(received).extracting(SourceRecord::data).containsExactly("late");
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("close() removes the listener and shuts the client down")
    void closeShutsTheClientDown() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
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
    @Order(6)
    @DisplayName("a cluster that is not up yet is waited for, not a failed start")
    void aMemberThatIsNotUpYetIsWaitedFor() {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
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

            assertThat(received).extracting(SourceRecord::data).containsExactly("arrived");
        }
    }
}
