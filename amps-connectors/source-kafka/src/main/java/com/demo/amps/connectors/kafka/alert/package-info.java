/**
 * The Kafka alert sink: the framework's one Kafka <em>producer</em>, living in the module
 * named for its consumer.
 *
 * <p>{@code :amps-connectors:source-kafka} is named for the {@code SourceFactory} it
 * contributes, and that is still its job; this package is the other direction over the same
 * client. {@code KafkaAlertSink} implements {@link com.demo.amps.connectors.alert.AlertSink}
 * over the Apache Kafka producer -- every alert as JSON, keyed by its code -- and
 * {@code KafkaAlertAutoConfiguration} registers it when
 * {@code amps-connectors.alerts.kafka.topic} names a topic.
 *
 * <p>It is here rather than in a module of its own for one reason: an application should
 * carry a single Kafka client dependency. {@code kafka-clients} is one jar with both halves
 * in it, the version is the Boot BOM's either way, and a separate {@code alert-kafka} module
 * would make an application that reads Kafka and alerts to Kafka -- the common case --
 * declare the same client twice to get one copy of it. Core cannot hold it, because core
 * knows no transport. So the producer lives beside the consumer, and the rule for the
 * module is "everything that speaks Kafka", not "everything that reads it".
 *
 * <p>What it sends is decided elsewhere: the shape is {@code AlertJson}'s, the same object
 * the AMPS sink publishes, and the settings it binds are
 * {@link com.demo.amps.connectors.config.AlertProperties.Kafka}.
 */
package com.demo.amps.connectors.kafka.alert;
