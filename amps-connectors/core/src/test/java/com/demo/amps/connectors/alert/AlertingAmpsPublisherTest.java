package com.demo.amps.connectors.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.amps.AmpsPublisher;
import com.demo.amps.connectors.amps.RecordingAmpsPublisher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertingAmpsPublisherTest {

    private final List<Alert> raised = new ArrayList<>();
    private final RecordingAmpsPublisher recording = new RecordingAmpsPublisher();
    private final AlertingAmpsPublisher publisher =
            new AlertingAmpsPublisher(recording, "orders", raised::add);

    @Test
    @DisplayName("a flush that completes raises nothing and delegates everything")
    void delegatesQuietly() throws Exception {
        publisher.connect();
        publisher.publish("t", "{}", "k");
        publisher.deltaPublish("t", "{}", "k");
        publisher.sowDeleteByKey("t", "k");
        publisher.sowDeleteByFilter("t", "/id = 'k'");

        assertThat(publisher.flush(Duration.ofSeconds(1))).isTrue();
        assertThat(publisher.isConnected()).isTrue();
        assertThat(recording.calls()).extracting(RecordingAmpsPublisher.Call::kind)
                .containsExactly("publish", "delta_publish", "sow_delete_by_key",
                        "sow_delete_by_filter");
        assertThat(raised).isEmpty();
        assertThat(publisher.delegate()).isSameAs(recording);

        publisher.close();
        assertThat(recording.isConnected()).isFalse();
    }

    @Test
    @DisplayName("a flush that does not complete is a WARN with the connector and the timeout")
    void aFailedFlushIsAWarning() {
        recording.failFlushes(1);

        assertThat(publisher.flush(Duration.ofSeconds(3))).isFalse();
        assertThat(publisher.flush(Duration.ofSeconds(3))).isTrue();

        assertThat(raised).hasSize(1);
        Alert alert = raised.get(0);
        assertThat(alert.code()).isEqualTo(AlertingAmpsPublisher.PUBLISH_FLUSH_TIMEOUT);
        assertThat(alert.severity()).isEqualTo(Alert.Severity.WARN);
        assertThat(alert.connector()).isEqualTo("orders");
        assertThat(alert.details()).containsEntry("timeout", "PT3S");
        assertThat(alert.message()).contains("will be re-read");
    }

    @Test
    @DisplayName("a publish that throws is an ERROR, and the exception still reaches the batch")
    void aThrowingPublishIsAnErrorAndRethrown() {
        AmpsPublisher throwing = new RecordingAmpsPublisher() {
            @Override
            public void publish(String topic, String data, String sowKey) {
                throw new IllegalStateException("publish to " + topic + " failed");
            }

            @Override
            public void sowDeleteByFilter(String topic, String filter) {
                throw new IllegalStateException("sow delete on " + topic + " failed");
            }
        };
        AlertingAmpsPublisher alerting = new AlertingAmpsPublisher(throwing, "orders", raised::add);

        assertThatThrownBy(() -> alerting.publish("sow/orders", "{}", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish to sow/orders failed");
        assertThatThrownBy(() -> alerting.sowDeleteByFilter("sow/orders", "/id = '1'"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(raised).hasSize(2);
        Alert alert = raised.get(0);
        assertThat(alert.code()).isEqualTo(AlertingAmpsPublisher.PUBLISH_FAILED);
        assertThat(alert.severity()).isEqualTo(Alert.Severity.ERROR);
        assertThat(alert.connector()).isEqualTo("orders");
        assertThat(alert.details())
                .containsEntry("operation", "publish")
                .containsEntry("topic", "sow/orders")
                .containsEntry("error", "java.lang.IllegalStateException: publish to sow/orders failed");
        assertThat(raised.get(1).details()).containsEntry("operation", "sow_delete");
    }
}
