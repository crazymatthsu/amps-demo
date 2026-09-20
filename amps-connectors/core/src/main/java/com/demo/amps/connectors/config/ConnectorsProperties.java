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
 *
 * <p>Beside the connectors, two application-level blocks: {@link #getResources() resources},
 * the shared lookup tables and clients that code transforms enrich from, started before the
 * connectors and stopped after them; and {@link #getAlerts() alerts}, where everything that
 * goes wrong is reported beyond the log.
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
     * Configuration-defined shared resources -- a reloadable lookup table read from a
     * database, today. An {@code AppResource} <em>bean</em> needs no entry here.
     */
    @Valid
    @NotNull
    private List<ResourceProperties> resources = new ArrayList<>();

    /** Where alerts go, and how many of them. Enabled and log-only by default. */
    @Valid
    @NotNull
    private AlertProperties alerts = new AlertProperties();

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

    /** The enabled resources, in configuration order -- which is their start order. */
    public List<ResourceProperties> enabledResources() {
        return resources.stream().filter(ResourceProperties::isEnabled).toList();
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

    public List<ResourceProperties> getResources() {
        return resources;
    }

    public void setResources(List<ResourceProperties> resources) {
        this.resources = resources == null ? new ArrayList<>() : resources;
    }

    public AlertProperties getAlerts() {
        return alerts;
    }

    public void setAlerts(AlertProperties alerts) {
        this.alerts = alerts == null ? new AlertProperties() : alerts;
    }

    public Duration getStatusInterval() {
        return statusInterval;
    }

    public void setStatusInterval(Duration statusInterval) {
        this.statusInterval = statusInterval;
    }
}
