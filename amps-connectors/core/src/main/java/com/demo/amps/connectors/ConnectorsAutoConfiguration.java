package com.demo.amps.connectors;

import com.demo.amps.connectors.amps.AmpsPublisherFactory;
import com.demo.amps.connectors.amps.HaAmpsPublisher;
import com.demo.amps.connectors.config.ConnectorsProperties;
import com.demo.amps.connectors.runtime.ConnectorFlowFactory;
import com.demo.amps.connectors.runtime.ConnectorManager;
import com.demo.amps.connectors.source.SourceFactory;
import com.demo.amps.connectors.source.SourceResolver;
import com.demo.amps.connectors.transform.RecordTransform;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.context.IntegrationFlowContext;

/**
 * Registers the whole source -&gt; AMPS pipeline in any Spring Boot application that has this
 * library on its classpath (via {@code META-INF/spring/...AutoConfiguration.imports}).
 *
 * <p>The framework's beans are contributed here explicitly rather than component-scanned, so
 * an application's own {@code @SpringBootApplication} scan stays confined to its own package:
 * a connector application supplies a main class and {@code amps-connectors:} configuration,
 * and nothing else. That is what makes one generic runner image deployable as fifty different
 * applications.
 *
 * <p>The two extension points are collected rather than enumerated -- every
 * {@link SourceFactory} on the classpath (each source module auto-configures its own) and
 * every {@link RecordTransform} bean the application declares. Both collections are
 * legitimately empty: an application with no source module can still run simulated connectors,
 * and transforms are the exception rather than the rule.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, which is how a test replaces the AMPS
 * client with a recording one and drives the whole flow -- channels, aggregator, timers,
 * acknowledgments -- without an AMPS anywhere.
 */
@AutoConfiguration
@EnableConfigurationProperties(ConnectorsProperties.class)
@EnableIntegration
public class ConnectorsAutoConfiguration {

    /** Source of timestamps; a bean so a test can pin it. */
    @Bean
    @ConditionalOnMissingBean
    public Clock ampsConnectorsClock() {
        return Clock.systemUTC();
    }

    /**
     * The driver lookup, over whichever source modules are on the classpath.
     *
     * @param factories every contributed factory; empty when only the simulator is wanted
     * @return the resolver every connector asks for its source
     */
    @Bean
    @ConditionalOnMissingBean
    public SourceResolver sourceResolver(ObjectProvider<SourceFactory> factories) {
        return new SourceResolver(factories.orderedStream().toList());
    }

    /**
     * The transforms a connector's {@code transforms:} list can name, by bean name.
     *
     * <p>Asked of the context rather than injected as a {@code Map}, so that the usual case --
     * no custom transforms anywhere -- is an empty registry instead of an unsatisfied
     * dependency.
     *
     * @param context the application context, scanned for {@link RecordTransform} beans
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean
    public TransformRegistry transformRegistry(ApplicationContext context) {
        return new TransformRegistry(context.getBeansOfType(RecordTransform.class));
    }

    /**
     * The real AMPS client for each connector.
     *
     * @param properties the bound configuration, for the shared server block
     * @return a factory building one {@link HaAmpsPublisher} per connector
     */
    @Bean
    @ConditionalOnMissingBean
    public AmpsPublisherFactory ampsPublisherFactory(ConnectorsProperties properties) {
        return connector -> new HaAmpsPublisher(
                properties.getAmps(), connector.getName(), connector.getAmps().getMessageType());
    }

    /**
     * Builds and registers each connector's Spring Integration flow.
     *
     * @param flowContext the runtime flow registry
     * @return the factory
     */
    @Bean
    @ConditionalOnMissingBean
    public ConnectorFlowFactory connectorFlowFactory(IntegrationFlowContext flowContext) {
        return new ConnectorFlowFactory(flowContext);
    }

    /**
     * The lifecycle bean that validates the configuration and runs the connectors.
     *
     * @param properties the bound configuration
     * @param transforms the application's transform beans
     * @param publishers builds each connector's AMPS client
     * @param sources resolves each connector's source
     * @param flows registers each connector's flow
     * @return the manager
     */
    @Bean
    @ConditionalOnMissingBean
    public ConnectorManager connectorManager(
            ConnectorsProperties properties,
            TransformRegistry transforms,
            AmpsPublisherFactory publishers,
            SourceResolver sources,
            ConnectorFlowFactory flows) {
        return new ConnectorManager(properties, transforms, publishers, sources, flows);
    }
}
