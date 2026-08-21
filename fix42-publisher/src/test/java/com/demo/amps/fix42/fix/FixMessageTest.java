package com.demo.amps.fix42.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixMessageTest {

    @Test
    @DisplayName("renders SOH-separated tag=value pairs in the order they were set")
    void rendersInOrder() {
        FixMessage message = FixMessage.ofType("D")
                .set(FixTags.CL_ORD_ID, "C1")
                .set(FixTags.SYMBOL, "AAPL")
                .set(FixTags.ORDER_QTY, 1000)
                .build();

        assertThat(message.printable()).isEqualTo("35=D|11=C1|55=AAPL|38=1000|");
        assertThat(message.render()).contains(String.valueOf((char) FixMessage.SOH));
    }

    @Test
    @DisplayName("parse is the inverse of render")
    void roundTrips() {
        FixMessage original = FixMessage.ofType("8")
                .set(FixTags.ORDER_ID, "ORD-1")
                .set(FixTags.EXEC_ID, "EXEC-1")
                .setDecimal(FixTags.AVG_PX, 185.4567)
                .build();

        FixMessage parsed = FixMessage.parse(original.render());

        assertThat(parsed.asMap()).isEqualTo(original.asMap());
        assertThat(parsed.msgType()).isEqualTo("8");
    }

    @Test
    @DisplayName("select keeps only the requested tags, in the order requested")
    void selectsSubset() {
        FixMessage full = FixMessage.ofType("G")
                .set(FixTags.CL_ORD_ID, "C2")
                .set(FixTags.ORIG_CL_ORD_ID, "C1")
                .set(FixTags.SYMBOL, "AAPL")
                .set(FixTags.ORDER_QTY, 1500)
                .setDecimal(FixTags.PRICE, 50.25)
                .build();

        FixMessage delta = full.select(List.of(FixTags.MSG_TYPE, FixTags.CL_ORD_ID,
                FixTags.ORIG_CL_ORD_ID, FixTags.ORDER_QTY));

        assertThat(delta.printable()).isEqualTo("35=G|11=C2|41=C1|38=1500|");
        assertThat(delta.has(FixTags.SYMBOL)).isFalse();
    }

    @Test
    @DisplayName("select skips tags the message does not carry, rather than failing")
    void selectSkipsAbsentTags() {
        // A rule names the fields a message MAY carry. The first message of a
        // chain has no OrigClOrdID, and that is not an error.
        FixMessage first = FixMessage.ofType("D").set(FixTags.CL_ORD_ID, "C1").build();

        FixMessage delta = first.select(List.of(FixTags.MSG_TYPE, FixTags.CL_ORD_ID,
                FixTags.ORIG_CL_ORD_ID));

        assertThat(delta.printable()).isEqualTo("35=D|11=C1|");
    }

    @Test
    @DisplayName("setting a tag to null or empty removes it")
    void emptyValuesAreOmitted() {
        FixMessage message = FixMessage.ofType("F")
                .set(FixTags.CL_ORD_ID, "C1")
                .set(FixTags.ORIG_CL_ORD_ID, "")
                .set(FixTags.TEXT, (String) null)
                .build();

        assertThat(message.printable()).isEqualTo("35=F|11=C1|");
    }
}
