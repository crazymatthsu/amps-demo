package com.demo.amps.testharness;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.nio.file.Path;
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
 * {@link AmpsTestServer} on Testcontainers, talking to the engine's Docker API.
 *
 * <p>The harness CI wants. Testcontainers handles the parts that are fiddly to
 * do by hand: it waits on {@link AmpsTestServer#READY_MARKER} rather than an
 * open port, it allocates and maps the port, and its reaper removes the
 * container even when the JVM is killed rather than shut down.
 *
 * <p>Two deliberate choices about state. The config is <b>copied</b> into the
 * container rather than bind-mounted, so no host path has to be visible to the
 * container VM and nothing on the host needs cleaning up afterwards. The data
 * directory is left in the container's own writable layer -- not a tmpfs and
 * not a host mount -- which gets both properties this suite needs at once: a
 * run cannot inherit the previous run's SOW or chain map, because every run
 * builds a new container; and state DOES survive {@link #restart()}, because a
 * writable layer belongs to the container rather than to one execution of it.
 * A tmpfs would satisfy the first and quietly break the second -- it is
 * discarded whenever the container stops -- and {@link #restart()} exists
 * precisely to test what survives.
 */
final class TestcontainersAmpsServer implements AmpsTestServer {

    private static final Logger log = LoggerFactory.getLogger(TestcontainersAmpsServer.class);

    private final AmpsFlow flow;
    private final GenericContainer<?> container;

    private TestcontainersAmpsServer(AmpsFlow flow, GenericContainer<?> container) {
        this.flow = flow;
        this.container = container;
    }

    /**
     * The one precondition beyond the shared ones: a reachable Docker API.
     *
     * <p>Checked rather than left to fail, because podman on macOS exposes the
     * socket only when asked -- so the common way to get here is choosing this
     * harness on a machine set up for the other one, and the readable reason is
     * worth more than the stack trace.
     */
    static Optional<String> unavailableReason() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            return Optional.of("AMPS_TEST_HARNESS=testcontainers needs a Docker-API-compatible "
                    + "socket and none was reachable. Either export DOCKER_HOST (with podman: "
                    + "DOCKER_HOST=\"unix://$(podman machine inspect --format "
                    + "'{{.ConnectionInfo.PodmanSocket.Path}}')\") or use the default "
                    + "AMPS_TEST_HARNESS=cli, which drives the engine binary directly.");
        }
        return Optional.empty();
    }

    static AmpsTestServer start(AmpsFlow flow) {
        GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse(System.getenv("AMPS_IMAGE")))
                        // The image is built locally from a release tarball and
                        // exists in no registry, so never try to pull it: a
                        // pull attempt would fail slowly and confusingly.
                        .withImagePullPolicy(imageName -> false)
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(
                                        Path.of(flow.configDir()).toAbsolutePath()),
                                CONTAINER_CONFIG_DIR)
                        // Entrypoint and command are set together here rather
                        // than via withCommand(String), which tokenises on
                        // whitespace and would split the shell line apart.
                        .withCreateContainerCmdModifier(cmd -> cmd
                                .withEntrypoint("/bin/sh", "-c")
                                .withCmd(AmpsTestServer.startupScript(AmpsTestServer.ampsBinary()))
                                .withWorkingDir(CONTAINER_DATA_DIR))
                        .withExposedPorts(AMPS_PORT)
                        .waitingFor(Wait.forLogMessage(".*" + READY_MARKER + ".*", 1)
                                .withStartupTimeout(STARTUP_TIMEOUT));

        container.start();
        TestcontainersAmpsServer server = new TestcontainersAmpsServer(flow, container);
        log.info("AMPS ready on tcp://{}:{} (container {}, testcontainers)",
                container.getHost(), server.port(), shortId(container));
        return server;
    }

    @Override
    public void restart() throws Exception {
        int before = AmpsTestServer.readyMarkerCount(logs());

        // Through the Docker API rather than a CLI subprocess: Testcontainers
        // already holds an authenticated client for whichever engine it found,
        // so this works wherever the rest of this harness does.
        DockerClientFactory.instance().client()
                .restartContainerCmd(container.getContainerId())
                .exec();

        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (AmpsTestServer.readyMarkerCount(logs()) > before) {
                log.info("AMPS container {} restarted on port {}", shortId(container), port());
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("AMPS did not come back within "
                + STARTUP_TIMEOUT.toSeconds() + "s. Container log:\n" + logs());
    }

    @Override
    public String uri() {
        return "tcp://" + container.getHost() + ":" + port() + "/amps/" + flow.messageType();
    }

    /**
     * Inspected live rather than read from the container info cached at
     * startup.
     *
     * <p>The distinction only shows up after {@link #restart()}: the host side
     * of a dynamically published port is assigned when the container starts, so
     * a restart can hand back a different one and the cached value would then
     * point at nothing. The round trip is immaterial next to the queries these
     * tests run over it.
     */
    @Override
    public int port() {
        Ports.Binding[] bindings = container.getCurrentContainerInfo()
                .getNetworkSettings()
                .getPorts()
                .getBindings()
                .get(ExposedPort.tcp(AMPS_PORT));
        if (bindings == null || bindings.length == 0) {
            throw new IllegalStateException("container " + shortId(container)
                    + " publishes no host port for " + AMPS_PORT);
        }
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    @Override
    public String logs() {
        return container.getLogs();
    }

    @Override
    public void close() {
        container.stop();
    }

    private static String shortId(GenericContainer<?> container) {
        return container.getContainerId().substring(0, 12);
    }
}
