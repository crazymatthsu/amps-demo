package com.demo.amps.connectors.it;

import com.demo.amps.connectors.app.ConnectorApplication;
import com.demo.amps.connectors.runtime.Connector;
import com.demo.amps.connectors.runtime.ConnectorManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The real {@link ConnectorApplication}, started in this JVM against a throwaway AMPS
 * container, with its connectors written as command-line properties.
 *
 * <p>The deployed article, not a hand-assembled subset of it: the same main class, the same
 * auto-configuration, the same {@code ConnectorManager} lifecycle and the same AMPS client the
 * image runs. Only three things are said differently -- the web server is off (nothing here
 * probes an actuator, and two suites binding 8080 in one Gradle run would collide), the AMPS
 * endpoint points at the container's ephemeral port, and the publish store is in memory so a
 * run leaves nothing on disk for the next one to replay.
 *
 * <p>Connectors are written as {@code --amps-connectors.connectors[i].…} arguments because
 * command-line properties are the one source that outranks the baked {@code application.yml}
 * -- which is exactly the precedence a deployment relies on.
 *
 * <pre>{@code
 * try (ConnectorAppRunner app = ConnectorAppRunner.against(server.port())
 *         .connector("positions")
 *             .set("format", "JSON")
 *             .set("source.tcp.mode", "LISTEN")
 *             .set("amps.topic", "sow/connectors/positions")
 *         .start()) {
 *     ...
 * }
 * }</pre>
 */
final class ConnectorAppRunner implements AutoCloseable {

    /** How long a connector gets to log on to AMPS and subscribe before the test gives up. */
    private static final Duration STARTUP = Duration.ofSeconds(30);

    private final ConfigurableApplicationContext context;

    private ConnectorAppRunner(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /**
     * A builder for an application publishing into the AMPS instance on {@code ampsPort}.
     *
     * @param ampsPort the host port the throwaway container listens on
     * @return the builder
     */
    static Builder against(int ampsPort) {
        return new Builder(ampsPort);
    }

    /** The lifecycle bean owning the connectors, for their counters and their state. */
    ConnectorManager manager() {
        return context.getBean(ConnectorManager.class);
    }

    /**
     * One connector by name, for its counters.
     *
     * @param name the configured connector name
     * @return the running connector
     */
    Connector connector(String name) {
        return manager().connectors().stream()
                .filter(c -> name.equals(c.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no connector named " + name));
    }

    /**
     * Blocks until every connector has connected to AMPS and started its source.
     *
     * <p>{@code SpringApplication.run} returns as soon as the lifecycle has been asked to
     * start, and a connector that could not reach AMPS on the first attempt is retried on a
     * five-second tick rather than failing the context -- so "the application started" and
     * "the connectors are up" are genuinely different moments, and a test that writes to a
     * socket in between would write to a port nobody has bound yet.
     */
    ConnectorAppRunner awaitStarted() {
        Awaitility.await("every connector connected and subscribed")
                .atMost(STARTUP)
                .until(() -> !manager().connectors().isEmpty()
                        && manager().connectors().stream().allMatch(Connector::isStarted));
        return this;
    }

    @Override
    public void close() {
        // Closing the context stops the manager, which closes each source, forces the
        // aggregator's partial batch out and only then disconnects -- so whatever the test
        // wrote last is in AMPS by the time this returns.
        context.close();
    }

    /** Collects properties for one application, then starts it. */
    static final class Builder {

        private final Map<String, String> properties = new LinkedHashMap<>();
        private int connectors;
        private String prefix;

        private Builder(int ampsPort) {
            properties.put("spring.main.web-application-type", "none");
            properties.put("spring.main.banner-mode", "off");
            properties.put("amps-connectors.amps.host", "127.0.0.1");
            properties.put("amps-connectors.amps.port", Integer.toString(ampsPort));
            // In the client's heap: nothing to clean up between suites, and a reconnect still
            // replays. The tests never restart the process, which is the only thing FILE buys.
            properties.put("amps-connectors.amps.publish-store", "MEMORY");
            properties.put("amps-connectors.amps.flush-timeout", "10s");
            // The status line is evidence for an operator, noise for a test run.
            properties.put("amps-connectors.status-interval", "1h");
        }

        /** Sets any application property, e.g. a logging level. */
        Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        /**
         * Starts a new connector block; subsequent {@link #set} calls apply to it.
         *
         * @param name the connector name -- also its AMPS client name and its flow id
         * @return this builder
         */
        Builder connector(String name) {
            prefix = "amps-connectors.connectors[" + connectors++ + "].";
            properties.put(prefix + "name", name);
            properties.put(prefix + "enabled", "true");
            return this;
        }

        /**
         * Sets one property of the connector most recently named.
         *
         * @param key the key relative to the connector, e.g. {@code amps.topic}
         * @param value the value, rendered with {@code toString}
         * @return this builder
         */
        Builder set(String key, Object value) {
            if (prefix == null) {
                throw new IllegalStateException("call connector(name) before set(...)");
            }
            properties.put(prefix + key, String.valueOf(value));
            return this;
        }

        /** Boots the application and waits for every connector to be up. */
        ConnectorAppRunner start() {
            List<String> args = new ArrayList<>(properties.size());
            properties.forEach((key, value) -> args.add("--" + key + "=" + value));
            SpringApplication application = new SpringApplication(ConnectorApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setRegisterShutdownHook(false);
            return new ConnectorAppRunner(application.run(args.toArray(String[]::new)))
                    .awaitStarted();
        }
    }
}
