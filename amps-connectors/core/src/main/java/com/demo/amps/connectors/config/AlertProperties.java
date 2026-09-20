package com.demo.amps.connectors.config;

import com.demo.amps.connectors.alert.Alert;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Where the application's alerts go, and how many of them.
 *
 * <pre>{@code
 * amps-connectors:
 *   alerts:
 *     enabled: true
 *     application: instrument-enricher     # blank -> spring.application.name
 *     min-severity: INFO
 *     suppress-repeats: 30s
 *     queue-size: 1000
 *     amps: { topic: connectors/alerts }
 *     # kafka: { bootstrap-servers: "${KAFKA_HOST:localhost}:9092", topic: connectors.alerts }
 * }</pre>
 *
 * <p>Enabled with no sink configured is the default and it is not a mistake: every alert is
 * still logged at its own severity, so a bare application says everything it would have said
 * on a topic in its own log. A sink is where the same alert goes so that something
 * <em>other</em> than a log reader can act on it.
 *
 * <p>The two throttles exist because an alert is raised from a record path, and a record path
 * can run at thousands per second. {@link #getSuppressRepeats()} collapses a storm of one code
 * from one connector into the first alert and, when the window closes, one more carrying how
 * many followed; {@link #getQueueSize()} bounds what a slow sink can hold in memory, dropping
 * the oldest and counting the drop rather than blocking the thread that read the record.
 */
public class AlertProperties {

    /** The AMPS side: a JSON topic the alerts are published onto. */
    public static class Amps {

        /** The AMPS topic; JSON message type. Setting it is what enables the sink. */
        private String topic;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }
    }

    /** The Kafka side: a topic the alerts are produced onto, keyed by alert code. */
    public static class Kafka {

        /** {@code host:port} list of bootstrap brokers. */
        private String bootstrapServers;

        /** The Kafka topic. Setting it is what enables the sink. */
        private String topic;

        /** Raw producer properties, applied last and passed through untouched. */
        @NotNull
        private Map<String, String> properties = new LinkedHashMap<>();

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties == null ? new LinkedHashMap<>() : properties;
        }
    }

    /** The name a bare application carries when nothing names it. */
    public static final String DEFAULT_APPLICATION = "amps-connector";

    /** {@code false} raises nothing at all -- not even a log line. */
    private boolean enabled = true;

    /**
     * The name every alert carries in its {@code application} field, and half of the AMPS
     * client name ({@code <prefix>-<application>-alerts}). Blank falls back to
     * {@code spring.application.name}, then to {@link #DEFAULT_APPLICATION}.
     */
    private String application;

    /** Alerts below this severity are dropped at the point they are raised. */
    @NotNull
    private Alert.Severity minSeverity = Alert.Severity.INFO;

    /**
     * How long, after an alert with a given code from a given connector, further alerts with
     * the same code and connector are only counted. When the window closes the last of them
     * is sent with {@code repeats} set to how many were collapsed. {@code 0} sends every one.
     */
    @NotNull
    private Duration suppressRepeats = Duration.ofSeconds(30);

    /**
     * How many alerts can wait for the sinks. A full queue drops its <em>oldest</em> alert to
     * make room -- the newest is the one that says what is happening now -- and counts the
     * drop, so a sink that cannot keep up costs alerts, never memory and never the record
     * thread's time.
     */
    @Min(1)
    private int queueSize = 1_000;

    /** AMPS sink settings; a topic selects it. */
    @Valid
    private Amps amps;

    /** Kafka sink settings; a topic selects it ({@code :amps-connectors:source-kafka}). */
    @Valid
    private Kafka kafka;

    /**
     * The application name alerts carry, with the fallbacks applied.
     *
     * <p>Resolved in code rather than by a {@code ${spring.application.name}} placeholder in
     * the configuration tree, because the tree is bound and validated by a test that has no
     * application running -- and a placeholder with no default fails that binding.
     *
     * @param environment the application's environment, for {@code spring.application.name};
     *     may be {@code null}
     * @return {@code application} when set, else {@code spring.application.name}, else
     *     {@link #DEFAULT_APPLICATION}
     */
    public String applicationName(Environment environment) {
        if (application != null && !application.isBlank()) {
            return application.trim();
        }
        String springName = environment == null
                ? null
                : environment.getProperty("spring.application.name");
        return springName == null || springName.isBlank()
                ? DEFAULT_APPLICATION
                : springName.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public Alert.Severity getMinSeverity() {
        return minSeverity;
    }

    public void setMinSeverity(Alert.Severity minSeverity) {
        this.minSeverity = minSeverity;
    }

    public Duration getSuppressRepeats() {
        return suppressRepeats;
    }

    public void setSuppressRepeats(Duration suppressRepeats) {
        this.suppressRepeats = suppressRepeats;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public Amps getAmps() {
        return amps;
    }

    public void setAmps(Amps amps) {
        this.amps = amps;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }
}
