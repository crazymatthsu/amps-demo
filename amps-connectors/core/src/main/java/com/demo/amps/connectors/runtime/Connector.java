package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.AlertingAmpsPublisher;
import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.amps.AmpsPublisher;
import com.demo.amps.connectors.amps.AmpsPublisherFactory;
import com.demo.amps.connectors.amps.BatchPublisher;
import com.demo.amps.connectors.config.AmpsServerProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceResolver;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.support.MessageBuilder;

/**
 * One upstream feed bridged onto one AMPS topic: the source, the pipeline, the flow and the
 * AMPS client, started and stopped together.
 *
 * <p>Nothing is started in the constructor. {@link ConnectorManager} starts connectors when
 * the application does and retries the ones that failed, so a broker that is down at boot is a
 * connector that keeps trying rather than an application that would not start. Construction,
 * on the other hand, <em>does</em> compile the pipeline, so a bad regular expression or an
 * unknown transform bean is a startup failure and not a surprise on the first record.
 *
 * <p>The order of {@link #stop()} is the part worth reading: close the source first so nothing
 * new arrives, then force the aggregator's partial batch out (synchronously -- by the time
 * that returns it has been published and acknowledged), then remove the flow, then close the
 * client. Reversed, the last few records the source had already read would be dropped on the
 * floor at every shutdown.
 *
 * <p>What goes wrong is reported twice, on purpose: counted here, where the status line reads
 * it, and raised as an alert, where something other than a log reader can act on it. The AMPS
 * client is wrapped in an {@link AlertingAmpsPublisher} for the publish side
 * ({@code PUBLISH_FAILED}, {@code PUBLISH_FLUSH_TIMEOUT}), and the hand-off from the source's
 * thread into the flow raises {@code SOURCE_ERROR} for anything that escapes the pipeline --
 * a path that should be unreachable, which is exactly why it is worth an alert when it is not.
 */
