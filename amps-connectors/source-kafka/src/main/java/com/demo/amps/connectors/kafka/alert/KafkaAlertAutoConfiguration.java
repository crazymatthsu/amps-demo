package com.demo.amps.connectors.kafka.alert;

import com.demo.amps.connectors.config.AlertProperties;
import com.demo.amps.connectors.config.ConnectorsProperties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Contributes the Kafka alert sink to any application that depends on this module and names
 * a topic under {@code amps-connectors.alerts.kafka}.
 *
 * <p>The topic is the switch, the way {@code alerts.amps.topic} switches the AMPS sink on:
 * an application that reads Kafka but wants its alerts on AMPS only sets no Kafka topic and
 * gets no producer. Guarded on the producer client being on the classpath, like the source
 * driver is on the consumer, so a runner shipping every module still starts when the Kafka
 * client has been excluded. The bean is conditional on its <em>name</em> rather than its
 * type so an application can replace it with any {@code AlertSink} it calls
 * {@code kafkaAlertSink} -- a test's recording one included.
 *
 * <p>The application name -- half of the client id -- is resolved the way the alert manager
 * resolves it: {@code alerts.application}, else {@code spring.application.name}, else the
 * default, so the two always agree.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaProducer.class)
@ConditionalOnProperty(prefix = "amps-connectors.alerts.kafka", name = "topic")
public class KafkaAlertAutoConfiguration {

    /**
     * @param properties the bound configuration, for the {@code alerts.kafka} block
     * @param environment for {@code spring.application.name}
     * @return the sink
     */
    @Bean
    @ConditionalOnMissingBean(name = "kafkaAlertSink")
    public KafkaAlertSink kafkaAlertSink(ConnectorsProperties properties, Environment environment) {
        AlertProperties alerts = properties.getAlerts();
        return new KafkaAlertSink(alerts.getKafka(), alerts.applicationName(environment));
    }
}
