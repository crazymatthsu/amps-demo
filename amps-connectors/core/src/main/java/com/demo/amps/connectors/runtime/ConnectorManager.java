package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.amps.AmpsPublisherFactory;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.ConnectorValidator;
import com.demo.amps.connectors.config.ConnectorsProperties;
import com.demo.amps.connectors.source.SourceResolver;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Owns every configured connector: validates the configuration, starts what is enabled, keeps
 * retrying what would not start, and stops everything on shutdown.
 *
 * <p>A {@link SmartLifecycle} rather than an {@code @PostConstruct}, so connectors start after
 * the rest of the context is ready -- including the Spring Integration infrastructure their
 * flows register into -- and stop before it is torn down, which is what lets a stopping
 * connector still publish its partial batch.
 *
 * <p>Validation is fail-fast and start is not. A configuration mistake is the developer's to
 * fix now, so {@link #start()} throws with the whole list; an unreachable broker is the
 * network's problem and might be over in a minute, so a connector that will not start is
 * logged and retried on a shared five-second tick. One connector's broker being down therefore
 * never holds up the others, and none of them needs a restart to recover.
 *
 * <p>The status line is the other half of that: the only routine evidence that a quiet
 * connector is quiet because the feed is quiet, rather than because it has been retrying for
 * an hour.
 */
public class ConnectorManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConnectorManager.class);

    /** How often a connector that failed to start is tried again. */
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    private final ConnectorsProperties properties;
    private final TransformRegistry transforms;
    private final AmpsPublisherFactory publishers;
    private final SourceResolver sources;
    private final ConnectorFlowFactory flows;
    private final List<Connector> connectors = new ArrayList<>();

    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    /**
     * @param properties the bound {@code amps-connectors:} configuration
     * @param transforms the application's transform beans
     * @param publishers builds each connector's AMPS client
     * @param sources resolves each connector's source
     * @param flows registers each connector's Spring Integration flow
     */
    public ConnectorManager(
            ConnectorsProperties properties,
            TransformRegistry transforms,
            AmpsPublisherFactory publishers,
            SourceResolver sources,
            ConnectorFlowFactory flows) {
        this.properties = properties;
        this.transforms = transforms;
        this.publishers = publishers;
        this.sources = sources;
        this.flows = flows;
    }

    /**
     * Fail fast on a configuration that would misbehave at runtime.
     *
     * @throws IllegalStateException listing every problem found
     */
    public void validate() {
        List<String> errors = ConnectorValidator.validate(properties);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "invalid amps-connectors configuration:\n  - " + String.join("\n  - ", errors));
        }
    }

    /** The connectors this manager runs; empty until {@link #start()}. */
    public List<Connector> connectors() {
        return List.copyOf(connectors);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.isEnabled()) {
            log.info("amps-connectors is disabled; no connectors will run");
            running = true;
            return;
        }
        validate();
        for (ConnectorProperties connector : properties.enabledConnectors()) {
            connectors.add(new Connector(
                    connector, properties.getAmps(), transforms, publishers, sources, flows));
        }
        running = true;
        if (connectors.isEmpty()) {
            log.info("amps-connectors has no enabled connectors configured");
            return;
        }
        log.info("starting {} connector(s)", connectors.size());
        startPending();
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "amps-connectors-manager");
            thread.setDaemon(true);
            return thread;
        });
        long retryMillis = RETRY_INTERVAL.toMillis();
        scheduler.scheduleWithFixedDelay(
                this::retryQuietly, retryMillis, retryMillis, TimeUnit.MILLISECONDS);
        long statusMillis = Math.max(1_000L, properties.getStatusInterval().toMillis());
        scheduler.scheduleWithFixedDelay(
                this::logStatus, statusMillis, statusMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
        }
        for (Connector connector : connectors) {
            try {
                connector.stop();
            } catch (RuntimeException e) {
                log.warn("[{}] stop failed", connector.name(), e);
            }
        }
        connectors.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Late in the start order and early in the stop order: the flows register into Spring
     * Integration's infrastructure, so it has to be up first and still up while a stopping
     * connector publishes its last batch.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1_000;
    }

    /** Start every connector that is not running yet; failures are retried on the next tick. */
    private void startPending() {
        for (Connector connector : connectors) {
            if (connector.isStarted()) {
                continue;
            }
            try {
                connector.start();
            } catch (Exception e) {
                log.warn("[{}] start failed, retrying in {}s: {}",
                        connector.name(), RETRY_INTERVAL.toSeconds(), e.toString());
            }
        }
    }

    /** The retry tick. Never throws: a scheduled task that throws is never scheduled again. */
    private void retryQuietly() {
        try {
            synchronized (this) {
                if (running) {
                    startPending();
                }
            }
        } catch (RuntimeException e) {
            log.warn("connector retry tick failed", e);
        }
    }

    private void logStatus() {
        try {
            if (running && !connectors.isEmpty()) {
                log.info("connector status:{}", status());
            }
        } catch (RuntimeException e) {
            log.warn("connector status tick failed", e);
        }
    }

    /** One line per connector, for the periodic status log. */
    public String status() {
        StringBuilder text = new StringBuilder();
        for (Connector connector : connectors) {
            text.append(System.lineSeparator()).append("  ").append(connector.status());
        }
        return text.toString();
    }
}
