package com.demo.amps.connectors.kafka.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.AlertJson;
import com.demo.amps.connectors.config.AlertProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Kafka sink against a {@link MockProducer} -- no broker, no testcontainers.
 *
 * <p>What is worth asserting here is everything the sink decides for itself: what goes on
 * the wire (topic, key, body), what the producer is configured with, and above all what a
 * failed send turns into -- nothing thrown at the time, a warning from the callback, and a
 * counted failure from the next flush. The producer's own behaviour is Kafka's to test.
 */
class KafkaAlertSinkTest {

    private static final String TOPIC = "connectors.alerts";
    private static final String APPLICATION = "instrument-enricher";

    // ---- fixtures ------------------------------------------------------------------

    private static AlertProperties.Kafka kafka() {
        AlertProperties.Kafka kafka = new AlertProperties.Kafka();
        kafka.setBootstrapServers("broker-1:9092");
        kafka.setTopic(TOPIC);
        return kafka;
    }

    /** An alert as the manager hands it to a sink: stamped, attributed, with details. */
    private static Alert alert() {
        return Alert.of(Alert.Severity.WARN, "UNKNOWN_SYMBOL", "symbol XYZ is not in instruments")
                .withConnector("orders-enriched")
                .withDetails(Map.of("symbol", "XYZ"))
                .withTimestamp(Instant.parse("2026-09-19T14:00:00Z"))
                .withApplication(APPLICATION);
    }

    private static MockProducer<String, String> mock(boolean autoComplete) {
        return new MockProducer<>(autoComplete, new StringSerializer(), new StringSerializer());
    }

    private static KafkaAlertSink sink(Producer<String, String> producer) {
        return new KafkaAlertSink(kafka(), APPLICATION, () -> producer);
    }

    // ---- what goes on the wire ---------------------------------------------------------

    @Test
    @DisplayName("an alert is produced to the topic, keyed by its code, as the AlertJson body")
    void producesTheAlertKeyedByCodeAsJson() {
        MockProducer<String, String> producer = mock(true);
        KafkaAlertSink sink = sink(producer);
        sink.start();

        Alert alert = alert();
        sink.send(alert);

        assertThat(producer.history()).hasSize(1);
        ProducerRecord<String, String> record = producer.history().get(0);
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo("UNKNOWN_SYMBOL");
        // The same object the AMPS sink publishes: one shape for both topics.
        assertThat(record.value()).isEqualTo(AlertJson.write(alert));
        assertThat(record.value())
                .startsWith("{\"timestamp\":\"2026-09-19T14:00:00Z\",\"application\":\""
                        + APPLICATION + "\",\"severity\":\"WARN\",\"code\":\"UNKNOWN_SYMBOL\"")
                .contains("\"connector\":\"orders-enriched\"")
                .contains("\"details\":{\"symbol\":\"XYZ\"}");
    }

    @Test
    @DisplayName("the sink is named for its topic")
    void isNamedForItsTopic() {
        assertThat(sink(mock(true)).name()).isEqualTo("kafka:connectors.alerts");
    }

    @Test
    @DisplayName("the producer is configured for an alerts channel, with the passthrough applied last")
    void configuresTheProducerForAnAlertsChannel() {
        AlertProperties.Kafka kafka = kafka();
        kafka.getProperties().put("linger.ms", "20");
        // An operator's override wins, whatever the sink's own opinion.
        kafka.getProperties().put(ProducerConfig.ACKS_CONFIG, "all");

        Map<String, Object> config = new KafkaAlertSink(kafka, APPLICATION).producerConfig();

        assertThat(config).contains(
                entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker-1:9092"),
                entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()),
                entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class.getName()),
                entry(ProducerConfig.CLIENT_ID_CONFIG, "instrument-enricher-alerts"),
                entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000"),
                entry("linger.ms", "20"),
                entry(ProducerConfig.ACKS_CONFIG, "all"));
        assertThat(new KafkaAlertSink(kafka(), APPLICATION).producerConfig())
                .containsEntry(ProducerConfig.ACKS_CONFIG, "1");
    }

    // ---- lifecycle -------------------------------------------------------------------

    @Test
    @DisplayName("flush() waits on the producer, close() closes it, and both are safe to repeat")
    void flushesAndCloses() {
        MockProducer<String, String> producer = mock(true);
        AtomicInteger built = new AtomicInteger();
        KafkaAlertSink sink = new KafkaAlertSink(kafka(), APPLICATION, () -> {
            built.incrementAndGet();
            return producer;
        });

        assertThatCode(sink::flush).as("nothing to flush before start").doesNotThrowAnyException();
        assertThat(built).hasValue(0);

        sink.start();
        sink.send(alert());
        sink.flush();
        assertThat(producer.flushed()).isTrue();
        assertThat(built).as("start builds it once; send reuses it").hasValue(1);

        sink.close();
        assertThat(producer.closed()).isTrue();
        assertThatCode(sink::close).doesNotThrowAnyException();
        assertThatCode(sink::flush).as("nothing left to flush").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a start that fails is not the end: the first send builds the producer instead")
    void buildsTheProducerLazilyWhenStartFailed() {
        MockProducer<String, String> producer = mock(true);
        AtomicInteger attempts = new AtomicInteger();
        KafkaAlertSink sink = new KafkaAlertSink(kafka(), APPLICATION, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new KafkaException("Failed to construct kafka producer");
            }
            return producer;
        });

        // Thrown, so the manager logs and counts it -- and keeps the sink.
        assertThatThrownBy(sink::start).isInstanceOf(KafkaException.class);

        sink.send(alert());
        assertThat(producer.history()).hasSize(1);
        assertThat(attempts).hasValue(2);
    }

    // ---- failure ---------------------------------------------------------------------

    @Test
    @DisplayName("a send the broker fails does not throw; the next flush reports it, once")
    void failedSendIsReportedByTheNextFlush() {
        MockProducer<String, String> producer = mock(false);
        KafkaAlertSink sink = sink(producer);
        sink.start();

        // The delivery thread is not blocked by a failing send: the producer answers later.
        assertThatCode(() -> sink.send(alert())).doesNotThrowAnyException();
        assertThat(producer.errorNext(new TimeoutException("broker away"))).isTrue();

        assertThatThrownBy(sink::flush)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 alert(s)")
                .hasMessageContaining(TOPIC)
                .hasMessageContaining("broker away")
                .hasCauseInstanceOf(TimeoutException.class);

        // Reported once: the next flush starts from zero.
        assertThatCode(sink::flush).doesNotThrowAnyException();

        sink.send(alert());
        assertThat(producer.completeNext()).isTrue();
        assertThatCode(sink::flush).doesNotThrowAnyException();
        assertThat(producer.history()).hasSize(2);
    }

    @Test
    @DisplayName("every failed send since the last flush is counted, not just the last")
    void countsEveryFailedSend() {
        MockProducer<String, String> producer = mock(false);
        KafkaAlertSink sink = sink(producer);
        sink.start();

        List<Alert> alerts = List.of(alert(), alert().withConnector("ticks-tcp"), alert());
        alerts.forEach(sink::send);
        producer.errorNext(new TimeoutException("one"));
        producer.completeNext();
        producer.errorNext(new TimeoutException("three"));

        assertThatThrownBy(sink::flush)
                .hasMessageContaining("2 alert(s)")
                .hasMessageContaining("three");
    }
}
