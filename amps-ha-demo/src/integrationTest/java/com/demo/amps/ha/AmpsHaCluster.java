package com.demo.amps.ha;

import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The replicated pair on Testcontainers: two AMPS containers on one network,
 * each running its file from {@code amps-ha-demo/config/}, reachable from the
 * test on fixed host ports -- and killable.
 *
 * <p>Why not {@code amps-test-harness}: that starts ONE instance of a flow
 * under {@code server/config/flows}. This suite needs two instances that can
 * find each other by the hostnames the configs replicate to
 * ({@code amps-primary}, {@code amps-secondary}), config from this module,
 * and the ability to SIGKILL either one and start it again on the same data.
 *
 * <p>Fixed host ports, chosen free at start, rather than Testcontainers' usual
 * random mapping: a container that is stopped and started again would
 * otherwise come back on a different host port, and the URIs the clients'
 * server chooser holds would point at nothing. Fixed ports keep a revived
 * instance where the clients expect it, which is the whole failover story.
 *
 * <p>State lives in each container's writable layer (not a tmpfs, not a host
 * mount), so a run never inherits the previous run's journal while a
 * {@link #revive} does find the SOW and journal the killed instance left.
 */
final class AmpsHaCluster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AmpsHaCluster.class);

    static final int AMPS_PORT = 9007;
    static final String READY_MARKER = "initialization completed";
    static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    /**
     * What an instance logs when its peer has connected to its incoming
     * replication transport (wording as of AMPS 5.3.5.135). Both instances
     * having logged it means the link is up in both directions.
     */
    static final Pattern PEER_LOGGED_ON = Pattern.compile("AMPS replication client session logon");

    /**
     * What an instance logs when the scheduled upgrade action restores a
     * destination it had downgraded to async back to sync acknowledgment.
     */
    static final Pattern LINK_UPGRADED = Pattern.compile("upgraded from async to sync");

    /** What an instance logs when the scheduled downgrade action gives up waiting on a destination. */
    static final Pattern LINK_DOWNGRADED = Pattern.compile("downgraded from sync to async");
    private static final String CONTAINER_CONFIG_DIR = "/amps/config";
    private static final String CONTAINER_DATA_DIR = "/amps/data";

    /** The two instances, named as the configs and the compose file name them. */
    enum Instance {
        PRIMARY("amps-primary", "primary"),
        SECONDARY("amps-secondary", "secondary");

        final String hostname;
        final String configFolder;

        Instance(String hostname, String configFolder) {
            this.hostname = hostname;
            this.configFolder = configFolder;
        }
    }

    /** A {@link GenericContainer} that binds one fixed host port. */
    private static final class AmpsContainer extends GenericContainer<AmpsContainer> {
        AmpsContainer(String image, int hostPort) {
            super(DockerImageName.parse(image));
            addFixedExposedPort(hostPort, AMPS_PORT);
        }
    }

    private final Network network;
    private final Map<Instance, AmpsContainer> containers;
    private final Map<Instance, Integer> hostPorts;

    private AmpsHaCluster(Network network, Map<Instance, AmpsContainer> containers, Map<Instance, Integer> hostPorts) {
        this.network = network;
        this.containers = containers;
        this.hostPorts = hostPorts;
    }

    /**
     * Why this suite cannot run here, or empty when it can. Returned rather
     * than thrown so the test turns it into a skip with a readable reason.
     */
    static Optional<String> unavailableReason() {
        String image = System.getenv("AMPS_IMAGE");
        if (image == null || image.isBlank()) {
            return Optional.of("AMPS_IMAGE is not set. There is no public AMPS server image; build one from "
                    + "server/Containerfile and export AMPS_IMAGE to run this suite.");
        }
        Path configDir = configDir();
        for (Instance instance : Instance.values()) {
            if (!Files.isRegularFile(configDir.resolve(instance.configFolder).resolve("amps-config.xml"))) {
                return Optional.of("cannot find " + configDir.resolve(instance.configFolder)
                        + "/amps-config.xml (the ha.configDir system property should point at amps-ha-demo/config)");
            }
        }
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            return Optional.of("no Docker-API-compatible socket is reachable, and this suite runs on Testcontainers. "
                    + "With podman: export DOCKER_HOST=\"unix://$(podman machine inspect --format "
                    + "'{{.ConnectionInfo.PodmanSocket.Path}}')\" and TESTCONTAINERS_RYUK_DISABLED=true.");
        }
        return Optional.empty();
    }

    /** Starts both instances and blocks until each has finished initialising. */
    static AmpsHaCluster start() throws Exception {
        String image = System.getenv("AMPS_IMAGE");
        Path configDir = configDir();
        Network network = Network.newNetwork();
        Map<Instance, AmpsContainer> containers = new EnumMap<>(Instance.class);
        Map<Instance, Integer> hostPorts = new EnumMap<>(Instance.class);
        try {
            for (Instance instance : Instance.values()) {
                int hostPort = freePort();
                hostPorts.put(instance, hostPort);
                AmpsContainer container = new AmpsContainer(image, hostPort)
                        // Built locally from a release tarball, in no registry: never pull.
                        .withImagePullPolicy(name -> false)
                        .withNetwork(network)
                        .withNetworkAliases(instance.hostname)
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(configDir.resolve(instance.configFolder).toAbsolutePath()),
                                CONTAINER_CONFIG_DIR)
                        // Entrypoint and command set together: withCommand(String)
                        // tokenises on whitespace and would split the shell line.
                        .withCreateContainerCmdModifier(cmd -> cmd
                                .withHostName(instance.hostname)
                                .withEntrypoint("/bin/sh", "-c")
                                .withCmd(startupScript())
                                .withWorkingDir(CONTAINER_DATA_DIR))
                        .waitingFor(Wait.forLogMessage(".*" + READY_MARKER + ".*", 1)
                                .withStartupTimeout(STARTUP_TIMEOUT));
                containers.put(instance, container);
            }
            // Together, not in turn: each one's destination is the other, and
            // emulated AMPS takes a while to come up.
            Startables.deepStart(containers.values().stream()).join();
        } catch (Exception | Error e) {
            containers.values().forEach(c -> {
                try {
                    c.stop();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            });
            network.close();
            throw e;
        }
        AmpsHaCluster cluster = new AmpsHaCluster(network, containers, hostPorts);
        for (Instance instance : Instance.values()) {
            log.info("{} ready at {} (container {})", instance, cluster.uri(instance), cluster.shortId(instance));
        }
        return cluster;
    }

    private static Path configDir() {
        return Path.of(System.getProperty("ha.configDir", "config"));
    }

    /**
     * Create the directories AMPS resolves its relative paths against, then
     * hand the process over. {@code exec} matters: without it the shell stays
     * PID 1 and the container's signals never reach AMPS.
     */
    private static String startupScript() {
        String binary = System.getenv().getOrDefault("AMPS_BIN", "/opt/amps/bin/ampServer");
        return "mkdir -p " + CONTAINER_DATA_DIR + "/sow " + CONTAINER_DATA_DIR + "/journal " + CONTAINER_DATA_DIR
                + "/stats && exec " + binary + " " + CONTAINER_CONFIG_DIR + "/amps-config.xml";
    }

    /** The client URI for {@code instance}, selecting the {@code json} message type. */
    String uri(Instance instance) {
        return "tcp://" + containers.get(instance).getHost() + ":" + hostPorts.get(instance) + "/amps/json";
    }

    /** {@code host:port} of {@code instance}, the part of a URI that identifies it. */
    String hostAndPort(Instance instance) {
        return containers.get(instance).getHost() + ":" + hostPorts.get(instance);
    }

    /** Both URIs, primary first: the order the clients' server chooser tries them. */
    List<String> uris() {
        return List.of(uri(Instance.PRIMARY), uri(Instance.SECONDARY));
    }

    /**
     * SIGKILL: a crash. No flush, no clean journal close, no goodbye to the
     * peer -- the case the clients' stores exist for.
     */
    void kill(Instance instance) {
        log.info("SIGKILL {} (container {})", instance, shortId(instance));
        docker().killContainerCmd(containers.get(instance).getContainerId()).withSignal("SIGKILL").exec();
    }

    /**
     * Starts a killed instance again on the data it left behind and waits for
     * a NEW ready marker: the old one is still in the log, so presence would
     * return at once with a server still recovering its journal.
     */
    void revive(Instance instance) throws Exception {
        int before = readyMarkerCount(logs(instance));
        log.info("starting {} again (container {})", instance, shortId(instance));
        docker().startContainerCmd(containers.get(instance).getContainerId()).exec();
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (readyMarkerCount(logs(instance)) > before) {
                log.info("{} is back at {}", instance, uri(instance));
                return;
            }
            if (!isRunning(instance)) {
                throw new IllegalStateException(instance + " exited while restarting. Log tail:\n" + tail(logs(instance), 40));
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(instance + " did not come back within " + STARTUP_TIMEOUT.toSeconds()
                + "s. Log tail:\n" + tail(logs(instance), 40));
    }

    boolean isRunning(Instance instance) {
        Boolean running = docker().inspectContainerCmd(containers.get(instance).getContainerId()).exec()
                .getState().getRunning();
        return Boolean.TRUE.equals(running);
    }

    String logs(Instance instance) {
        return containers.get(instance).getLogs();
    }

    /** Whether {@code pattern} appears in the instance's log within {@code timeout}. */
    boolean awaitLog(Instance instance, Pattern pattern, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            if (pattern.matcher(logs(instance)).find()) {
                return true;
            }
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            Thread.sleep(500);
        }
    }

    /**
     * The instance's log lines about replication that say something -- link
     * logons, resyncs, replays, downgrades and upgrades -- without the thread
     * monitor's register/de-register chatter or the scheduled action's
     * every-two-seconds "module invoked" lines.
     */
    String replicationLines(Instance instance, int max) {
        List<String> lines = logs(instance).lines()
                .filter(l -> l.toLowerCase().contains("replicat")
                        && !l.contains("thread monitor")
                        && !l.contains("action module invoked"))
                .toList();
        List<String> last = lines.subList(Math.max(0, lines.size() - max), lines.size());
        return String.join("\n", last);
    }

    static String tail(String text, int lines) {
        List<String> all = text.lines().toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    static int readyMarkerCount(String logs) {
        int count = 0;
        for (String line : logs.split("\n")) {
            if (line.contains(READY_MARKER)) {
                count++;
            }
        }
        return count;
    }

    private String shortId(Instance instance) {
        String id = containers.get(instance).getContainerId();
        return id == null ? "?" : id.substring(0, Math.min(12, id.length()));
    }

    private static DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() {
        for (Map.Entry<Instance, AmpsContainer> entry : containers.entrySet()) {
            try {
                entry.getValue().stop();
            } catch (RuntimeException e) {
                log.warn("could not stop {}: {}", entry.getKey(), e.toString());
            }
        }
        network.close();
    }
}
