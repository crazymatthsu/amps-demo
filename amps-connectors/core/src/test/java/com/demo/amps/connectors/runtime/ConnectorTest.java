package com.demo.amps.connectors.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.AlertingAmpsPublisher;
import com.demo.amps.connectors.amps.RecordingAmpsPublisher;
import com.demo.amps.connectors.config.AmpsServerProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.source.FakeRecordSource;
import com.demo.amps.connectors.source.FakeSourceFactory;
import com.demo.amps.connectors.source.SourceRecord;
import com.demo.amps.connectors.source.SourceResolver;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

/**
 * One connector with a flow factory that hands back a channel of the test's choosing, so the
 * two alert paths that the full flow never exercises can be reached: a record that throws on
 * its way in, and the publisher that arrives already wrapped.
 */
class ConnectorTest {

    private final List<Alert> raised = new ArrayList<>();
    private final RecordingAmpsPublisher recording = new RecordingAmpsPublisher();
    private final FakeRecordSource source = new FakeRecordSource();

    /** A flow whose input channel is {@code channel}; nothing is registered anywhere. */
    private static ConnectorFlowFactory flowsOver(MessageChannel channel) {
        return new ConnectorFlowFactory(null) {
            @Override
            public ConnectorFlow register(
                    ConnectorProperties connector, RecordPipeline pipeline,
                    com.demo.amps.connectors.amps.BatchPublisher batchPublisher) {
                return new ConnectorFlow(
                        channel, new SimpleMessageStore(), null, connector.getName());
            }

            @Override
            public void unregister(ConnectorFlow flow) {
            }
        };
    }

    private Connector connector(MessageChannel channel) {
        return new Connector(
                TestConnectors.tcp("orders", 15009),
                new AmpsServerProperties(),
                new TransformRegistry(Map.of()),
                properties -> recording,
                new SourceResolver(List.of(new FakeSourceFactory(source))),
                flowsOver(channel),
                raised::add);
    }

    @Test
    @DisplayName("a record that throws on the way into the flow is counted and raised as SOURCE_ERROR")
    void raisesSourceErrors() throws Exception {
        MessageChannel refusing = new MessageChannel() {
            @Override
            public boolean send(Message<?> message) {
                throw new IllegalStateException("no subscribers");
            }

            @Override
            public boolean send(Message<?> message, long timeout) {
                return send(message);
            }
        };
        Connector connector = connector(refusing);
        connector.start();

        // The source's thread survives: emit() returns, and the second record is counted too.
        source.emit(SourceRecord.of("{\"id\":\"1\"}"));
        source.emit(SourceRecord.of("{\"id\":\"2\"}"));

        assertThat(connector.sourceErrors()).isEqualTo(2);
        assertThat(raised).hasSize(2);
        Alert alert = raised.get(0);
        assertThat(alert.code()).isEqualTo(Connector.SOURCE_ERROR);
        assertThat(alert.severity()).isEqualTo(Alert.Severity.WARN);
        assertThat(alert.connector()).isEqualTo("orders");
        assertThat(alert.details())
                .containsEntry("error", "java.lang.IllegalStateException: no subscribers")
                .containsEntry("sourceErrors", 1L);
        assertThat(alert.message()).contains("record #1");

        connector.stop();
        assertThat(source.closeCount()).isEqualTo(1);
        assertThat(recording.isConnected()).isFalse();
    }

    @Test
    @DisplayName("the batch publisher writes through an alerting wrapper over the factory's publisher")
    void wrapsThePublisher() throws Exception {
        Connector connector = connector(new MessageChannel() {
            @Override
            public boolean send(Message<?> message) {
                return true;
            }

            @Override
            public boolean send(Message<?> message, long timeout) {
                return true;
            }
        });
        connector.start();
        recording.failFlushes(1);

        SourceRecord record = SourceRecord.of("{\"id\":\"1\"}");
        connector.batchPublisher().publish(List.of(
                PublishRequest.publish("test/orders", Command.PUBLISH, record.data(), null, record)));

        assertThat(recording.calls()).hasSize(1);
        assertThat(connector.batchPublisher().failedBatches()).isEqualTo(1);
        assertThat(raised).extracting(Alert::code)
                .containsExactly(AlertingAmpsPublisher.PUBLISH_FLUSH_TIMEOUT);
        assertThat(raised.get(0).connector()).isEqualTo("orders");
        connector.stop();
    }
}
