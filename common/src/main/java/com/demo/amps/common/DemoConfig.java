package com.demo.amps.common;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Everything the demos need to find the AMPS instance, resolved from environment
 * variables with sane defaults for the podman setup in {@code server/}.
 *
 * <p>System properties win over environment variables so a single demo can be
 * pointed somewhere else without exporting anything:
 * {@code ./gradlew :clients:run -Damps.port=9107 --args="sow-query"}.
 */
public final class DemoConfig {

    private DemoConfig() {
    }

    /** Host the AMPS instance is published on. */
    public static String host() {
        return get("amps.host", "AMPS_HOST", "127.0.0.1");
    }

    /** The `amps` protocol TCP port (see server/config/amps-config.xml). */
    public static int port() {
        return Integer.parseInt(get("amps.port", "AMPS_PORT", "9007"));
    }

    /** The admin/monitoring HTTP port. */
    public static int adminPort() {
        return Integer.parseInt(get("amps.adminPort", "AMPS_ADMIN_PORT", "8085"));
    }

    /**
     * Client URI for the JSON message type.
     *
     * <p>The trailing path selects protocol and message type: {@code /amps/json}
     * means "the AMPS protocol, carrying JSON payloads". Every demo in this repo
     * uses JSON exclusively -- the protobuf schema is the contract, JSON is the
     * encoding.
     */
    public static String uri() {
        return "tcp://" + host() + ":" + port() + "/amps/json";
    }

    /** Base URL of the AMPS admin HTTP interface. */
    public static String adminUrl() {
        return "http://" + host() + ":" + adminPort();
    }

    /**
     * Host-side path of the AMPS data directory (bind-mounted into the container).
     * The journal-size lab measures real files under here.
     */
    public static Path dataDir() {
        return Paths.get(get("amps.dataDir", "AMPS_DATA_DIR", "server/data")).toAbsolutePath();
    }

    /** Directory for client-side bookmark and publish stores. */
    public static Path clientStateDir() {
        return Paths.get(get("amps.clientStateDir", "AMPS_CLIENT_STATE_DIR", "build/client-state"))
                .toAbsolutePath();
    }

    /** Default command timeout in milliseconds. */
    public static long timeoutMillis() {
        return Long.parseLong(get("amps.timeoutMs", "AMPS_TIMEOUT_MS", "10000"));
    }

    private static String get(String systemProperty, String environmentVariable, String fallback) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
