package com.demo.amps.fix42.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixFrameTest {

    private static byte[] bytes(String printable) {
        return printable.replace('|', (char) FixMessage.SOH).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static FixFrame frame(String printable) {
        byte[] payload = bytes(printable);
        return new FixFrame().wrap(payload, 0, payload.length);
    }

    @Test
    @DisplayName("indexes every field in wire order without copying")
    void indexesFieldsInWireOrder() {
        FixFrame frame = frame("35=8|11=C1|150=1|9000=P-1|");

        assertThat(frame.count()).isEqualTo(4);
        assertThat(frame.tagAt(0)).isEqualTo(FixTags.MSG_TYPE);
        assertThat(frame.valueAt(3)).isEqualTo("P-1");
        assertThat(frame.value(FixTags.EXEC_TYPE)).isEqualTo("1");
        assertThat(frame.value(FixTags.ORIG_CL_ORD_ID)).isEmpty();
        assertThat(frame.has(FixTags.CL_ORD_ID)).isTrue();
        assertThat(frame.has(FixTags.ORIG_CL_ORD_ID)).isFalse();
        assertThat(frame.indexOf(FixTags.PARENT_ORDER_ID)).isEqualTo(3);
        assertThat(frame.printable()).isEqualTo("35=8|11=C1|150=1|9000=P-1|");
    }

    @Test
    @DisplayName("a repeated tag reads as its last occurrence, while iteration keeps both")
    void repeatedTagReadsAsLastOccurrence() {
        FixFrame frame = frame("35=8|58=first|58=second|");

        assertThat(frame.indexOf(FixTags.TEXT)).isEqualTo(2);
        assertThat(frame.value(FixTags.TEXT)).isEqualTo("second");
        assertThat(frame.count()).isEqualTo(3);
        // The map form keeps the first position and the last value, as it always has.
        assertThat(FixMessage.of(frame).printable()).isEqualTo("35=8|58=second|");
    }

    @Test
    @DisplayName("valueEquals compares in place; an absent tag equals nothing")
    void valueEqualsComparesInPlace() {
        FixFrame frame = frame("35=8|11=C1|");

        assertThat(frame.valueEquals(FixTags.MSG_TYPE, "8")).isTrue();
        assertThat(frame.valueEquals(FixTags.MSG_TYPE, "88")).isFalse();
        assertThat(frame.valueAtEquals(1, "C1")).isTrue();
        assertThat(frame.valueEquals(FixTags.ORIG_CL_ORD_ID, "")).isFalse();
    }

    @Test
    @DisplayName("copyField writes the field as tag=value, skips absent tags, and can rename")
    void copiesFieldsIntoAWriter() {
        FixFrame frame = frame("35=G|11=C2|38=100|");
        FixWriter out = new FixWriter(64);

        assertThat(frame.copyField(FixTags.CL_ORD_ID, out)).isTrue();
        assertThat(frame.copyField(FixTags.ORIG_CL_ORD_ID, out)).isFalse();
        assertThat(frame.copyFieldAs(FixTags.ORDER_QTY, FixTags.PENDING_ORDER_QTY, out)).isTrue();
        frame.copyFieldAt(0, out);

        assertThat(out.printable()).isEqualTo("11=C2|9010=100|35=G|");
    }

    @Test
    @DisplayName("an unterminated final field runs to the end of the payload")
    void unterminatedFinalFieldRunsToTheEnd() {
        FixFrame frame = frame("35=8|11=C1");

        assertThat(frame.count()).isEqualTo(2);
        assertThat(frame.value(FixTags.CL_ORD_ID)).isEqualTo("C1");
    }

    @Test
    @DisplayName("a malformed field is rejected rather than dropped")
    void rejectsMalformedFields() {
        assertThatThrownBy(() -> frame("35=8|x=1|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte 5");
        assertThatThrownBy(() -> frame("35=8|11|")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> frame("=1|")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("honours the offset and length of a payload inside a larger array")
    void honoursOffsetAndLength() {
        byte[] payload = bytes("35=8|11=C1|");
        byte[] larger = new byte[payload.length + 4];
        System.arraycopy(payload, 0, larger, 2, payload.length);

        FixFrame frame = new FixFrame().wrap(larger, 2, payload.length);

        assertThat(frame.printable()).isEqualTo("35=8|11=C1|");
        assertThat(frame.valueOffsetAt(0)).isEqualTo(5);
        assertThat(frame.valueLengthAt(1)).isEqualTo(2);
        assertThat(frame.buffer()).isSameAs(larger);
    }

    @Test
    @DisplayName("wraps a writer's contents and grows past its initial field capacity")
    void wrapsAWriterAndGrows() {
        FixWriter writer = new FixWriter(2048);
        for (int i = 0; i < 100; i++) {
            writer.field(1000 + i, "v" + i);
        }

        FixFrame frame = new FixFrame().wrap(writer);

        assertThat(frame.count()).isEqualTo(100);
        assertThat(frame.tagAt(99)).isEqualTo(1099);
        assertThat(frame.value(1042)).isEqualTo("v42");
    }

    @Test
    @DisplayName("an empty payload has no fields")
    void emptyPayload() {
        assertThat(frame("").count()).isZero();
    }
}
