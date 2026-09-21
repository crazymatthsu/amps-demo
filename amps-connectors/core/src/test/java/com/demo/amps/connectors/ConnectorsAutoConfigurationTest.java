package com.demo.amps.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.AlertManager;
import com.demo.amps.connectors.alert.AmpsAlertSink;
import com.demo.amps.connectors.alert.RecordingAlertSink;
import com.demo.amps.connectors.codec.PayloadCodec;
import com.demo.amps.connectors.codec.PayloadCodecRegistry;
import com.demo.amps.connectors.codec.TestPojoCodec;
import com.demo.amps.connectors.config.AlertProperties;
import com.demo.amps.connectors.config.ResourceProperties;
import com.demo.amps.connectors.control.CommandContext;
import com.demo.amps.connectors.control.CommandDispatcher;
import com.demo.amps.connectors.control.CommandHandler;
import com.demo.amps.connectors.control.ControlCommand;
import com.demo.amps.connectors.control.StatusCommand;
import com.demo.amps.connectors.resource.AppResource;
import com.demo.amps.connectors.resource.FakeResource;
import com.demo.amps.connectors.resource.ResourceFactory;
import com.demo.amps.connectors.resource.ResourceRegistry;
import com.demo.amps.connectors.runtime.ConnectorManager;
import com.demo.amps.connectors.source.FakeRecordSource;
import com.demo.amps.connectors.source.FakeSourceFactory;
import com.demo.amps.connectors.source.InboundRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/**
 * The bean graph the auto-configuration builds for the resources, the alerts and the control
 * channel, with the connectors themselves left to {@code ConnectorFlowTest}.
 */
class ConnectorsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConnectorsAutoConfiguration.class))
            // Nothing listens on port 1, so a sink that dials it is refused at once rather
            // than finding a real AMPS on 9007.
            .withPropertyValues("amps-connectors.amps.port=1");

    @Configuration(proxyBeanMethods = false)
    static class AppResources {

        @Bean
        FakeResource rics() {
            return new FakeResource("rics");
        }

        @Bean
        RecordingAlertSink recordingAlertSink() {
            return new RecordingAlertSink();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AppCodecs {

        @Bean
        PayloadCodec testPojoCodec() {
            return new TestPojoCodec();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class JdbcFactory {

        @Bean
        ResourceFactory jdbcResourceFactory() {
            return new ResourceFactory() {
                @Override
                public boolean supports(ResourceProperties resource) {
                    return resource.getJdbc() != null;
                }

                @Override
                public AppResource create(
                        ResourceProperties resource, com.demo.amps.connectors.alert.Alerts alerts) {
                    return new FakeResource(resource.getName());
                }
            };
        }
    }

    /** A control source the test can push commands into, and a handler of the app's own. */
    @Configuration(proxyBeanMethods = false)
    static class Control {

        private final FakeRecordSource source = new FakeRecordSource();
        private final List<ControlCommand> flushed = new ArrayList<>();

        @Bean
        FakeSourceFactory controlSourceFactory() {
            return new FakeSourceFactory(source, connector -> connector.getName().endsWith("-control"));
        }

        @Bean
        CommandHandler flushHandler() {
            return new CommandHandler() {
                @Override
                public String command() {
                    return "flush";
                }

                @Override
                public void handle(ControlCommand command, CommandContext context) {
                    flushed.add(command);
                }
            };
        }
    }

    private static final String[] CONTROL = {
            "spring.application.name=instrument-enricher",
            "amps-connectors.control.enabled=true",
            "amps-connectors.control.source.amps.topic=connectors/control"
    };

    private static final String[] INSTRUMENTS = {
            "amps-connectors.resources[0].name=instruments",
            "amps-connectors.resources[0].jdbc.url=jdbc:h2:mem:refdata",
            "amps-connectors.resources[0].jdbc.query=SELECT symbol FROM instruments",
            "amps-connectors.resources[0].jdbc.key-columns[0]=symbol"
    };

    @Test
    @DisplayName("by default alerts are on and log-only, and there are no resources")
    void defaultsToLogOnlyAlertsAndNoResources() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AlertManager.class);
            assertThat(context).doesNotHaveBean(AmpsAlertSink.class);
            AlertManager alerts = context.getBean(AlertManager.class);
            assertThat(alerts.isRunning()).isTrue();
            assertThat(alerts.sinkNames()).isEmpty();
            assertThat(alerts.application()).isEqualTo(AlertProperties.DEFAULT_APPLICATION);
            ResourceRegistry resources = context.getBean(ResourceRegistry.class);
            assertThat(resources.names()).isEmpty();
            assertThat(resources.isRunning()).isTrue();
            // ...and no codecs: every feed is text until an application says otherwise.
            assertThat(context).hasSingleBean(PayloadCodecRegistry.class);
            assertThat(context.getBean(PayloadCodecRegistry.class).types()).isEmpty();
            ConnectorManager manager = context.getBean(ConnectorManager.class);
            assertThat(manager.isRunning()).isTrue();
            assertThat(manager.resources()).isSameAs(resources);
        });
    }

    @Test
    @DisplayName("a PayloadCodec bean lands in the registry every connector's pipeline is built with")
    void collectsTheApplicationsCodecs() {
        runner.withUserConfiguration(AppCodecs.class).run(context -> {
            PayloadCodecRegistry codecs = context.getBean(PayloadCodecRegistry.class);
            assertThat(codecs.types()).containsExactly(TestPojoCodec.TYPE);
            assertThat(codecs.canDecode(TestPojoCodec.TYPE)).isTrue();
            assertThat(codecs.canEncode(TestPojoCodec.TYPE)).isTrue();
            assertThat(context.getBean(ConnectorManager.class).isRunning()).isTrue();
        });
    }

    @Test
    @DisplayName("the application name comes from alerts.application, else spring.application.name")
    void resolvesTheApplicationName() {
        runner.withPropertyValues("spring.application.name=instrument-enricher")
                .run(context -> assertThat(context.getBean(AlertManager.class).application())
                        .isEqualTo("instrument-enricher"));
        runner.withPropertyValues("spring.application.name=instrument-enricher",
                        "amps-connectors.alerts.application=named-explicitly")
                .run(context -> assertThat(context.getBean(AlertManager.class).application())
                        .isEqualTo("named-explicitly"));

        MockEnvironment environment = new MockEnvironment();
        AlertProperties properties = new AlertProperties();
        assertThat(properties.applicationName(environment)).isEqualTo("amps-connector");
        assertThat(properties.applicationName(null)).isEqualTo("amps-connector");
        environment.setProperty("spring.application.name", " from-spring ");
        assertThat(properties.applicationName(environment)).isEqualTo("from-spring");
        properties.setApplication("mine");
        assertThat(properties.applicationName(environment)).isEqualTo("mine");
    }

    @Test
    @DisplayName("alerts.amps.topic is what puts the AMPS sink in the context")
    void ampsSinkIsConditionalOnItsTopic() {
        // The sink's start dials port 1, is refused, gives up after about logon-timeout and
        // says so; the context still starts, because alerts never hold an application up.
        runner.withPropertyValues("amps-connectors.alerts.amps.topic=connectors/alerts",
                        "amps-connectors.amps.logon-timeout=200ms",
                        "amps-connectors.amps.reconnect-delay=50ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(AmpsAlertSink.class);
                    AlertManager alerts = context.getBean(AlertManager.class);
                    assertThat(alerts.sinkNames()).containsExactly("amps:connectors/alerts");
                    assertThat(alerts.isRunning()).isTrue();
                    assertThat(alerts.sinkFailures()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("every AppResource bean and every AlertSink bean is collected")
    void collectsResourceAndSinkBeans() {
        runner.withUserConfiguration(AppResources.class).run(context -> {
            ResourceRegistry resources = context.getBean(ResourceRegistry.class);
            assertThat(resources.names()).containsExactly("rics");
            assertThat(resources.lookup("rics", FakeResource.class).startCount()).isEqualTo(1);

            AlertManager alerts = context.getBean(AlertManager.class);
            assertThat(alerts.sinkNames()).containsExactly("recording");
            alerts.raise(Alert.of(Alert.Severity.WARN, "X", "m"));
            RecordingAlertSink sink = context.getBean(RecordingAlertSink.class);
            assertThat(sink.awaitCode("X").application()).isEqualTo("amps-connector");
        });
    }

    @Test
    @DisplayName("a resources: entry is built by the factory that claims it, after the beans")
    void buildsConfiguredResourcesWithFactories() {
        runner.withUserConfiguration(AppResources.class, JdbcFactory.class)
                .withPropertyValues(INSTRUMENTS)
                .run(context -> {
                    ResourceRegistry resources = context.getBean(ResourceRegistry.class);
                    assertThat(resources.names()).containsExactly("rics", "instruments");
                    assertThat(resources.lookup("instruments", FakeResource.class).isAvailable())
                            .isTrue();
                });
    }

    @Test
    @DisplayName("a disabled entry is neither built nor started")
    void skipsDisabledResources() {
        runner.withUserConfiguration(JdbcFactory.class)
                .withPropertyValues(INSTRUMENTS)
                .withPropertyValues("amps-connectors.resources[0].enabled=false")
                .run(context -> assertThat(context.getBean(ResourceRegistry.class).names())
                        .isEmpty());
    }

    @Test
    @DisplayName("the dispatcher is always a bean, idle unless control.enabled says otherwise")
    void controlIsOffByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CommandDispatcher.class);
            CommandDispatcher dispatcher = context.getBean(CommandDispatcher.class);
            assertThat(dispatcher.isRunning()).isFalse();
            assertThat(dispatcher.status()).isEqualTo("control: disabled");
            assertThat(dispatcher.commands()).containsExactlyInAnyOrder("reload", "status");
        });
    }

    @Test
    @DisplayName("enabled control listens on its source, named after the application, and dispatches")
    void controlListensAndDispatches() {
        runner.withUserConfiguration(AppResources.class, Control.class)
                .withPropertyValues(CONTROL)
                .run(context -> {
                    CommandDispatcher dispatcher = context.getBean(CommandDispatcher.class);
                    assertThat(dispatcher.isRunning()).isTrue();
                    assertThat(dispatcher.target()).isEqualTo("instrument-enricher");
                    assertThat(dispatcher.connector().getName())
                            .isEqualTo("instrument-enricher-control");
                    assertThat(dispatcher.commands())
                            .containsExactlyInAnyOrder("reload", "status", "flush");
                    // Started after the connectors: the phase order is what makes a command
                    // that arrives at boot find something to act on.
                    assertThat(dispatcher.getPhase())
                            .isGreaterThan(context.getBean(ConnectorManager.class).getPhase());

                    Control control = context.getBean(Control.class);
                    assertThat(control.source.startCount()).isEqualTo(1);
                    control.source.emit(InboundRecord.of(
                            "{\"command\":\"flush\",\"to\":\"instrument-enricher\"}"));
                    control.source.emit(InboundRecord.of("{\"command\":\"status\"}"));
                    assertThat(control.flushed).hasSize(1);
                    assertThat(dispatcher.succeeded()).isEqualTo(2);

                    Alert status = context.getBean(RecordingAlertSink.class)
                            .awaitCode(StatusCommand.STATUS);
                    assertThat(status.application()).isEqualTo("instrument-enricher");
                    assertThat(status.details()).containsKeys("connectors", "resources");
                    assertThat(String.valueOf(status.details().get("resources"))).contains("rics");
                });
    }

    @Test
    @DisplayName("an invalid control: block stops the application with the readable list")
    void failsOnInvalidControl() {
        runner.withPropertyValues("amps-connectors.control.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid amps-connectors configuration")
                    .hasMessageContaining("control: source needs exactly one");
        });
    }

    @Test
    @DisplayName("an entry no factory claims names the module to add")
    void failsWhenNoFactoryClaimsAResource() {
        runner.withPropertyValues(INSTRUMENTS).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resource 'instruments' (jdbc)")
                    .hasMessageContaining(":amps-connectors:resource-jdbc");
        });
    }

    @Test
    @DisplayName("an invalid resources: or alerts: block stops the application with the readable list")
    void failsOnInvalidConfiguration() {
        runner.withUserConfiguration(JdbcFactory.class)
                .withPropertyValues(INSTRUMENTS)
                .withPropertyValues("amps-connectors.resources[0].jdbc.key-columns=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("invalid amps-connectors configuration")
                            .hasMessageContaining("jdbc.key-columns is required");
                });
        runner.withPropertyValues("amps-connectors.alerts.kafka.topic=connectors.alerts")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("alerts.kafka.topic needs");
                });
    }
}
