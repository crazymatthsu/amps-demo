package com.demo.amps.fix42.state.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixWriterTest {

    @Test
    @DisplayName("writes tag=value<SOH> fields in order and remembers their tags")
    void writesFieldsInOrder() {
        FixWriter writer = new FixWriter(64)
                .field(FixTags.MSG_TYPE, "D")
                .field(FixTags.CL_ORD_ID, "C1")
                .field(FixTags.PARENT_ORDER_ID, "PARENT-1");

        assertThat(writer.asString()).isEqualTo("35=D\u000111=C1\u00019000=PARENT-1\u0001");
        assertThat(writer.printable()).isEqualTo("35=D|11=C1|9000=PARENT-1|");
        assertThat(writer.fieldCount()).isEqualTo(3);
        assertThat(writer.contains(FixTags.CL_ORD_ID)).isTrue();
        assertThat(writer.contains(FixTags.ORIG_CL_ORD_ID)).isFalse();
    }

    @Test
    @DisplayName("a byte value is copied from the given range only")
    void copiesByteRange() {
        byte[] source = "xxAAPLyy".getBytes(StandardCharsets.ISO_8859_1);

        FixWriter writer = new FixWriter(64).field(FixTags.SYMBOL, source, 2, 4);

        assertThat(writer.printable()).isEqualTo("55=AAPL|");
    }

    @Test
    @DisplayName("clear forgets the fields but keeps the buffer")
    void clearKeepsTheBuffer() {
        FixWriter writer = new FixWriter(64).field(FixTags.MSG_TYPE, "8");
        byte[] buffer = writer.buffer();

        writer.clear();

        assertThat(writer.size()).isZero();
        assertThat(writer.fieldCount()).isZero();
        assertThat(writer.contains(FixTags.MSG_TYPE)).isFalse();
        assertThat(writer.buffer()).isSameAs(buffer);
    }

    @Test
    @DisplayName("grows past its initial capacity without losing what was written")
    void growsOnDemand() {
        FixWriter writer = new FixWriter(16).field(FixTags.MSG_TYPE, "D");
        String text = "x".repeat(100);

        writer.field(FixTags.TEXT, text);

        assertThat(writer.printable()).isEqualTo("35=D|58=" + text + "|");
    }

    @Test
    @DisplayName("raw appends a pre-encoded field and counts its tag as written")
    void rawAppendsEncodedField() {
        byte[] literal = FixWriter.encodeField(FixTags.PENDING_ACTION, "NONE");

        FixWriter writer = new FixWriter(64).raw(FixTags.PENDING_ACTION, literal);

        assertThat(writer.printable()).isEqualTo("9013=NONE|");
        assertThat(writer.contains(FixTags.PENDING_ACTION)).isTrue();
    }

    @Test
    @DisplayName("characters outside Latin-1 become '?', as String.getBytes(ISO_8859_1) renders them")
    void nonLatin1BecomesQuestionMark() {
        FixWriter writer = new FixWriter(64).field(FixTags.TEXT, "café €");

        assertThat(writer.asString()).isEqualTo("58=café ?\u0001");
    }

    @Test
    @DisplayName("a negative tag is rejected")
    void rejectsNegativeTags() {
        assertThatThrownBy(() -> new FixWriter(64).field(-1, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("writeTo produces exactly what render does")
    void matchesRender() {
        FixMessage message = FixMessage.ofType("8")
                .set(FixTags.ORDER_ID, "ORD-1")
                .set(FixTags.EXEC_TYPE, "1")
                .setDecimal(FixTags.AVG_PX, 185.4567)
                .build();

        assertThat(message.writeTo(new FixWriter(64)).asString()).isEqualTo(message.render());
    }
}
