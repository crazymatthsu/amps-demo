package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.amps.BatchPublisher;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.source.InboundRecord;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.context.IntegrationFlowContext;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.messaging.MessageChannel;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
 * <p>The flush-interval deadline fires on a thread of the connector's <em>own</em>, never on
 * the application's shared {@code taskScheduler}. A deadline does more than fire a timer: its
 * release runs the whole publish, and a publish ends in a flush that waits for the server's
 * persisted ack -- half a second and more against a real AMPS. Spring Boot builds the shared
 * scheduler with {@code spring.task.scheduling.pool.size} threads, one by default, so there
 * every connector's deadline queues behind whichever connector is mid-flush. And late is not
 * the worst of it: the aggregator re-arms the deadline on every record and drops a timer that
 * fires after its group has changed, so a feed fast enough to add a record during the wait
 * keeps invalidating its own release and only ever goes out by size. Measured on the demo
 * profile: a 40 msg/s feed on a 250ms interval released one batch of ~700 every half-minute.
 *
 * <p>Sizing the shared pool instead would put a number that has to track the connector count
 * into the application's scheduling configuration, where the next connector added quietly
 * brings the bug back while every counter still reads healthy. A thread per connector makes
 * the isolation structural: a deadline can only ever wait on its own connector's publish, and
 * during that publish the source thread is blocked behind the same group lock, so nothing
 * re-arms the timer meanwhile. The cost is one parked thread per connector -- what a correctly
 * sized pool would hold anyway -- and the application's scheduler, with whatever
 * {@code @Scheduled} work it carries, is left alone.
 *
 * <p>Flows are registered through {@link IntegrationFlowContext} under the connector's name
 * rather than declared as beans, because connectors are configuration: an application that
 * mounts a different {@code application.yml} runs different connectors without a different
 * jar. The scheduler thread is created and shut down with the registration for the same
 * reason.
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
     * A registered flow, and the handles a connector's {@code stop()} needs.
     *
     * @param input where the source's records go in
     * @param store the aggregator's group store, kept so a stopping connector can force the
     *     partial batch out
     * @param scheduler the connector's own deadline thread, shut down with the flow
     * @param id the registration id, which is the connector's name
     */
    public record ConnectorFlow(
            MessageChannel input, SimpleMessageStore store, ThreadPoolTaskScheduler scheduler,
            String id) {

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
        ThreadPoolTaskScheduler scheduler = deadlineScheduler(name);
        long flushInterval = Math.max(1L, connector.getAmps().getBatch().getFlushInterval().toMillis());
        int maxMessages = connector.getAmps().getBatch().getMaxMessages();

        IntegrationFlow flow = IntegrationFlow.from(input)
                .handle(InboundRecord.class, (record, headers) -> pipeline.apply(record))
                .aggregate(aggregator -> aggregator
                        // This connector's thread, not the application's shared one: the
                        // deadline release publishes and waits for the ack, and another
                        // connector's wait must never be what delays this one's release.
                        .taskScheduler(scheduler)
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
                    batchPublisher.publish(records(batch));
                    return null;
                })
                .get();

        try {
            flowContext.registration(flow).id(name).register();
        } catch (RuntimeException e) {
            scheduler.shutdown();
            throw e;
        }
        return new ConnectorFlow(input, store, scheduler, name);
    }

    /**
     * Remove a connector's flow, stopping its endpoints and its group-timeout scheduling, and
     * then its deadline thread.
     *
     * <p>In that order: removing the flow destroys the aggregator, which cancels whatever it
     * still has scheduled, so the thread is shut down with nothing left to run on it. A
     * release in flight at this point is not possible either -- {@code Connector.stop()} has
     * already forced the last batch out under the same group lock a timer release holds.
     *
     * @param flow the registration returned by {@link #register}
     */
    public void unregister(ConnectorFlow flow) {
        try {
            if (flowContext.getRegistrationById(flow.id()) != null) {
                flowContext.remove(flow.id());
            }
        } finally {
            flow.scheduler().shutdown();
        }
    }

    /**
     * One thread of the connector's own for the flush-interval deadline.
     *
     * <p>One is enough: the aggregator holds a single group per connector and takes its lock
     * for the whole release, so two deadline threads could never publish concurrently anyway.
     * Daemon, like the manager's own tick thread, so a thread the shutdown order missed can
     * never keep a JVM alive. Cancelled timers are removed from the queue rather than left to
     * expire, because every record re-arms the deadline by cancelling the previous one and a
     * fast feed would otherwise keep flush-interval's worth of dead entries queued.
     */
    private static ThreadPoolTaskScheduler deadlineScheduler(String name) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("amps-connectors-" + name + "-deadline-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    /** The aggregated payloads; only this flow writes into the group, so the cast is safe. */
    @SuppressWarnings("unchecked")
    private static List<OutboundRecord> records(List<?> batch) {
        return (List<OutboundRecord>) batch;
    }
}
