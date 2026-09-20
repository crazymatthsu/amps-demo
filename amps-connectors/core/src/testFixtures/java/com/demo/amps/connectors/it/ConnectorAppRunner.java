package com.demo.amps.connectors.it;

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
 * A real connector application, started in this JVM against a throwaway AMPS container, with
 * its connectors written as command-line properties.
 *
 * <p>The deployed article, not a hand-assembled subset of it: the suite names the application's
 * own main class, and everything else is what the image runs -- the same auto-configuration,
 * the same {@code ConnectorManager} lifecycle, the same AMPS client. Only three things are
 * said differently: the web server is off (nothing here probes an actuator, and two suites
 * binding 8080 in one Gradle run would collide), the AMPS endpoint points at the container's
 * ephemeral port, and the publish store is in memory so a run leaves nothing on disk for the
 * next one to replay.
 *
 * <p>It lives in core's test fixtures rather than in the generic runner's integration suite
 * because every application module already has these fixtures on both of its test classpaths
 * through the {@code amps.connector-app} convention plugin: a custom application under
 * {@code apps/} starts itself the same way, with its own main class, and no build file
 * changes.
 *
 * <p>Connectors are written as {@code --amps-connectors.connectors[i].…} arguments because
 * command-line properties are the one source that outranks the baked {@code application.yml}
 * -- which is exactly the precedence a deployment relies on.
 *
 * <pre>{@code
 * try (ConnectorAppRunner app = ConnectorAppRunner.against(server.port(), ConnectorApplication.class)
 *         .connector("positions")
 *             .set("format", "JSON")
 *             .set("source.tcp.mode", "LISTEN")
 *             .set("amps.topic", "sow/connectors/positions")
 *         .start()) {
 *     ...
 * }
 * }</pre>
 */
public final class ConnectorAppRunner implements AutoCloseable {

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
     * @param application the application's {@code @SpringBootApplication} main class -- the
     *     generic runner's, or a custom application's under {@code apps/}
     * @return the builder
     */
    public static Builder against(int ampsPort, Class<?> application) {
        return new Builder(ampsPort, application);
    }

    /** The application context, for whatever bean a suite needs beyond the connectors. */
    public ConfigurableApplicationContext context() {
        return context;
    }

    /** The lifecycle bean owning the connectors, for their counters and their state. */
    public ConnectorManager manager() {
        return context.getBean(ConnectorManager.class);
    }

    /**
     * One connector by name, for its counters.
     *
     * @param name the configured connector name
     * @return the running connector
     */
    public Connector connector(String name) {
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
    public ConnectorAppRunner awaitStarted() {
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
    public static final class Builder {

        private final Map<String, String> properties = new LinkedHashMap<>();
        private final Class<?> application;
        private int connectors;
        private String prefix;

        private Builder(int ampsPort, Class<?> application) {
            this.application = application;
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
        public Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        /**
         * Starts a new connector block; subsequent {@link #set} calls apply to it.
         *
         * @param name the connector name -- also its AMPS client name and its flow id
         * @return this builder
         */
        public Builder connector(String name) {
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
        public Builder set(String key, Object value) {
            if (prefix == null) {
                throw new IllegalStateException("call connector(name) before set(...)");
            }
            properties.put(prefix + key, String.valueOf(value));
            return this;
        }

        /** Boots the application and waits for every connector to be up. */
        public ConnectorAppRunner start() {
            List<String> args = new ArrayList<>(properties.size());
            properties.forEach((key, value) -> args.add("--" + key + "=" + value));
            SpringApplication app = new SpringApplication(application);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setRegisterShutdownHook(false);
            return new ConnectorAppRunner(app.run(args.toArray(String[]::new)))
                    .awaitStarted();
        }
    }
}
