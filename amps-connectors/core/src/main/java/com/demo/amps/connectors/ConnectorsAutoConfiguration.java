package com.demo.amps.connectors;

import com.demo.amps.connectors.alert.AlertManager;
import com.demo.amps.connectors.alert.AlertSink;
import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.alert.AmpsAlertSink;
import com.demo.amps.connectors.amps.AmpsPublisherFactory;
import com.demo.amps.connectors.amps.HaAmpsPublisher;
import com.demo.amps.connectors.config.AlertProperties;
import com.demo.amps.connectors.config.ConnectorValidator;
import com.demo.amps.connectors.config.ConnectorsProperties;
import com.demo.amps.connectors.config.ResourceProperties;
import com.demo.amps.connectors.control.CommandContext;
import com.demo.amps.connectors.control.CommandDispatcher;
import com.demo.amps.connectors.control.CommandHandler;
import com.demo.amps.connectors.resource.AppResource;
import com.demo.amps.connectors.resource.ResourceFactory;
import com.demo.amps.connectors.resource.ResourceRegistry;
import com.demo.amps.connectors.runtime.ConnectorFlowFactory;
import com.demo.amps.connectors.runtime.ConnectorManager;
import com.demo.amps.connectors.source.SourceFactory;
import com.demo.amps.connectors.source.SourceResolver;
import com.demo.amps.connectors.transform.RecordTransform;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
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
 * <p>The extension points are collected rather than enumerated -- every
 * {@link SourceFactory} and {@link ResourceFactory} on the classpath (each module
 * auto-configures its own), every {@link RecordTransform}, {@link AppResource},
 * {@link AlertSink} and {@link CommandHandler} bean the application declares. All of them
 * are legitimately empty: an application with no source module can still run simulated
 * connectors, transforms are the exception rather than the rule, alerts with no sink are
 * alerts in the log, and the built-in commands need no handler bean at all.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, which is how a test replaces the AMPS
 * client with a recording one and drives the whole flow -- channels, aggregator, timers,
 * acknowledgments -- without an AMPS anywhere.
 *
 * <p>One rule about dependencies, because the bean graph has a natural direction: the
 * {@link TransformRegistry} is built by instantiating every {@link RecordTransform}, a
 * transform that enriches holds a resource from the {@link ResourceRegistry}, and the
 * registry is built from the resources, the factories and the {@link AlertManager} -- which
 * is built from the sinks. That is a chain, not a cycle, as long as no resource and no sink
 * depends on the transform registry or the connector manager. A resource that wants to raise
 * alerts takes {@link Alerts}, which is earlier in the chain. The {@link CommandDispatcher}
 * is the end of it: it holds the manager and the registry, and nothing holds it -- a
 * {@link CommandHandler} bean that needs the connectors is given them in its
 * {@link CommandContext} at dispatch time, never injected with the dispatcher.
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
     * The application's one {@link Alerts}: every sink bean in the context, behind a queue
     * and repeat suppression. Always present -- with no sink configured it is the log --
     * so that anything taking an {@code Alerts} can be built.
     *
     * <p>The application name defaults to {@code spring.application.name}, and to
     * {@code amps-connector} when that is unset too; the resolution lives in code so the
     * configuration tree never needs a placeholder for it.
     *
     * @param properties the bound configuration, for the {@code alerts:} block
     * @param environment for {@code spring.application.name}
     * @param sinks every contributed sink; empty means log only
     * @param clock stamps the alerts and drives the suppression windows
     * @return the manager
     */
    @Bean
    @ConditionalOnMissingBean
    public AlertManager alertManager(
            ConnectorsProperties properties,
            Environment environment,
            ObjectProvider<AlertSink> sinks,
            Clock clock) {
        requireValid(ConnectorValidator.validateAlerts(properties));
        AlertProperties alerts = properties.getAlerts();
        return new AlertManager(
                alerts, alerts.applicationName(environment), sinks.orderedStream().toList(),
                clock);
    }

    /**
     * The AMPS alerts topic, when {@code alerts.amps.topic} names one.
     *
     * @param properties the bound configuration, for the server block and the topic
     * @param environment for {@code spring.application.name}, half of the client name
     * @return the sink
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "amps-connectors.alerts.amps", name = "topic")
    public AmpsAlertSink ampsAlertSink(ConnectorsProperties properties, Environment environment) {
        AlertProperties alerts = properties.getAlerts();
        return new AmpsAlertSink(
                properties.getAmps(), alerts.applicationName(environment),
                alerts.getAmps().getTopic());
    }

    /**
     * Every shared resource, by name: the {@link AppResource} beans the application declares,
     * then each enabled {@code resources:} entry as built by the first
     * {@link ResourceFactory} that recognises it.
     *
     * <p>An entry no factory claims is a build problem rather than a configuration one -- the
     * block is spelled correctly, its module is just not on the classpath -- and the
     * exception says which module to add, the way {@link SourceResolver} does for a source.
     *
     * @param properties the bound configuration, for the {@code resources:} list
     * @param factories every contributed factory, one per resource module
     * @param resources every resource bean the application declares
     * @param alerts where the resources report what goes wrong
     * @return the registry
     * @throws IllegalStateException if an entry is invalid or nothing can build it
     */
    @Bean
    @ConditionalOnMissingBean
    public ResourceRegistry resourceRegistry(
            ConnectorsProperties properties,
            ObjectProvider<ResourceFactory> factories,
            ObjectProvider<AppResource> resources,
            AlertManager alerts) {
        requireValid(ConnectorValidator.validateResources(properties));
        List<ResourceFactory> available = factories.orderedStream().toList();
        List<AppResource> all = new ArrayList<>(resources.orderedStream().toList());
        for (ResourceProperties resource : properties.enabledResources()) {
            all.add(build(resource, available, alerts));
        }
        return new ResourceRegistry(all, alerts);
    }

    /**
     * The lifecycle bean that validates the configuration and runs the connectors.
     *
     * @param properties the bound configuration
     * @param transforms the application's transform beans
     * @param publishers builds each connector's AMPS client
     * @param sources resolves each connector's source
     * @param flows registers each connector's flow
     * @param resources the application's shared resources
     * @param alerts the application's alerts
     * @return the manager
     */
    @Bean
    @ConditionalOnMissingBean
    public ConnectorManager connectorManager(
            ConnectorsProperties properties,
            TransformRegistry transforms,
            AmpsPublisherFactory publishers,
            SourceResolver sources,
            ConnectorFlowFactory flows,
            ResourceRegistry resources,
            AlertManager alerts) {
        return new ConnectorManager(
                properties, transforms, publishers, sources, flows, resources, alerts);
    }

    /**
     * The control channel, listening when {@code control.enabled} says so and idle
     * otherwise -- always a bean, so an application can ask it for its status either way.
     *
     * <p>The application name is the alerts' ({@code alerts.application}, else
     * {@code spring.application.name}), because it is the same identity: the name the
     * instance answers to in a command's {@code to} is the name its alerts carry, and the
     * synthetic connector the control source is resolved for is {@code <name>-control}.
     *
     * @param properties the bound configuration, for the {@code control:} block
     * @param environment for {@code spring.application.name}
     * @param sources resolves the control source from the modules on the classpath
     * @param resources what {@code reload} reloads
     * @param connectors what {@code status} reports on
     * @param alerts where the commands report
     * @param handlers the application's own command handlers; empty is the usual case
     * @return the dispatcher
     * @throws IllegalStateException if the control block is enabled and invalid
     */
    @Bean
    @ConditionalOnMissingBean
    public CommandDispatcher commandDispatcher(
            ConnectorsProperties properties,
            Environment environment,
            SourceResolver sources,
            ResourceRegistry resources,
            ConnectorManager connectors,
            AlertManager alerts,
            ObjectProvider<CommandHandler> handlers) {
        requireValid(ConnectorValidator.validateControl(properties));
        String application = properties.getAlerts().applicationName(environment);
        return new CommandDispatcher(
                properties.getControl(), application, sources,
                new CommandContext(application, resources, connectors, alerts),
                handlers.orderedStream().toList());
    }

    private static AppResource build(
            ResourceProperties resource, List<ResourceFactory> factories, Alerts alerts) {
        for (ResourceFactory factory : factories) {
            if (factory.supports(resource)) {
                AppResource built = factory.create(resource, alerts);
                if (!resource.getName().equals(built.name())) {
                    throw new IllegalStateException(resource + ": " + factory.getClass().getName()
                            + " built a resource named '" + built.name()
                            + "' -- a factory names its resource after the entry");
                }
                return built;
            }
        }
        Set<String> kinds = resource.configuredKinds();
        throw new IllegalStateException(resource + ": no resource implementation for "
                + String.join("/", kinds) + " -- add a dependency on "
                + ":amps-connectors:resource-" + kinds.iterator().next());
    }

    /** The same readable list {@code ConnectorManager.validate()} throws, only earlier. */
    private static void requireValid(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "invalid amps-connectors configuration:\n  - " + String.join("\n  - ", errors));
        }
    }
}
