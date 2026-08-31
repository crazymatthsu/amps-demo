package com.demo.amps.fix42.it;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A throwaway AMPS instance in a container, running the {@code fix42-chaining}
 * flow.
 *
 * <p>Testcontainers handles the parts that are fiddly to do by hand: it waits
 * on the server's own {@code initialization completed} log line rather than on
 * an open port (the engine's port forwarder accepts connections before AMPS is
 * listening, so a port check races and the client is dropped mid-logon), it
 * allocates and maps the port, and its reaper removes the container even when
 * the JVM is killed rather than shut down.
 *
 * <p>The image is site-specific -- there is no public AMPS server image, it has
 * to be built from a release tarball -- so this reads {@code AMPS_IMAGE} and
 * reports {@link #unavailableReason()} instead of failing when it is unset.
 * That keeps {@code ./gradlew build} green on a machine that has never seen
 * AMPS, and makes it do real work on one that has.
 *
 * <p>Two deliberate choices about state. The config is <b>copied</b> into the
 * container rather than bind-mounted, so no host path has to be visible to the
 * container VM and nothing on the host needs cleaning up afterwards. The data
 * directory is left in the container's own writable layer -- not a tmpfs and
 * not a host mount -- which gets both properties this suite needs at once: a
 * run cannot inherit the previous run's SOW or chain map, because every run
 * builds a new container from the image; and state DOES survive
 * {@link #restart()}, because a writable layer belongs to the container rather
 * than to one execution of it. A tmpfs would satisfy the first and quietly
 * break the second -- it is discarded whenever the container stops -- and
 * {@link #restart()} exists precisely to test what survives.
 */
public final class AmpsTestServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AmpsTestServer.class);

    private static final String FLOW_DIR = "server/config/flows/fix42-chaining";
    private static final String CONTAINER_CONFIG_DIR = "/amps/config";
    private static final String CONTAINER_DATA_DIR = "/amps/data";
    private static final int AMPS_PORT = 9007;
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    private final GenericContainer<?> container;

    private AmpsTestServer(GenericContainer<?> container) {
        this.container = container;
    }

    /**
     * Why this suite cannot run here, or empty when it can.
     *
     * <p>Returned rather than thrown so the test class can turn it into a skip
     * with a readable reason instead of a failure.
     */
    public static Optional<String> unavailableReason() {
        String image = System.getenv("AMPS_IMAGE");
        if (image == null || image.isBlank()) {
            return Optional.of("AMPS_IMAGE is not set. There is no public AMPS server image; "
                    + "build one from server/Containerfile and export AMPS_IMAGE to run these "
                    + "tests.");
        }
        if (!Files.isRegularFile(Path.of(FLOW_DIR, "amps-config.xml"))) {
            return Optional.of("cannot find " + FLOW_DIR + "/amps-config.xml -- the integration "
                    + "test must run with the repository root as its working directory");
        }
        try {
            org.testcontainers.DockerClientFactory.instance().client();
        } catch (RuntimeException e) {
            return Optional.of("no Docker-API-compatible container runtime is reachable. "
                    + "With podman, expose its socket -- 'podman machine start' plus "
                    + "DOCKER_HOST=unix://$(podman machine inspect --format "
                    + "'{{.ConnectionInfo.PodmanSocket.Path}}') -- or run the tests against "
                    + "docker. Cause: " + e.getMessage());
        }
        return Optional.empty();
    }

    /** Starts an instance and blocks until AMPS reports itself initialised. */
    public static AmpsTestServer start() {
        String binary = System.getenv().getOrDefault("AMPS_BIN", "/opt/amps/bin/ampServer");

        GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse(System.getenv("AMPS_IMAGE")))
                        // The image is built locally from a release tarball and
                        // exists in no registry, so never try to pull it: a
                        // pull attempt would fail slowly and confusingly.
                        .withImagePullPolicy(imageName -> false)
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(Path.of(FLOW_DIR).toAbsolutePath()),
                                CONTAINER_CONFIG_DIR)
                        // No mount for the data directory: AMPS writes the SOW,
                        // the journal and the chain map into the container's own
                        // writable layer. Fresh per run because the container is
                        // fresh, and preserved across restart() because the layer
                        // outlives any single start.
                        // Entrypoint and command are set together here rather
                        // than via withCommand(String), which tokenises on
                        // whitespace and would split the shell line apart.
                        .withCreateContainerCmdModifier(cmd -> cmd
                                .withEntrypoint("/bin/sh", "-c")
                                .withCmd(startupScript(binary))
                                .withWorkingDir(CONTAINER_DATA_DIR))
                        .withExposedPorts(AMPS_PORT)
                        // The real readiness signal. An open port is not one:
                        // the port forwarder accepts before AMPS listens.
                        .waitingFor(Wait.forLogMessage(".*initialization completed.*", 1)
                                .withStartupTimeout(STARTUP_TIMEOUT));

        container.start();
        log.info("AMPS ready on {} (container {})",
                "tcp://" + container.getHost() + ":" + container.getMappedPort(AMPS_PORT),
                container.getContainerId().substring(0, 12));
        return new AmpsTestServer(container);
    }

    /**
     * The container's command: create the directories AMPS expects under its
     * working directory, then hand the process over to the server.
     *
     * <p>{@code exec} matters -- without it the shell stays PID 1 and the
     * container's stop signal never reaches AMPS, so every teardown waits out
     * the kill timeout.
     */
    private static String startupScript(String binary) {
        return "mkdir -p " + CONTAINER_DATA_DIR + "/sow " + CONTAINER_DATA_DIR + "/journal "
                + CONTAINER_DATA_DIR + "/stats"
                + " && exec " + binary + " " + CONTAINER_CONFIG_DIR + "/amps-config.xml";
    }

    /**
     * Restarts the container on the same data directory.
     *
     * <p>For tests that care what survives: the SOW file, the journal, and --
     * the reason this exists -- the chaining key generator's persisted chain
     * map, without which the identity a record was built under would be lost
     * while the record itself survived.
     *
     * <p>Waits for a NEW readiness marker rather than any marker. The previous
     * run's "initialization completed" is still in the log after a restart, so
     * matching on presence alone returns instantly and hands back a server
     * that is still starting.
     */
    public void restart() throws Exception {
        int before = readyMarkerCount();

        // Through the Docker API rather than a CLI subprocess: Testcontainers
        // already holds an authenticated client for whichever engine it found,
        // so this works wherever the rest of the harness does.
        DockerClientFactory.instance().client()
                .restartContainerCmd(container.getContainerId())
                .exec();

        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (readyMarkerCount() > before) {
                log.info("AMPS container {} restarted on port {}",
                        container.getContainerId().substring(0, 12), port());
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("AMPS did not come back within "
                + STARTUP_TIMEOUT.toSeconds() + "s. Container log:\n" + logs());
    }

    private int readyMarkerCount() {
        int count = 0;
        for (String line : logs().split("\n")) {
            if (line.contains("initialization completed")) {
                count++;
            }
        }
        return count;
    }

    /** The client URI for this instance, selecting the {@code fix} message type. */
    public String uri() {
        return "tcp://" + container.getHost() + ":" + port() + "/amps/fix";
    }

    /**
     * The host port AMPS is reachable on, inspected live rather than read from
     * the container info cached at startup.
     *
     * <p>The distinction only shows up after {@link #restart()}: the host side
     * of a dynamically published port is assigned when the container starts, so
     * a restart can hand back a different one, and the cached value would then
     * point at nothing. Live inspection is a round trip to the engine, which is
     * immaterial next to the queries these tests then run over it.
     */
    public int port() {
        Ports.Binding[] bindings = container.getCurrentContainerInfo()
                .getNetworkSettings()
                .getPorts()
                .getBindings()
                .get(ExposedPort.tcp(AMPS_PORT));
        if (bindings == null || bindings.length == 0) {
            throw new IllegalStateException(
                    "container " + container.getContainerId().substring(0, 12)
                            + " publishes no host port for " + AMPS_PORT);
        }
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    /** The server log, for diagnosing a failure. */
    public String logs() {
        return container.getLogs();
    }

    @Override
    public void close() {
        container.stop();
    }
}
