package com.demo.amps.connectors.it.smoke;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientConnectionStrategyConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

/**
 * The two halves of {@code amps-connectors/scripts/hazelcast-smoke.sh} that need a JVM:
 * writing to the Hazelcast container, and reading the AMPS container back.
 *
 * <p>The script owns the choreography -- three containers on one podman network, the real
 * {@code localhost/amps-connector-app:local} image between them -- and this owns the two
 * ends nothing else on the host can speak. It is deliberately not a JUnit test: what it
 * proves is that the <em>deployed article</em> works (the image, the mounted configuration,
 * the container network, the healthcheck), which is a different claim from
 * {@code HazelcastToAmpsIT}'s, where the application runs in the test's own JVM.
 *
 * <pre>{@code
 * HazelcastSmoke feed   --hazelcast localhost:25701 --amps localhost:29007
 * HazelcastSmoke verify --hazelcast localhost:25701 --amps localhost:29007
 * }</pre>
 *
 * <h2>Why the Hazelcast client is unisocket</h2>
 *
 * <p>A smart client asks the cluster for its members and then connects to each one directly,
 * so it can send every operation to the partition that owns the key. What the member
 * <em>advertises</em> is its own address on the podman network -- {@code 10.89.x.y:5701} --
 * which is reachable from the connector container and from nothing on the host. A smart
 * client on the host therefore connects to the published port, learns an address it cannot
 * route to, and hangs. Unisocket routing keeps every operation on the connection it already
 * has, which is the published port, and the member proxies it onward. The connector container
 * is on the network and needs none of this: it dials {@code hazelcast:5701} and routes fine.
 */
public final class HazelcastSmoke {

    /** The cluster the compose'd member runs under ({@code HZ_CLUSTERNAME=dev}). */
    private static final String CLUSTER = "dev";

    /** The map the {@code positions-hazelcast} instance configuration bridges. */
    private static final String MAP = "positions";

    /** The topic that instance publishes into -- declared with NO {@code <Key>}. */
    private static final String TOPIC = "sow/connectors/positions";

    /** Long enough for a container's batch flush and a podman-machine hiccup. */
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    private HazelcastSmoke() {
    }

