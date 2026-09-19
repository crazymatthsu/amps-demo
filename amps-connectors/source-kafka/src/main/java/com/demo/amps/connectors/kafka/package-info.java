/**
 * The Kafka driver for {@code amps-connectors}.
 *
 * <p>{@code KafkaRecordSource} implements
 * {@link com.demo.amps.connectors.source.RecordSource} over the Apache Kafka consumer,
 * {@code KafkaSourceFactory} claims the connectors that configure {@code source.kafka}, and
 * {@code KafkaSourceAutoConfiguration} registers it.
 *
 * <p>This is the transport that makes the framework's at-least-once contract visible. The
 * consumer runs with {@code enable.auto.commit=false} and commits nothing until the batch
 * publisher says a record reached AMPS: offsets are committed from the poll thread, for
 * acknowledged records only, so a crash between a publish and its flush re-reads rather than
 * loses. A null-valued record is a tombstone and becomes a {@code DELETE}.
 *
 * <p>The rules it obeys live with the configuration it binds
 * ({@link com.demo.amps.connectors.config.KafkaSourceProperties}).
 */
package com.demo.amps.connectors.kafka;
