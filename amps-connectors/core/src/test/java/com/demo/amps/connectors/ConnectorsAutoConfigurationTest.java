package com.demo.amps.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.AlertManager;
import com.demo.amps.connectors.alert.AmpsAlertSink;
import com.demo.amps.connectors.alert.RecordingAlertSink;
import com.demo.amps.connectors.config.AlertProperties;
import com.demo.amps.connectors.config.ResourceProperties;
import com.demo.amps.connectors.resource.AppResource;
import com.demo.amps.connectors.resource.FakeResource;
import com.demo.amps.connectors.resource.ResourceFactory;
import com.demo.amps.connectors.resource.ResourceRegistry;
import com.demo.amps.connectors.runtime.ConnectorManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/**
 * The bean graph the auto-configuration builds for the resources and the alerts, with the
 * connectors themselves left to {@code ConnectorFlowTest}.
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
            ConnectorManager manager = context.getBean(ConnectorManager.class);
            assertThat(manager.isRunning()).isTrue();
            assertThat(manager.resources()).isSameAs(resources);
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