    /**
     * Runs one subcommand.
     *
     * @param args {@code feed|verify --hazelcast host:port --amps host:port}
     */
    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                throw new IllegalArgumentException("usage: HazelcastSmoke <feed|verify> "
                        + "--hazelcast <host:port> --amps <host:port>");
            }
            Map<String, String> options = options(args);
            String hazelcast = required(options, "hazelcast");
            String amps = required(options, "amps");
            switch (args[0]) {
                case "feed" -> feed(hazelcast);
                case "verify" -> verify(amps);
                default -> throw new IllegalArgumentException(
                        "unknown subcommand '" + args[0] + "' (feed | verify)");
            }
        } catch (Exception e) {
            System.err.println("smoke: " + e.getMessage());
            System.exit(1);
        }
    }

    // ---- feed --------------------------------------------------------------------------

    /**
     * Write the cache the way an application would, and say what was written.
     *
     * <p>Three entries, one of them then updated and one of them removed, which is the whole
     * of what a map connector has to get right: an upsert under a key the publisher supplies,
     * a second upsert that replaces rather than adds, and a removal that has no payload left
     * to be addressed by anything except that same key.
     *
     * @param hazelcast the published {@code host:port} of the member container
     */
    private static void feed(String hazelcast) {
        HazelcastInstance client = HazelcastClient.newHazelcastClient(clientConfig(hazelcast));
        try {
            IMap<String, HazelcastJsonValue> positions = client.getMap(MAP);
            positions.put("ACC-1|AAPL", position("ACC-1", "AAPL", 100, "101.50"));
            positions.put("ACC-2|MSFT", position("ACC-2", "MSFT", 250, "330.25"));
            positions.put("ACC-3|TSLA", position("ACC-3", "TSLA", 40, "242.10"));
            System.out.println("put     ACC-1|AAPL qty=100, ACC-2|MSFT qty=250, ACC-3|TSLA qty=40");

            positions.put("ACC-1|AAPL", position("ACC-1", "AAPL", 175, "102.00"));
            System.out.println("update  ACC-1|AAPL qty=100 -> 175");

            positions.remove("ACC-2|MSFT");
            System.out.println("remove  ACC-2|MSFT");

            System.out.println("map '" + MAP + "' now holds " + positions.size()
                    + " entries: " + new TreeMap<>(positions.getAll(positions.keySet())).keySet());
        } finally {
            client.shutdown();
        }
    }

    /**
     * A client that stays on the one connection it opened.
     *
     * <p>{@code setSmartRouting(false)} is deprecated in 5.5 in favour of a
     * {@code ClusterRoutingConfig}, whose {@code RoutingMode} enum lives in an
     * {@code ...impl.connection.tcp} package -- so the deprecated setter is still the only
     * one that can be named from outside Hazelcast's own internals.
     *
     * @param hazelcast the published {@code host:port} of the member container
     * @return the client configuration
     */
    @SuppressWarnings("deprecation")
    private static ClientConfig clientConfig(String hazelcast) {
        ClientConfig config = new ClientConfig();
        config.setClusterName(CLUSTER);
        config.setInstanceName("amps-connectors-smoke");
        config.setProperty("hazelcast.logging.type", "slf4j");
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig()
                .setAddresses(List.of(hazelcast))
                .setConnectionTimeout(5_000)
                // See the class comment: the member advertises a container address the host
                // cannot reach, so the client must not go looking for it.
                .setSmartRouting(false);
        ClientConnectionStrategyConfig strategy = config.getConnectionStrategyConfig();
        // A one-shot tool: either the cluster is there now or this run has failed.
        strategy.setAsyncStart(false);
        strategy.getConnectionRetryConfig().setClusterConnectTimeoutMillis(30_000);
        return config;
    }

    private static HazelcastJsonValue position(String account, String symbol, int qty,
                                               String avgCost) {
        return new HazelcastJsonValue("{\"account\":\"" + account + "\",\"symbol\":\"" + symbol
                + "\",\"qty\":" + qty + ",\"avgCost\":" + avgCost + "}");
    }

    // ---- verify ------------------------------------------------------------------------

    /**
     * Read the SOW back with a plain AMPS client until it says what {@link #feed} implies.
     *
     * <p>Two records, keyed by the map's own keys, one of them carrying the updated quantity
     * and the removed one gone. Polled rather than asserted once: the connector batches, and
     * {@code Client.flush()} is this client's flush, not the connector's.
     *
     * @param amps the published {@code host:port} of the AMPS container
     * @throws Exception if the SOW never agreed, or the client could not connect
     */
    private static void verify(String amps) throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ACC-1|AAPL", "{\"account\":\"ACC-1\",\"symbol\":\"AAPL\","
                + "\"qty\":175,\"avgCost\":102.00}");
        expected.put("ACC-3|TSLA", "{\"account\":\"ACC-3\",\"symbol\":\"TSLA\","
                + "\"qty\":40,\"avgCost\":242.10}");

        Client client = new Client("amps-connectors-smoke-verify");
        try {
            client.connect("tcp://" + amps + "/amps/json");
            client.logon(10_000);
            Map<String, String> actual = new TreeMap<>();
            try {
                Awaitility.await("the SOW to hold exactly " + expected.keySet())
                        .atMost(PATIENCE).pollInterval(Duration.ofSeconds(1))
                        .until(() -> {
                            actual.clear();
                            actual.putAll(sow(client));
                            return actual.equals(new TreeMap<>(expected));
                        });
            } catch (ConditionTimeoutException timeout) {
                throw new IllegalStateException(diff(expected, actual));
            }
            System.out.println("verified " + TOPIC + ": " + actual.size()
                    + " records, keyed by the map key");
            actual.forEach((key, data) -> System.out.println("  " + key + "  " + data));
            System.out.println("  ACC-2|MSFT is absent: the map removal became a sow_delete");
        } finally {
            client.close();
        }
    }

    /** The whole topic as {@code SowKey -> payload}. */
    private static Map<String, String> sow(Client client) throws Exception {
        Map<String, String> records = new TreeMap<>();
        Command query = new Command("sow").setTopic(TOPIC).setFilter("1=1").setTimeout(10_000);
        try (MessageStream stream = client.execute(query)) {
            stream.timeout(10_000);
            for (Message message : stream) {
                if (message == null || message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    records.put(message.getSowKey(), message.getData());
                }
            }
        }
        return records;
    }

    /** What the SOW should hold against what it does, line by line. */
    private static String diff(Map<String, String> expected, Map<String, String> actual) {
        StringBuilder report = new StringBuilder(TOPIC + " never matched.\n  expected "
                + expected.size() + " record(s):\n");
        expected.forEach((key, data) -> report.append("    ").append(key).append("  ")
                .append(data).append('\n'));
        report.append("  found ").append(actual.size()).append(" record(s):\n");
        if (actual.isEmpty()) {
            report.append("    (none)\n");
        }
        actual.forEach((key, data) -> report.append("    ").append(key).append("  ")
                .append(data).append(expected.containsKey(key)
                        ? (data.equals(expected.get(key)) ? "" : "   <- payload differs")
                        : "   <- unexpected key").append('\n'));
        expected.keySet().stream().filter(key -> !actual.containsKey(key))
                .forEach(key -> report.append("    ").append(key).append("   <- MISSING\n"));
        return report.toString();
    }

    // ---- arguments ---------------------------------------------------------------------

    /** {@code --name value} pairs after the subcommand. */
    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument '" + args[i] + "'");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(args[i] + " needs a value");
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " <host:port> is required");
        }
        return value;
    }
}
