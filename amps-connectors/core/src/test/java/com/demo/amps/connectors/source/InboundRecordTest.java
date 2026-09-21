package com.demo.amps.connectors.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.codec.PayloadType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboundRecordTest {

    /** An acknowledger that writes down what it was called with. */
    private static final class Recording implements Acknowledger {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void ack(long seqno) {
            calls.add("ack(" + seqno + ")");
        }

        @Override
        public void ackBatch(long fromSeqno, long toSeqno) {
            calls.add("ackBatch(" + fromSeqno + "," + toSeqno + ")");
        }
    }

    @Test
    @DisplayName("the factories build text records with no position and nothing to acknowledge")
    void factoriesDefaultToTextUnpositionedAndUnacknowledged() {
        InboundRecord record = InboundRecord.of("{\"id\":1}");
        assertThat(record.data()).isEqualTo("{\"id\":1}");
        assertThat(record.type()).isSameAs(PayloadType.UNSET);
        assertThat(record.key()).isNull();
        assertThat(record.action()).isEqualTo(InboundRecord.Action.UPSERT);
        assertThat(record.seqno()).isEqualTo(InboundRecord.NO_SEQNO).isEqualTo(-1L);
        assertThat(record.attributes()).isEmpty();
        assertThat(record.acknowledger()).isSameAs(Acknowledger.NONE);

        assertThat(InboundRecord.of("x", "K-1").key()).isEqualTo("K-1");
        InboundRecord delete = InboundRecord.delete("", "K-1");
        assertThat(delete.action()).isEqualTo(InboundRecord.Action.DELETE);
        assertThat(delete.key()).isEqualTo("K-1");
    }

    @Test
    @DisplayName("null type, attributes and acknowledger become their defaults; a null action is refused")
    void theCanonicalConstructorFillsTheDefaults() {
        InboundRecord record = new InboundRecord(
                "x", null, null, InboundRecord.Action.UPSERT, 5, null, null);
        assertThat(record.type()).isEqualTo(PayloadType.UNSET);
        assertThat(record.attributes()).isEmpty();
        assertThat(record.acknowledger()).isSameAs(Acknowledger.NONE);
        assertThat(record.seqno()).isEqualTo(5);
        assertThatThrownBy(() -> new InboundRecord("x", null, null, null, 0, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("action");
    }

    @Test
    void theWithersKeepEverythingElse() {
        Recording acks = new Recording();
        InboundRecord record = InboundRecord.of("x", "K-1")
                .withType(PayloadType.of(100, 1))
                .withSeqno(42)
                .withAck(acks)
                .withAttributes(Map.of("topic", "orders"));
        assertThat(record.data()).isEqualTo("x");
        assertThat(record.key()).isEqualTo("K-1");
        assertThat(record.type()).isEqualTo(PayloadType.of(100, 1));
        assertThat(record.seqno()).isEqualTo(42);
        assertThat(record.acknowledger()).isSameAs(acks);
        assertThat(record.attributes()).containsExactly(Map.entry("topic", "orders"));

        InboundRecord retyped = record.withType(null);
        assertThat(retyped.type()).isEqualTo(PayloadType.UNSET);
        assertThat(retyped.seqno()).isEqualTo(42);
        assertThat(retyped.acknowledger()).isSameAs(acks);
    }

    @Test
    @DisplayName("attributes are copied, so a source reusing its map cannot edit a record in flight")
    void attributesAreCopied() {
        Map<String, String> reused = new HashMap<>();
        reused.put("offset", "1");
        InboundRecord record = InboundRecord.of("x").withAttributes(reused);
        reused.put("offset", "2");
        assertThat(record.attributes()).containsEntry("offset", "1");
        assertThatThrownBy(() -> record.attributes().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("text() is the payload as text whatever it arrived as; data() is the payload itself")
    void textOfEveryPayloadShape() {
        assertThat(InboundRecord.of("plain").text()).isEqualTo("plain");
        byte[] bytes = "{\"id\":\"é\"}".getBytes(StandardCharsets.UTF_8);
        InboundRecord binary = InboundRecord.of(bytes);
        assertThat(binary.data()).isSameAs(bytes);
        assertThat(binary.text()).isEqualTo("{\"id\":\"é\"}");
        assertThat(InboundRecord.delete(null, "k").text()).isEmpty();
        Object typed = new Object() {
            @Override
            public String toString() {
                return "Order[O-1]";
            }
        };
        InboundRecord object = InboundRecord.of(typed).withType(PayloadType.of(100, 1));
        assertThat(object.data()).isSameAs(typed);
        assertThat(object.text()).isEqualTo("Order[O-1]");
    }

    @Test
    void hasDataMeansNotNullAndNotEmpty() {
        assertThat(InboundRecord.of("x").hasData()).isTrue();
        assertThat(InboundRecord.of("").hasData()).isFalse();
        assertThat(InboundRecord.delete(null, "k").hasData()).isFalse();
        assertThat(InboundRecord.of(new byte[0]).hasData()).isFalse();
        assertThat(InboundRecord.of(new byte[] {1}).hasData()).isTrue();
        assertThat(InboundRecord.of(new Object()).hasData()).isTrue();
    }

    @Test
    @DisplayName("ack() hands the acknowledger the record's own seqno; the explicit forms pass theirs")
    void acknowledgmentGoesToTheStreamWithThePosition() {
        Recording acks = new Recording();
        InboundRecord record = InboundRecord.of("x").withSeqno(7).withAck(acks);
        record.ack();
        record.ack(9);
        record.ackBatch(3, 9);
        assertThat(acks.calls).containsExactly("ack(7)", "ack(9)", "ackBatch(3,9)");

        // NO_SEQNO is what a source that assigned no position is acknowledged with.
        InboundRecord unpositioned = InboundRecord.of("x").withAck(acks);
        unpositioned.ack();
        assertThat(acks.calls).last().isEqualTo("ack(-1)");
    }

    @Test
    @DisplayName("the default ackBatch is the cumulative reading: acknowledge the last position")
    void ackBatchDefaultsToTheLastPosition() {
        List<Long> acked = new ArrayList<>();
        Acknowledger cumulative = acked::add;
        InboundRecord.of("x").withAck(cumulative).ackBatch(10, 20);
        assertThat(acked).containsExactly(20L);
    }

    @Test
    void noneIsSafeToCallAndDoesNothing() {
        InboundRecord record = InboundRecord.of("x");
        record.ack();
        record.ack(1);
        record.ackBatch(1, 2);
        Acknowledger.NONE.ack(3);
        Acknowledger.NONE.ackBatch(1, 3);
    }
}
