package com.demo.amps.fix42.state;

import com.crankuptheamps.client.HAClient;
import com.demo.amps.fix42.state.amps.AmpsPublishSink;
import com.demo.amps.fix42.state.amps.MissResolver;
import com.demo.amps.fix42.state.amps.SowRecordSource;
import com.demo.amps.fix42.state.config.OrderStateProperties;
import com.demo.amps.fix42.state.hooks.DeskStatusHook;
import com.demo.amps.fix42.state.hooks.RejectStatusAtRequestTimeHook;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.RecordWriter;
import com.demo.amps.fix42.state.publish.AuditRouter;
import com.demo.amps.fix42.state.publish.OrderStatePublisher;
import com.demo.amps.fix42.state.publish.RepairQueue;
import com.demo.amps.fix42.state.recovery.SowCacheLoader;
import com.demo.amps.fix42.state.spi.OrderStateHook;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * FIX 4.2 order-state publisher: the stateful successor to
 * {@code fix42-publisher}.
 *
 * <pre>
 *   export AMPS_IMAGE=localhost/amps-demo:5.3.5.135
 *   AMPS_FLOW=fix42-order-state ./server/scripts/amps.sh start
 *   ./gradlew :fix42-order-state-publisher:bootRun
 * </pre>
 *
 * <p>Publishes the scripted flow and the edge-case scenarios into the
 * {@code fix42-order-state} server flow under the {@code demo} profile, and
 * exits. The order blotter is keyed by the publisher (tag 13011) from a small
 * cache per order, so a message is applied conditionally: two cancels, one
 * confirmed and one rejected, leave the order canceled whichever answer
 * arrives first.
 *
 * <p>The Spring shell around a core that knows neither Spring nor AMPS: the
 * bindings map onto the core's plain options, and the {@code amps} package's
 * adapters fill the core's seams (docs/07 §3).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Fix42OrderStatePublisherApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Fix42OrderStatePublisherApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        // A finite flow, and the AMPS client runs non-daemon threads: exit
        // explicitly, closing the context (and the client) first.
        System.exit(SpringApplication.exit(application.run(args)));
    }

    /** Refuses to start on a configuration that would fail later. */
    @Bean
    public InitializingBean validateOrderStateProperties(OrderStateProperties properties) {
        return properties::validate;
    }

    @Bean
    public OrderStateMachine orderStateMachine(OrderStateProperties properties) {
        OrderStateMachine machine = new OrderStateMachine(properties.machineOptions());
        machine.setTerminalRetention(properties.orderState().terminalRetention(), System::currentTimeMillis);
        return machine;
    }

    @Bean
    public RecordWriter recordWriter(OrderStateProperties properties) {
        return new RecordWriter(properties.machineOptions());
    }

    @Bean
    public AuditRouter auditRouter(OrderStateProperties properties) {
        return new AuditRouter(properties.routingOptions());
    }

    /** The shipped example hook; on in the demo profile, off unless asked for elsewhere. */
    @Bean
    @ConditionalOnProperty(name = "fix42-order-state.hooks.desk-status", havingValue = "true")
    public DeskStatusHook deskStatusHook() {
        return new DeskStatusHook();
    }

    /** For a venue whose rejects carry the request-time status (docs/11 F2); off unless asked for. */
    @Bean
    @ConditionalOnProperty(name = "fix42-order-state.hooks.reject-status-at-request-time", havingValue = "true")
    public RejectStatusAtRequestTimeHook rejectStatusAtRequestTimeHook() {
        return new RejectStatusAtRequestTimeHook();
    }

    /**
     * The publisher, with its cache rebuilt from the SOW before the first
     * message (docs/04 §2) and every hook bean in {@code @Order} sequence
     * (docs/07 §2).
     */
    @Bean(destroyMethod = "close")
    public OrderStatePublisher orderStatePublisher(OrderStateMachine machine, RecordWriter writer,
                                                   AuditRouter router, HAClient client, RepairQueue repairs,
                                                   ObjectProvider<OrderStateHook> hooks,
                                                   OrderStateProperties properties) throws Exception {
        String blotter = properties.orderState().topic();
        long timeoutMs = properties.amps().timeoutMs();
        if (properties.orderState().missLookup()) {
            machine.setMissLookup(new MissResolver(client, new MissResolver.Options(blotter, timeoutMs,
                    properties.orderState().missLookupByOrderId())));
        }
        new SowCacheLoader(properties.recoveryOptions()).load(new SowRecordSource(client, blotter, timeoutMs), machine);
        OrderStatePublisher publisher = new OrderStatePublisher(machine, writer, router, new AmpsPublishSink(client),
                repairs, hooks.orderedStream().toList(), properties.publisherOptions());
        publisher.startIdleDrain(Duration.ofSeconds(1));
        return publisher;
    }
}
