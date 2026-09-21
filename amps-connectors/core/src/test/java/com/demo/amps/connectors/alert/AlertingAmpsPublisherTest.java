package com.demo.amps.connectors.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crankuptheamps.client.Message;
import com.demo.amps.connectors.amps.AmpsPublisher;
import com.demo.amps.connectors.amps.PublishListener;
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
        // The sequence the publisher underneath answers with comes back through the wrapper.
        assertThat(publisher.publish("t", "{}", "k")).isEqualTo(1);
        assertThat(publisher.deltaPublish("t", "{}", "k")).isEqualTo(2);
        assertThat(publisher.sowDeleteByKey("t", "k")).isEqualTo(3);
        assertThat(publisher.sowDeleteByFilter("t", "/id = 'k'")).isEqualTo(4);

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
            public long publish(String topic, Object data, String sowKey) {
                throw new IllegalStateException("publish to " + topic + " failed");
            }

            @Override
            public long sowDeleteByFilter(String topic, String filter) {
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

    @Test
    @DisplayName("a write the server refuses is an ERROR with its sequence and reason; a duplicate is not")
    void aRejectedWriteIsAnErrorAndADuplicateIsNot() {
        List<String> heard = new ArrayList<>();
        publisher.setPublishListener(new PublishListener() {
            @Override
            public void persistedUpTo(long seqno) {
                heard.add("persisted " + seqno);
            }

            @Override
            public void failedWrite(long seqno, int reason) {
                heard.add("failed " + seqno + " reason " + reason);
            }
        });
        // The wrapper's listener sits on the publisher underneath, wrapping the test's.
        assertThat(recording.listener()).isNotNull();

        recording.persistUpTo(41);
        recording.failWrite(42, Message.Reason.BadSowKey);
        recording.failWrite(43, Message.Reason.Duplicate);

        assertThat(heard).as("everything reaches the listener, in order")
                .containsExactly("persisted 41", "failed 42 reason " + Message.Reason.BadSowKey,
                        "failed 43 reason " + Message.Reason.Duplicate);
        assertThat(raised).hasSize(1);
        Alert alert = raised.get(0);
        assertThat(alert.code()).isEqualTo(AlertingAmpsPublisher.PUBLISH_REJECTED);
        assertThat(alert.severity()).isEqualTo(Alert.Severity.ERROR);
        assertThat(alert.connector()).isEqualTo("orders");
        assertThat(alert.details())
                .containsEntry("seqno", 42L)
                .containsEntry("reason", Message.Reason.BadSowKey)
                .containsEntry("reasonText", "bad sow key");
        assertThat(alert.message()).contains("42").contains("bad sow key").contains("not retried");

        // Clearing the listener clears the one underneath too.
        publisher.setPublishListener(null);
        assertThat(recording.listener()).isNull();
    }
}
