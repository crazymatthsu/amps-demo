package com.demo.amps.fix42.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * A throwaway AMPS instance in a container, running the {@code fix42-chaining}
 * flow.
 *
 * <p>Two implementations, because the two places these tests run want
 * different things:
 *
 * <ul>
 *   <li>{@link ContainerCliAmpsServer} drives {@code podman} (or {@code docker})
 *       as a subprocess. It needs only the engine binary on {@code PATH}, which
 *       is what a developer machine already has -- on macOS podman exposes a
 *       Docker-compatible socket only if you go and enable it.</li>
 *   <li>{@link TestcontainersAmpsServer} talks to the engine's Docker API. That
 *       is the shape CI wants: on a GitHub runner the socket is there by
 *       default, and the reaper removes containers even when the JVM is killed
 *       rather than shut down.</li>
 * </ul>
 *
 * <p>Choose with {@code AMPS_TEST_HARNESS}: {@code cli} (the default, aliases
 * {@code podman} and {@code docker}) or {@code testcontainers} (alias
 * {@code tc}). The default is deliberately the one that needs no setup, so
 * running these tests on a laptop stays a one-variable affair -- {@code
 * AMPS_IMAGE} and nothing else.
 *
 * <p>The image is site-specific -- there is no public AMPS server image, it has
 * to be built from a release tarball -- so both report
 * {@link #unavailableReason()} instead of failing when one is not configured.
 * That keeps {@code ./gradlew build} green on a machine that has never seen
 * AMPS, and makes it do real work on one that has. A green build is therefore
 * not by itself proof these ran: check for {@code SKIPPED} if it matters.
 *
 * <p>Whichever is chosen, a run never inherits SOW records or a chain map from
 * the last one -- which matters more than usual here, because the chaining
 * module's whole job is to remember identifiers across restarts -- while state
 * still survives a deliberate {@link #restart()}.
 */
public interface AmpsTestServer extends AutoCloseable {

    /** Where the flow's config lives, relative to the repository root. */
    String FLOW_DIR = "server/config/flows/fix42-chaining";

    String CONTAINER_CONFIG_DIR = "/amps/config";
    String CONTAINER_DATA_DIR = "/amps/data";

    /** The port AMPS listens on inside the container. */
    int AMPS_PORT = 9007;

    Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    /**
     * The server's own readiness signal.
     *
     * <p>An open port is not one: the engine's port forwarder accepts
     * connections as soon as the container exists, so a client that races it
     * connects successfully and is then dropped mid-logon ("Socket closed").
     * This is the same line {@code server/scripts/amps.sh wait} keys on.
     */
    String READY_MARKER = "initialization completed";

    /** Which harness starts the container. */
    enum Harness {
        CLI,
        TESTCONTAINERS;

        /**
         * Reads {@code AMPS_TEST_HARNESS}.
         *
         * <p>An unrecognised value throws rather than falling back. Silently
         * running the other harness would be the same class of mistake as a
         * cached all-skipped result: the build looks like it did what you
         * asked and did something else.
         */
        static Harness selected() {
            String raw = System.getenv("AMPS_TEST_HARNESS");
            if (raw == null || raw.isBlank()) {
                return CLI;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "cli", "podman", "docker" -> CLI;
                case "testcontainers", "tc" -> TESTCONTAINERS;
                default -> throw new IllegalArgumentException(
                        "AMPS_TEST_HARNESS='" + raw + "' is not recognised. Use 'cli' (aliases "
                                + "'podman', 'docker') or 'testcontainers' (alias 'tc').");
            };
        }
    }

    /**
     * Why this suite cannot run here, or empty when it can.
     *
     * <p>Returned rather than thrown so the test class can turn it into a skip
     * with a readable reason instead of a failure.
     */
    static Optional<String> unavailableReason() {
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
        return switch (Harness.selected()) {
            case CLI -> ContainerCliAmpsServer.unavailableReason();
            case TESTCONTAINERS -> TestcontainersAmpsServer.unavailableReason();
        };
    }

    /** Starts an instance and blocks until AMPS is genuinely ready. */
    static AmpsTestServer start() throws Exception {
        return switch (Harness.selected()) {
            case CLI -> ContainerCliAmpsServer.start();
            case TESTCONTAINERS -> TestcontainersAmpsServer.start();
        };
    }

    /**
     * The container's command: create the directories AMPS resolves its
     * relative config paths against, then hand the process over to the server.
     *
     * <p>{@code exec} matters -- without it the shell stays PID 1 and the
     * container's stop signal never reaches AMPS, so every teardown waits out
     * the kill timeout.
     */
    static String startupScript(String binary) {
        return "mkdir -p " + CONTAINER_DATA_DIR + "/sow " + CONTAINER_DATA_DIR + "/journal "
                + CONTAINER_DATA_DIR + "/stats"
                + " && exec " + binary + " " + CONTAINER_CONFIG_DIR + "/amps-config.xml";
    }

    /** The AMPS binary inside the image. */
    static String ampsBinary() {
        return System.getenv().getOrDefault("AMPS_BIN", "/opt/amps/bin/ampServer");
    }

    /**
     * How many times the server has announced readiness.
     *
     * <p>Counted rather than matched because {@link #restart()} needs a NEW
     * marker: the previous run's is still in the log afterwards, so testing for
     * presence returns instantly and hands back a server that is still
     * starting.
     */
    static int readyMarkerCount(String logs) {
        int count = 0;
        for (String line : logs.split("\n")) {
            if (line.contains(READY_MARKER)) {
                count++;
            }
        }
        return count;
    }

    /** The client URI for this instance, selecting the {@code fix} message type. */
    String uri();

    /** The host port AMPS is reachable on. */
    int port();

    /** The server log, for diagnosing a failure. */
    String logs();

    /**
     * Restarts the container on the same data.
     *
     * <p>For tests that care what survives: the SOW file, the journal, and --
     * the reason this exists -- the chaining key generator's persisted chain
     * map, without which the identity a record was built under would be lost
     * while the record itself survived.
     */
    void restart() throws Exception;

    @Override
    void close();
}
