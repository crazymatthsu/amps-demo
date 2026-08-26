package com.demo.amps.fix42.it;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A throwaway AMPS instance in a container, running the {@code fix42-chaining}
 * flow.
 *
 * <p>Deliberately not Testcontainers. This repository already drives podman
 * from a shell script ({@code server/scripts/amps.sh}) and the image is
 * site-specific -- there is no public AMPS server image to pull -- so the
 * lightest honest thing is to invoke the engine directly and skip the whole
 * suite when no image is configured. That keeps {@code ./gradlew build} green
 * on a machine that has never seen AMPS, and makes it do real work on one that
 * has.
 *
 * <p>Each instance gets its own port and its own empty data directory, so a
 * run never inherits SOW records or a chain map from the last one -- which
 * matters more than usual here, because the chaining module's whole job is to
 * remember identifiers across restarts.
 */
public final class AmpsTestServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AmpsTestServer.class);

    private static final String FLOW_DIR = "server/config/flows/fix42-chaining";
    private static final String CONTAINER_CONFIG_DIR = "/amps/config";
    private static final String CONTAINER_DATA_DIR = "/amps/data";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(180);

    private final String engine;
    private final String image;
    private final String containerName;
    private final int port;
    private final Path dataDir;
    private boolean started;

    private AmpsTestServer(String engine, String image, String containerName, int port,
                           Path dataDir) {
        this.engine = engine;
        this.image = image;
        this.containerName = containerName;
        this.port = port;
        this.dataDir = dataDir;
    }

    /**
     * Why this suite cannot run here, or empty when it can.
     *
     * <p>Returned rather than thrown so the test class can turn it into a
     * skip with a readable reason instead of a failure.
     */
    public static java.util.Optional<String> unavailableReason() {
        String image = System.getenv("AMPS_IMAGE");
        if (image == null || image.isBlank()) {
            return java.util.Optional.of("AMPS_IMAGE is not set. There is no public AMPS server "
                    + "image; build one from server/Containerfile and export AMPS_IMAGE to run "
                    + "these tests.");
        }
        String engine = engine();
        if (!commandExists(engine)) {
            return java.util.Optional.of("container engine '" + engine + "' is not on PATH");
        }
        if (!Files.isRegularFile(Path.of(FLOW_DIR, "amps-config.xml"))) {
            return java.util.Optional.of("cannot find " + FLOW_DIR + "/amps-config.xml -- the "
                    + "integration test must run with the repository root as its working "
                    + "directory");
        }
        return java.util.Optional.empty();
    }

    /** Starts an instance and blocks until it accepts connections. */
    public static AmpsTestServer start() throws Exception {
        String image = System.getenv("AMPS_IMAGE");
        int port = freePort();
        String name = "amps-fix42-it-" + port;

        // Under build/, so it is inside the repository (and therefore inside the
        // path a podman machine shares on macOS) and `gradle clean` disposes of it.
        Path dataDir = Path.of("build", "fix42-it", name).toAbsolutePath();
        deleteRecursively(dataDir);
        for (String directory : List.of("sow", "journal", "stats")) {
            Files.createDirectories(dataDir.resolve(directory));
        }

        AmpsTestServer server =
                new AmpsTestServer(engine(), image, name, port, dataDir);
        server.run();
        return server;
    }

    private void run() throws Exception {
        Path flowDir = Path.of(FLOW_DIR).toAbsolutePath();
        String mountSuffix = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("linux") ? ":z" : "";

        List<String> command = new ArrayList<>(List.of(engine, "run", "-d",
                "--name", containerName,
                "-p", port + ":9007",
                "-v", flowDir + ":" + CONTAINER_CONFIG_DIR + mountSuffix,
                "-v", dataDir + ":" + CONTAINER_DATA_DIR + mountSuffix,
                "-w", CONTAINER_DATA_DIR,
                "--entrypoint", System.getenv().getOrDefault("AMPS_BIN", "/opt/amps/bin/ampServer")));
        String platform = System.getenv().getOrDefault("AMPS_PLATFORM", "linux/amd64");
        if (!platform.isBlank()) {
            command.addAll(2, List.of("--platform", platform));
        }
        command.add(image);
        command.add(CONTAINER_CONFIG_DIR + "/amps-config.xml");

        log.info("starting AMPS container {} on port {}", containerName, port);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw new IllegalStateException("could not start AMPS container:\n" + output);
        }
        started = true;
        awaitReady();
        log.info("AMPS ready on tcp://127.0.0.1:{}", port);
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

        Process process = new ProcessBuilder(engine, "restart", containerName)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw new IllegalStateException("could not restart AMPS container:\n" + output);
        }

        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (readyMarkerCount() > before) {
                log.info("AMPS container {} restarted on port {}", containerName, port);
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
        return "tcp://127.0.0.1:" + port + "/amps/fix";
    }

    public int port() {
        return port;
    }

    /** The server log, for diagnosing a failure. */
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
     * being open.
     *
     * <p>The container engine's port forwarder accepts connections as soon as
     * the container exists, so a client that races it connects successfully and
     * is then dropped mid-logon ("Socket closed"). The server's own
     * "initialization completed" log line is the real signal, exactly as
     * {@code server/scripts/amps.sh wait} uses it.
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
            if (portOpen && logs().contains("initialization completed")) {
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
