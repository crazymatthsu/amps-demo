package com.demo.amps.fix42.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Two deliberate choices about state, both so a run never inherits anything
 * from the last one -- which matters more than usual here, since the chaining
 * module's whole job is remembering identifiers across restarts:
 * the config is <b>copied</b> into the image rather than bind-mounted (no host
 * path has to be visible to the container VM), and the data directory is a
 * <b>tmpfs</b>, so there is nothing on the host to clean up afterwards.
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
                        // AMPS resolves every relative path in its config against
                        // the working directory, and expects these to exist. A
                        // tmpfs keeps the SOW and journal entirely in the
                        // container, so no run can inherit the last one's state.
                        .withTmpFs(java.util.Map.of(CONTAINER_DATA_DIR, "rw"))
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

    /** The client URI for this instance, selecting the {@code fix} message type. */
    public String uri() {
        return "tcp://" + container.getHost() + ":" + container.getMappedPort(AMPS_PORT)
                + "/amps/fix";
    }

    public int port() {
        return container.getMappedPort(AMPS_PORT);
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
