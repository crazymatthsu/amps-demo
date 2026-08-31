package com.demo.amps.fix42.it;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AmpsTestServer} driving {@code podman} (or {@code docker}) as a
 * subprocess.
 *
 * <p>The harness a developer machine wants. This repository already drives
 * podman from a shell script ({@code server/scripts/amps.sh}) and the image is
 * site-specific, so invoking the engine directly needs nothing beyond the
 * binary on {@code PATH} -- no Docker-compatible socket, which podman on macOS
 * exposes only if you go and enable it. That is why this, not Testcontainers,
 * is what {@code AMPS_TEST_HARNESS} defaults to.
 *
 * <p>Each instance gets its own port and its own empty data directory under
 * {@code build/}, so a run never inherits SOW records or a chain map from the
 * last one, {@code gradle clean} disposes of them, and the path stays inside
 * the directory a podman machine shares on macOS.
 *
 * <p>{@code CONTAINER_ENGINE} picks the binary (default {@code podman}) and
 * {@code AMPS_PLATFORM} the architecture (default {@code linux/amd64}, since
 * the AMPS image is amd64 and an Apple Silicon machine must emulate it).
 */
final class ContainerCliAmpsServer implements AmpsTestServer {

    private static final Logger log = LoggerFactory.getLogger(ContainerCliAmpsServer.class);

    private final String engine;
    private final String image;
    private final String containerName;
    private final int port;
    private final Path dataDir;
    private boolean started;

    private ContainerCliAmpsServer(String engine, String image, String containerName, int port,
                                   Path dataDir) {
        this.engine = engine;
        this.image = image;
        this.containerName = containerName;
        this.port = port;
        this.dataDir = dataDir;
    }

    /** The one precondition beyond the shared ones: the engine binary. */
    static Optional<String> unavailableReason() {
        String engine = engine();
        if (!commandExists(engine)) {
            return Optional.of("container engine '" + engine + "' is not on PATH. Set "
                    + "CONTAINER_ENGINE to the binary you use, or AMPS_TEST_HARNESS=testcontainers "
                    + "to go through the Docker API instead.");
        }
        return Optional.empty();
    }

    static AmpsTestServer start() throws Exception {
        String image = System.getenv("AMPS_IMAGE");
        int port = freePort();
        String name = "amps-fix42-it-" + port;

        // Under build/, so it is inside the repository (and therefore inside the
        // path a podman machine shares on macOS) and `gradle clean` disposes of it.
        Path dataDir = Path.of("build", "fix42-it", name).toAbsolutePath();
        deleteRecursively(dataDir);
        // The same three directories AmpsTestServer.startupScript creates inside
        // the container for the other harness -- made on the host here, because
        // this one bind-mounts the data directory rather than keeping it in the
        // container's own layer.
        for (String directory : List.of("sow", "journal", "stats")) {
            Files.createDirectories(dataDir.resolve(directory));
        }

        ContainerCliAmpsServer server =
                new ContainerCliAmpsServer(engine(), image, name, port, dataDir);
        server.run();
        return server;
    }

    private void run() throws Exception {
        Path flowDir = Path.of(FLOW_DIR).toAbsolutePath();
        String mountSuffix = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("linux") ? ":z" : "";

        List<String> command = new ArrayList<>(List.of(engine, "run", "-d",
                "--name", containerName,
                "-p", port + ":" + AMPS_PORT,
                "-v", flowDir + ":" + CONTAINER_CONFIG_DIR + mountSuffix,
                "-v", dataDir + ":" + CONTAINER_DATA_DIR + mountSuffix,
                "-w", CONTAINER_DATA_DIR,
                "--entrypoint", AmpsTestServer.ampsBinary()));
        String platform = System.getenv().getOrDefault("AMPS_PLATFORM", "linux/amd64");
        if (!platform.isBlank()) {
            command.addAll(2, List.of("--platform", platform));
        }
        command.add(image);
        command.add(CONTAINER_CONFIG_DIR + "/amps-config.xml");

        log.info("starting AMPS container {} on port {} (cli: {})", containerName, port, engine);
        runEngineCommand(command, "could not start AMPS container");
        started = true;
        awaitReady();
        log.info("AMPS ready on tcp://127.0.0.1:{} (container {}, cli)", port, containerName);
    }

    @Override
    public void restart() throws Exception {
        int before = AmpsTestServer.readyMarkerCount(logs());

        runEngineCommand(List.of(engine, "restart", containerName),
                "could not restart AMPS container");

        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (AmpsTestServer.readyMarkerCount(logs()) > before) {
                log.info("AMPS container {} restarted on port {}", containerName, port);
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("AMPS did not come back within "
                + STARTUP_TIMEOUT.toSeconds() + "s. Container log:\n" + logs());
    }

    @Override
    public String uri() {
        return "tcp://127.0.0.1:" + port + "/amps/fix";
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public String logs() {
        try {
            Process process = new ProcessBuilder(engine, "logs", containerName)
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);
            return output;
        } catch (Exception e) {
            return "could not read container logs: " + e;
        }
    }

    @Override
    public void close() {
        if (!started) {
            return;
        }
        try {
            new ProcessBuilder(engine, "rm", "-f", containerName)
                    .redirectErrorStream(true).start().waitFor(2, TimeUnit.MINUTES);
            log.info("removed AMPS container {}", containerName);
        } catch (Exception e) {
            log.warn("could not remove container {}: {}", containerName, e.toString());
        }
        deleteRecursively(dataDir);
    }

    /**
     * Waits for AMPS to be genuinely ready, which is not the same as its port
     * being open -- see {@link AmpsTestServer#READY_MARKER}. Also fails fast if
     * the container exits, so a crash-loop does not wait out the timeout.
     */
    private void awaitReady() throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        boolean portOpen = false;

        while (Instant.now().isBefore(deadline)) {
            if (!portOpen) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                    portOpen = true;
                } catch (IOException retry) {
                    // Not listening yet.
                }
            }
            if (portOpen && logs().contains(READY_MARKER)) {
                return;
            }
            if (!isRunning()) {
                throw new IllegalStateException(
                        "the AMPS container exited during startup. Log:\n" + logs());
            }
            Thread.sleep(500);
        }

        throw new IllegalStateException("AMPS was not ready on port " + port + " within "
                + STARTUP_TIMEOUT.toSeconds() + "s. Container log:\n" + logs());
    }

    /** Whether the container is still up, so a crash-loop fails fast. */
    private boolean isRunning() {
        try {
            Process process = new ProcessBuilder(engine, "ps", "--format", "{{.Names}}")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);
            return output.lines().anyMatch(containerName::equals);
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs an engine command, failing with its combined output on error. */
    private static void runEngineCommand(List<String> command, String failureMessage)
            throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw new IllegalStateException(failureMessage + ":\n" + output);
        }
    }

    private static String engine() {
        return System.getenv().getOrDefault("CONTAINER_ENGINE", "podman");
    }

    private static boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder("which", command)
                    .redirectErrorStream(true).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A container running as another uid can leave files we
                    // cannot remove; the next run uses a different directory.
                }
            });
        } catch (IOException ignored) {
            // Same: cleanup is best-effort, never a test failure.
        }
    }
}