public final class Connector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Connector.class);

    /** Raised, at WARN, when a record throws on its way from the source into the flow. */
    public static final String SOURCE_ERROR = "SOURCE_ERROR";

    private final ConnectorProperties properties;
    private final RecordPipeline pipeline;
    private final AmpsPublisher publisher;
    private final BatchPublisher batchPublisher;
    private final SourceResolver sources;
    private final ConnectorFlowFactory flows;
    private final Alerts alerts;

    private final AtomicLong sourceErrors = new AtomicLong();

    private volatile RecordSource source;
    private volatile ConnectorFlowFactory.ConnectorFlow flow;
    private volatile boolean started;

    /**
     * @param properties the connector configuration
     * @param server the application's AMPS server block
     * @param transforms the application's transform beans, for {@code bean:} steps
     * @param publishers builds this connector's AMPS client
     * @param sources resolves this connector's source from the modules on the classpath
     * @param flows registers this connector's Spring Integration flow
     * @param alerts where this connector reports what goes wrong
     */
    public Connector(
            ConnectorProperties properties,
            AmpsServerProperties server,
            TransformRegistry transforms,
            AmpsPublisherFactory publishers,
            SourceResolver sources,
            ConnectorFlowFactory flows,
            Alerts alerts) {
        this.properties = properties;
        this.alerts = alerts;
        this.pipeline = new RecordPipeline(properties, transforms);
        // Wrapped here, not in BatchPublisher: that class is about the acknowledgment
        // contract and stays ignorant of who is listening.
        this.publisher = new AlertingAmpsPublisher(
                publishers.create(properties), properties.getName(), alerts);
        this.batchPublisher =
                new BatchPublisher(publisher, properties.getName(), server.getFlushTimeout());
        this.sources = sources;
        this.flows = flows;
    }

    /** The connector's configured name; the id of its flow and part of its AMPS client name. */
    public String name() {
        return properties.getName();
    }

    /** Whether the source is subscribed and the flow is registered. */
    public boolean isStarted() {
        return started;
    }

    /**
     * Connect, register the flow, and subscribe.
     *
     * @throws Exception if AMPS refused the logon or the source could not be started; the
     *     manager logs it and retries on the next tick
     */
    public synchronized void start() throws Exception {
        if (started) {
            return;
        }
        // AMPS first: a connector that subscribed before it could publish would read a feed it
        // has nowhere to put, and for a source that cannot rewind those records are gone.
        publisher.connect();
        ConnectorFlowFactory.ConnectorFlow registered =
                flows.register(properties, pipeline, batchPublisher);
        RecordSource resolved;
        try {
            resolved = sources.resolve(properties);
            resolved.start(record -> {
                try {
                    registered.input().send(MessageBuilder.withPayload(record).build());
                } catch (RuntimeException e) {
                    // The pipeline runs on this thread, so anything it throws arrives here.
                    // A source's reader thread must survive one bad record.
                    long count = sourceErrors.incrementAndGet();
                    if (count <= 10 || count % 1_000 == 0) {
                        log.warn("[{}] record #{} failed on the way into the flow: {}",
                                name(), count, e.toString());
                    }
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("error", e.toString());
                    details.put("sourceErrors", count);
                    alerts.raise(Alert.of(Alert.Severity.WARN, SOURCE_ERROR,
                                    "record #" + count + " failed on the way into the flow: "
                                            + e.getMessage())
                            .withConnector(name())
                            .withDetails(details));
                }
            });
        } catch (RuntimeException e) {
            flows.unregister(registered);
            publisher.close();
            throw e;
        }
        this.source = resolved;
        this.flow = registered;
        this.started = true;
        log.info("[{}] started: {} {} -> {} {} (batch {} / {})",
                name(), properties.getFormat(), properties.getSource().describe(),
                properties.getAmps().getMessageType(), properties.getAmps().getTopic(),
                properties.getAmps().getBatch().getMaxMessages(),
                properties.getAmps().getBatch().getFlushInterval());
    }

    /** Unsubscribe, publish whatever is buffered, unregister the flow and disconnect. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        RecordSource current = source;
        source = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException e) {
                log.warn("[{}] closing the source failed", name(), e);
            }
        }
        ConnectorFlowFactory.ConnectorFlow registered = flow;
        flow = null;
        if (registered != null) {
            try {
                // Synchronous: the partial batch is published and acknowledged before this
                // returns, which is the whole reason the store was kept.
                registered.release();
            } catch (RuntimeException e) {
                log.warn("[{}] the final batch could not be published", name(), e);
            }
            try {
                flows.unregister(registered);
            } catch (RuntimeException e) {
                log.warn("[{}] removing the flow failed", name(), e);
            }
        }
        publisher.close();
        log.info("[{}] stopped after {}", name(), counters());
    }

    /** Whether both ends are up: the feed is connected and so is the AMPS client. */
    public boolean isConnected() {
        RecordSource current = source;
        return started && current != null && current.isConnected() && publisher.isConnected();
    }

    /** One line for the periodic status log. */
    public String status() {
        return String.format("%-24s %-9s %-28s %s",
                name(),
                started ? (isConnected() ? "RUNNING" : "RETRYING") : "STOPPED",
                properties.getAmps().getTopic(),
                counters());
    }

    private String counters() {
        return String.format(
                "received=%d published=%d batches=%d failed=%d rejected=%d filtered=%d "
                        + "dropped=%d ignored-deletes=%d",
                pipeline.received(), batchPublisher.publishedMessages(),
                batchPublisher.publishedBatches(), batchPublisher.failedBatches(),
                pipeline.rejected(), pipeline.filtered(), pipeline.dropped(),
                pipeline.ignoredDeletes());
    }

    /** The compiled pipeline, for its counters. */
    public RecordPipeline pipeline() {
        return pipeline;
    }

    /** The batch publisher, for its counters. */
    public BatchPublisher batchPublisher() {
        return batchPublisher;
    }

    /** Records received from the source since construction. */
    public long received() {
        return pipeline.received();
    }

    /** Records published in batches AMPS acknowledged as persisted. */
    public long published() {
        return batchPublisher.publishedMessages();
    }

    /** Records that could not be decoded, keyed or encoded. */
    public long rejected() {
        return pipeline.rejected();
    }

    /** Records the filter said no to. */
    public long filtered() {
        return pipeline.filtered();
    }

    /** Records a transform said no to, and removals with no usable key. */
    public long dropped() {
        return pipeline.dropped();
    }

    /** Removals on a connector configured {@code on-delete: IGNORE}. */
    public long ignoredDeletes() {
        return pipeline.ignoredDeletes();
    }

    /** Records that threw on the way into the flow; the reader thread survived each one. */
    public long sourceErrors() {
        return sourceErrors.get();
    }

    @Override
    public void close() {
        stop();
    }
}
