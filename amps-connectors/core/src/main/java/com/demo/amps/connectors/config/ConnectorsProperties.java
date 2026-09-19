package com.demo.amps.connectors.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Root of the {@code amps-connectors:} configuration tree -- the whole application is driven
 * from here.
 *
 * <pre>{@code
 * amps-connectors:
 *   enabled: true
 *   amps:
 *     host: ${AMPS_HOST:localhost}
 *     port: 9007
 *   connectors:
 *     - name: orders-kafka
 *       format: FIX
 *       source:
 *         kafka: { bootstrap-servers: kafka:9092, topic: orders, group-id: connectors }
 *       amps:
 *         topic: sow/connectors/orders
 *         message-type: fix
 * }</pre>
 *
 * <p>One AMPS server block, shared by every connector in the application, and a list of
 * connectors that is normally empty in the jar and arrives from mounted configuration. That
 * asymmetry is the deployment model: one image, N instances, each made a different application
 * by the files it mounts.
 */
@ConfigurationProperties(prefix = "amps-connectors")
@Validated
public class ConnectorsProperties {

    /** Master switch: {@code false} starts the application with no connectors running. */
    private boolean enabled = true;

    /** The AMPS instance every connector in this application publishes into. */
    @Valid
    @NotNull
    private AmpsServerProperties amps = new AmpsServerProperties();

    /** The connectors this application runs. One upstream feed each. */
    @Valid
    @NotNull
    private List<ConnectorProperties> connectors = new ArrayList<>();

    /**
     * How often each connector logs its counters (received, published, rejected, filtered,
     * dropped). The only routine evidence that a quiet connector is quiet because the feed is
     * quiet rather than because it is broken.
     */
    @NotNull
    private Duration statusInterval = Duration.ofSeconds(60);

    /** The enabled connectors, in configuration order. */
    public List<ConnectorProperties> enabledConnectors() {
        return connectors.stream().filter(ConnectorProperties::isEnabled).toList();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AmpsServerProperties getAmps() {
        return amps;
    }

    public void setAmps(AmpsServerProperties amps) {
        this.amps = amps;
    }

    public List<ConnectorProperties> getConnectors() {
        return connectors;
    }

    public void setConnectors(List<ConnectorProperties> connectors) {
        this.connectors = connectors == null ? new ArrayList<>() : connectors;
    }

    public Duration getStatusInterval() {
        return statusInterval;
    }

    public void setStatusInterval(Duration statusInterval) {
        this.statusInterval = statusInterval;
    }
}
