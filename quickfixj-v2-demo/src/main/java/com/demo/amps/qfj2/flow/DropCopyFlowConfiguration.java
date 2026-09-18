package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.amps.AmpsPublisher;
import com.demo.amps.qfj2.config.QfjProperties;
import com.demo.amps.qfj2.engine.FixHeaders;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.ExecutorChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.messaging.MessageChannel;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * The Spring Integration flow:
 *
 * <pre>
 *   FIX engine --> fixInboundChannel --> rules (in order) --> destinations (all that match)
 * </pre>
 *
 * <p>Two handlers on one flow, both fed from the channel the engine's
 * {@code Application} sends on. The channel type is the one real choice
 * here -- see {@link QfjProperties.ChannelMode}.
 *
 * <p>Not wired under the {@code seqno-admin} profile: the admin tool runs no
 * engine, and an AMPS destination would open the fix connection for nothing.
 */
@Configuration
@Profile("!seqno-admin")
public class DropCopyFlowConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DropCopyFlowConfiguration.class);

    @Bean
    public MessageChannel fixInboundChannel(QfjProperties properties) {
        return switch (properties.flow().channel()) {
            case DIRECT -> new DirectChannel();
            case EXECUTOR -> new ExecutorChannel(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "qfj2-flow");
                thread.setDaemon(false);
                return thread;
            }));
        };
    }

    /** The configured rules, then any {@link EnrichmentRule} beans. */
    @Bean
    public RuleChain ruleChain(QfjProperties properties, ObjectProvider<EnrichmentRule> customRules) {
        List<EnrichmentRule> rules = new ArrayList<>(RuleFactory.createAll(properties.flow().rules()));
        rules.addAll(customRules.orderedStream().toList());
        RuleChain chain = new RuleChain(rules);
        log.info("rules: {}", chain.describe());
        return chain;
    }

    /** The configured destinations, then any {@link Destination} beans. */
    @Bean
    public DestinationDispatcher destinationDispatcher(QfjProperties properties, SessionSettings settings,
                                                       ObjectProvider<AmpsPublisher> ampsFixPublisher,
                                                       ObjectProvider<Destination> customDestinations) {
        Set<SessionID> known = new HashSet<>();
        for (Iterator<SessionID> it = settings.sectionIterator(); it.hasNext();) {
            known.add(it.next());
        }
        List<Destination> destinations = new ArrayList<>(DestinationFactory.createAll(
                properties.flow().destinations(), ampsFixPublisher::getObject,
                properties.amps().flushEach(), known));
        destinations.addAll(customDestinations.orderedStream().toList());
        DestinationDispatcher dispatcher = new DestinationDispatcher(destinations);
        log.info("destinations: {}", dispatcher.describe());
        return dispatcher;
    }

    @Bean
    public IntegrationFlow dropCopyFlow(MessageChannel fixInboundChannel, RuleChain ruleChain,
                                        DestinationDispatcher destinationDispatcher) {
        return IntegrationFlow.from(fixInboundChannel)
                .handle(quickfix.Message.class, (message, headers) -> {
                    ruleChain.apply(message, FixHeaders.context(headers));
                    return message;
                })
                .handle(quickfix.Message.class, (message, headers) -> {
                    destinationDispatcher.dispatch(message, FixHeaders.context(headers));
                    return null;
                })
                .get();
    }
}
