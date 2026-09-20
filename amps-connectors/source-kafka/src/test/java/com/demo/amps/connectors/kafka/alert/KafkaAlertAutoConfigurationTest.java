package com.demo.amps.connectors.kafka.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.ConnectorsAutoConfiguration;
import com.demo.amps.connectors.alert.AlertManager;
import com.demo.amps.connectors.alert.AlertSink;
import com.demo.amps.connectors.alert.RecordingAlertSink;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The sink reaches the alert manager through the auto-configuration alone: a topic under
 * {@code alerts.kafka} is all an application writes.
 *
 * <p>Alerts are disabled in every context here so the manager never starts the sink -- a
 * started sink builds a real producer, and this test is about the bean graph, not a broker.
 */
class KafkaAlertAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConnectorsAutoConfiguration.class, KafkaAlertAutoConfiguration.class))
            .withPropertyValues("amps-connectors.alerts.enabled=false");

    private static final String[] KAFKA_ALERTS = {
            "amps-connectors.alerts.kafka.bootstrap-servers=broker-1:9092",
            "amps-connectors.alerts.kafka.topic=connectors.alerts"
    };

    @Configuration(proxyBeanMethods = false)
    static class OwnSink {

        @Bean
        AlertSink kafkaAlertSink() {
            return new RecordingAlertSink("the application's own");
        }
    }

    @Test
    @DisplayName("alerts.kafka.topic is what puts the Kafka sink in the context")
    void sinkIsConditionalOnItsTopic() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(KafkaAlertSink.class);
            assertThat(context.getBean(AlertManager.class).sinkNames()).isEmpty();
        });
        runner.withPropertyValues(KAFKA_ALERTS).run(context -> {
            assertThat(context).hasSingleBean(KafkaAlertSink.class);
            KafkaAlertSink sink = context.getBean(KafkaAlertSink.class);
            assertThat(sink.name()).isEqualTo("kafka:connectors.alerts");
            assertThat(sink.producerConfig())
                    .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker-1:9092");
            assertThat(context.getBean(AlertManager.class).sinkNames())
                    .containsExactly("kafka:connectors.alerts");
        });
    }

    @Test
    @DisplayName("the client id is the alert manager's application name plus -alerts")
    void clientIdFollowsTheApplicationName() {
        runner.withPropertyValues(KAFKA_ALERTS).run(context ->
                assertThat(context.getBean(KafkaAlertSink.class).producerConfig())
                        .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "amps-connector-alerts"));
        runner.withPropertyValues(KAFKA_ALERTS)
                .withPropertyValues("spring.application.name=instrument-enricher")
                .run(context ->
                        assertThat(context.getBean(KafkaAlertSink.class).producerConfig())
                                .containsEntry(ProducerConfig.CLIENT_ID_CONFIG,
                                        "instrument-enricher-alerts"));
        runner.withPropertyValues(KAFKA_ALERTS)
                .withPropertyValues("spring.application.name=instrument-enricher",
                        "amps-connectors.alerts.application=named-explicitly")
                .run(context ->
                        assertThat(context.getBean(KafkaAlertSink.class).producerConfig())
                                .containsEntry(ProducerConfig.CLIENT_ID_CONFIG,
                                        "named-explicitly-alerts"));
    }

    @Test
    @DisplayName("a topic without bootstrap servers is refused at startup, not at the first alert")
    void topicNeedsBootstrapServers() {
        runner.withPropertyValues("amps-connectors.alerts.kafka.topic=connectors.alerts")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("alerts.kafka.bootstrap-servers");
                });
    }

    @Test
    @DisplayName("a bean named kafkaAlertSink replaces the module's, whatever its type")
    void applicationCanReplaceTheSinkByName() {
        runner.withPropertyValues(KAFKA_ALERTS).withUserConfiguration(OwnSink.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(KafkaAlertSink.class);
                    assertThat(context.getBean("kafkaAlertSink"))
                            .isInstanceOf(RecordingAlertSink.class);
                    assertThat(context.getBean(AlertManager.class).sinkNames())
                            .containsExactly("the application's own");
                });
    }
}
