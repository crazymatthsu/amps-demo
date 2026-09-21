package com.demo.amps.connectors.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.source.Acknowledger;
import com.demo.amps.connectors.source.InboundRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageContextTest {

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

    private final Recording acks = new Recording();
    private final Object typed = new Object();
    private final InboundRecord in = InboundRecord.of(typed, "K-1")
            .withType(PayloadType.of(100, 1))
            .withSeqno(42)
            .withAttributes(Map.of("topic", "orders"))
            .withAck(acks);
    private final OutboundRecord out = OutboundRecord.publish(
            "sow/orders", Command.DELTA_PUBLISH, "{\"id\":\"K-1\"}", PayloadType.UNSET, "K-1");
    private final MessageContext context = MessageContext.of(in, out);

    @Test
    @DisplayName("the accessors read the two halves: in-side from the record, out-side from the command")
    void readsBothHalves() {
        assertThat(context.in()).isSameAs(in);
        assertThat(context.out()).isSameAs(out);
        assertThat(context.dataIn()).isSameAs(typed);
        assertThat(context.dataInType()).isEqualTo(PayloadType.of(100, 1));
        assertThat(context.dataInFactoryId()).isEqualTo(100);
        assertThat(context.dataInClassId()).isEqualTo(1);
        assertThat(context.dataInSeqno()).isEqualTo(42);
        assertThat(context.dataOut()).isEqualTo("{\"id\":\"K-1\"}");
        assertThat(context.dataOutType()).isEqualTo(PayloadType.UNSET);
        assertThat(context.dataOutFactoryId()).isZero();
        assertThat(context.dataOutClassId()).isZero();
        assertThat(context.dataOutSeqno()).as("nothing published yet").isZero();
        assertThat(context.key()).isEqualTo("K-1");
        assertThat(context.action()).isEqualTo(InboundRecord.Action.UPSERT);
        assertThat(context.attributes()).containsEntry("topic", "orders");
        assertThat(context).hasToString(
                "MessageContext[UPSERT key=K-1 inSeqno=42 inType=100/1 -> DELTA_PUBLISH "
                        + "sow/orders outType=0/0 outSeqno=0]");
    }

    @Test
    @DisplayName("the out-side sequence is written once: 0 -> n, n again is a no-op, another n is an error")
    void outSeqnoIsWriteOnce() {
        context.assignOutSeqno(0);
        assertThat(context.dataOutSeqno()).as("0 means nothing was assigned").isZero();
        context.assignOutSeqno(17);
        assertThat(context.dataOutSeqno()).isEqualTo(17);
        assertThatCode(() -> context.assignOutSeqno(17)).doesNotThrowAnyException();
        assertThat(context.dataOutSeqno()).isEqualTo(17);
        assertThatThrownBy(() -> context.assignOutSeqno(18))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already assigned as 17")
                .hasMessageContaining("18");
        assertThat(context.dataOutSeqno()).isEqualTo(17);
        assertThatThrownBy(() -> context.assignOutSeqno(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(context.toString()).endsWith("outSeqno=17]");
    }

    @Test
    @DisplayName("ack, ack(seqno) and ackBatch go to the in-record's acknowledger, seqno meaning the in-side position")
    void acknowledgmentDelegatesToTheInRecord() {
        context.ack();
        context.ack(50);
        context.ackBatch(40, 50);
        assertThat(acks.calls).containsExactly("ack(42)", "ack(50)", "ackBatch(40,50)");
    }

    @Test
    void bothHalvesAreRequired() {
        assertThatThrownBy(() -> MessageContext.of(null, out))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MessageContext.of(in, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a delete's context has no out-side payload, and the out type is the target's")
    void aDeleteHasNoOutData() {
        MessageContext delete = MessageContext.of(
                in, OutboundRecord.deleteByKey("sow/orders", PayloadType.of(100, 1), "K-1"));
        assertThat(delete.dataOut()).isNull();
        assertThat(delete.out().text()).isEmpty();
        assertThat(delete.dataOutType()).isEqualTo(PayloadType.of(100, 1));
        assertThat(delete.out().command()).isEqualTo(Command.SOW_DELETE);
        MessageContext filtered = MessageContext.of(
                in, OutboundRecord.deleteByFilter("sow/orders", null, "/id = 'K-1'"));
        assertThat(filtered.dataOutType()).isEqualTo(PayloadType.UNSET);
        assertThat(filtered.out().deleteFilter()).isEqualTo("/id = 'K-1'");
    }
}
