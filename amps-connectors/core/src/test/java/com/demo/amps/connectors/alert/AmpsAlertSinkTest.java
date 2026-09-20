package com.demo.amps.connectors.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.ConnectionRefusedException;
import com.demo.amps.connectors.amps.AmpsPublisher;
import com.demo.amps.connectors.amps.RecordingAmpsPublisher;
import com.demo.amps.connectors.config.AmpsServerProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmpsAlertSinkTest {

    private final AmpsServerProperties server = new AmpsServerProperties();
    private final RecordingAmpsPublisher recording = new RecordingAmpsPublisher();
    private final AmpsAlertSink sink = new AmpsAlertSink(server, "connectors/alerts", recording);

    private static Alert alert() {
        return Alert.of(Alert.Severity.WARN, "X", "m").withConnector("orders")
                .withTimestamp(Instant.parse("2026-09-19T14:00:00Z"))
                .withApplication("app");
    }

    /** A publisher whose connect fails a set number of times before letting the recording one through. */
    private static final class Refusing implements AmpsPublisher {

        private final RecordingAmpsPublisher recording = new RecordingAmpsPublisher();
        private final AtomicInteger attempts = new AtomicInteger();
        private final int refusals;

        Refusing(int refusals) {
            this.refusals = refusals;
        }

        @Override
        public void connect() throws AMPSException {
            if (attempts.incrementAndGet() <= refusals) {
                throw new ConnectionRefusedException("nobody home");
            }
            recording.connect();
        }

        @Override
        public boolean isConnected() {
            return recording.isConnected();
        }

        @Override
        public void publish(String topic, String data, String sowKey) {
            recording.publish(topic, data, sowKey);
        }

        @Override
        public void deltaPublish(String topic, String data, String sowKey) {
            recording.deltaPublish(topic, data, sowKey);
        }

        @Override
        public void sowDeleteByKey(String topic, String sowKey) {
            recording.sowDeleteByKey(topic, sowKey);
        }

        @Override
        public void sowDeleteByFilter(String topic, String filter) {
            recording.sowDeleteByFilter(topic, filter);
        }

        @Override
        public boolean flush(Duration timeout) {
            return recording.flush(timeout);
        }

        @Override
        public void close() {
            recording.close();
        }
    }

    @Test
    @DisplayName("an alert is published as JSON onto the topic, with no SowKey, and flush waits for it")
    void publishesJson() throws Exception {
        sink.start();
        sink.start();
        sink.send(alert());
        sink.flush();

        assertThat(sink.name()).isEqualTo("amps:connectors/alerts");
        assertThat(recording.isConnected()).isTrue();
        assertThat(recording.calls()).hasSize(1);
        RecordingAmpsPublisher.Call call = recording.calls().get(0);
        assertThat(call.kind()).isEqualTo("publish");
        assertThat(call.topic()).isEqualTo("connectors/alerts");
        assertThat(call.sowKeyOrFilter()).isNull();
        assertThat(call.data()).isEqualTo(AlertJson.write(alert()));
        assertThat(recording.flushCount()).isEqualTo(1);

        sink.close();
        assertThat(recording.isConnected()).isFalse();
    }

    @Test
    @DisplayName("a flush that does not complete is a failure the manager counts")
    void aFailedFlushThrows() throws Exception {
        sink.start();
        recording.failFlushes(1);
        assertThatThrownBy(sink::flush)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connectors/alerts")
                .hasMessageContaining(server.getFlushTimeout().toString());
    }

    @Test
    @DisplayName("AMPS being down at start is the manager's to count: the sink waits reconnect-delay, then connects on an alert")
    void connectsLazilyAfterAFailedStart() {
        server.setReconnectDelay(Duration.ofMillis(100));
        Refusing refusing = new Refusing(1);
        AmpsAlertSink lazy = new AmpsAlertSink(server, "connectors/alerts", refusing);

        assertThatThrownBy(lazy::start).isInstanceOf(ConnectionRefusedException.class);
        assertThat(refusing.isConnected()).isFalse();
        assertThat(refusing.attempts.get()).isEqualTo(1);

        // Inside reconnect-delay a send fails fast, without dialling again...
        assertThatThrownBy(() -> lazy.send(alert()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("next attempt");
        assertThat(refusing.attempts.get()).isEqualTo(1);

        // ...and once it has passed, the next alert connects and goes out.
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> lazy.send(alert()));
        assertThat(refusing.isConnected()).isTrue();
        assertThat(refusing.recording.calls()).hasSize(1);
        assertThat(refusing.attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the AMPS exception from a lazy connect is the one the manager counts, and the next attempt is deferred")
    void rethrowsAmpsExceptions() {
        Refusing refusing = new Refusing(Integer.MAX_VALUE);
        AmpsAlertSink lazy = new AmpsAlertSink(server, "connectors/alerts", refusing);

        assertThatThrownBy(() -> lazy.send(alert())).isInstanceOf(ConnectionRefusedException.class);
        assertThatThrownBy(() -> lazy.send(alert()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("next attempt");
        assertThat(refusing.attempts.get()).isEqualTo(1);
    }
}
