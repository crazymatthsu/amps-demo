package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.amps.BatchPublisher;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.source.SourceRecord;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.context.IntegrationFlowContext;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.messaging.MessageChannel;

/**
 * Builds and registers one connector's Spring Integration flow.
 *
 * <p>The flow is three steps and deliberately no more:
 *
 * <pre>{@code
 * DirectChannel --> pipeline.apply(record) --> aggregate(size | idle time) --> batchPublisher
 * }</pre>
 *
 * <p>Spring Integration earns its place here for exactly one thing: the aggregator. "Release a
 * batch when it reaches N messages <em>or</em> when it has been idle for T" is a timer, a
 * buffer, a lock and a race between them, and this repo would rather configure that than write
 * it again. Everything else in the picture is plain Java -- the pipeline is a function, the
 * batch publisher is a loop -- so there is no framework to reason about when a record goes
 * missing.
 *
 * <p>A {@link DirectChannel} is the point of the design rather than a default: it runs the
 * pipeline on the <em>source's</em> reader thread, so a source that outruns AMPS ends up
 * waiting in its own read loop instead of growing an unbounded queue. Back-pressure for free,
 * and the reason {@code batch.max-messages} bounds latency as well as throughput.
 *
 * <p>{@code handle}, not {@code transform}, because a null reply ends the flow quietly, which
 * is how a filtered, dropped or rejected record leaves the pipeline without a discard channel.
 *
 * <p>Flows are registered through {@link IntegrationFlowContext} under the connector's name
 * rather than declared as beans, because connectors are configuration: an application that
 * mounts a different {@code application.yml} runs different connectors without a different
 * jar.
 */
public class ConnectorFlowFactory {

    private final IntegrationFlowContext flowContext;

    /**
     * @param flowContext the runtime flow registry, from {@code @EnableIntegration}
     */
    public ConnectorFlowFactory(IntegrationFlowContext flowContext) {
        this.flowContext = flowContext;
    }

    /**
     * A registered flow, and the two handles a connector's {@code stop()} needs.
     *
     * @param input where the source's records go in
     * @param store the aggregator's group store, kept so a stopping connector can force the
     *     partial batch out
     * @param id the registration id, which is the connector's name
     */
    public record ConnectorFlow(MessageChannel input, SimpleMessageStore store, String id) {

        /**
         * Release whatever is buffered, now, on the calling thread.
         *
         * <p>{@code expireMessageGroups(0)} expires every group older than zero milliseconds,
         * and the aggregator registered itself as the store's expiry callback, so this is a
         * synchronous force-release: by the time it returns the batch has been through the
         * publisher. That is what makes a clean shutdown lose nothing that was already read.
         */
        public void release() {
            store.expireMessageGroups(0);
        }
    }

    /**
     * Build and register the flow for one connector.
     *
     * @param connector the connector configuration
     * @param pipeline the compiled record pipeline
     * @param batchPublisher where released batches go
     * @return the registered flow
     */
    public ConnectorFlow register(
            ConnectorProperties connector,
            RecordPipeline pipeline,
            BatchPublisher batchPublisher) {
        String name = connector.getName();
        DirectChannel input = new DirectChannel();
        SimpleMessageStore store = new SimpleMessageStore();
        long flushInterval = Math.max(1L, connector.getAmps().getBatch().getFlushInterval().toMillis());
        int maxMessages = connector.getAmps().getBatch().getMaxMessages();

        IntegrationFlow flow = IntegrationFlow.from(input)
                .handle(SourceRecord.class, (record, headers) -> pipeline.apply(record))
                .aggregate(aggregator -> aggregator
                        // One group per connector: this flow only ever carries its own records,
                        // so the correlation key is a constant and the group is "the batch".
                        .correlationStrategy(message -> name)
                        .releaseStrategy(group -> group.size() >= maxMessages)
                        // An ABSOLUTE deadline, not the DSL's plain groupTimeout(millis): that one
                        // is re-armed by every message added, i.e. an idle timeout, so a feed that
                        // never pauses for flush-interval would only ever release on size and a
                        // slow-but-steady stream could sit for max-messages x inter-arrival. A Date
                        // is honoured as-is by the aggregator, so the batch goes out flush-interval
                        // after its FIRST record whatever arrives in between.
                        .groupTimeout(group -> new Date(group.getTimestamp() + flushInterval))
                        .sendPartialResultOnExpiry(true)
                        .expireGroupsUponCompletion(true)
                        .expireGroupsUponTimeout(true)
                        .messageStore(store)
                        .outputProcessor(group -> {
                            List<Object> batch = new ArrayList<>(group.size());
                            group.getMessages().forEach(message -> batch.add(message.getPayload()));
                            return batch;
                        }))
                .handle(List.class, (batch, headers) -> {
                    batchPublisher.publish(requests(batch));
                    return null;
                })
                .get();

        flowContext.registration(flow).id(name).register();
        return new ConnectorFlow(input, store, name);
    }

    /**
     * Remove a connector's flow, stopping its endpoints and its group-timeout scheduling.
     *
     * @param flow the registration returned by {@link #register}
     */
    public void unregister(ConnectorFlow flow) {
        if (flowContext.getRegistrationById(flow.id()) != null) {
            flowContext.remove(flow.id());
        }
    }

    /** The aggregated payloads; only this flow writes into the group, so the cast is safe. */
    @SuppressWarnings("unchecked")
    private static List<PublishRequest> requests(List<?> batch) {
        return (List<PublishRequest>) batch;
    }
}
