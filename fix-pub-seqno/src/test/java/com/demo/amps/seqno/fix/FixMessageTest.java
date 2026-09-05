package com.demo.amps.seqno.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class FixMessageTest {

    @Test
    void roundTripsThroughTheWireFormat() {
        FixMessage original = FixMessage.ofType("D")
                .set(FixTags.SENDER_COMP_ID, "PUB-A")
                .set(FixTags.SENDER_SEQ_NUM, 42)
                .set(FixTags.SYMBOL, "AAPL")
                .set(FixTags.ORDER_QTY, 500)
                .build();

        FixMessage parsed = FixMessage.parse(original.render());

        assertEquals("D", parsed.value(FixTags.MSG_TYPE));
        assertEquals("PUB-A", parsed.value(FixTags.SENDER_COMP_ID));
        assertEquals(42, parsed.longValue(FixTags.SENDER_SEQ_NUM));
        assertEquals("AAPL", parsed.value(FixTags.SYMBOL));
        assertEquals(original, parsed);
    }

    @Test
    void rendersFieldsSeparatedBySoh() {
        String rendered = FixMessage.ofType("D").set(FixTags.SENDER_SEQ_NUM, 1).build().render();
        assertTrue(rendered.indexOf(FixMessage.SOH) >= 0, "fields are SOH-separated");
        assertTrue(rendered.endsWith(String.valueOf((char) FixMessage.SOH)),
                "every field including the last is SOH-terminated");
        assertEquals("35=D|8888=1|", FixMessage.printable(rendered));
    }

    @Test
    void optionalLongIsEmptyForAbsentOrNonNumeric() {
        FixMessage message = FixMessage.ofType("D").set(FixTags.SYMBOL, "AAPL").build();
        assertEquals(OptionalLong.empty(), message.optionalLong(FixTags.SENDER_SEQ_NUM));
        assertEquals(OptionalLong.empty(), message.optionalLong(FixTags.SYMBOL));
        assertFalse(message.has(FixTags.SENDER_SEQ_NUM));
    }

    @Test
    void toBuilderStampsWithoutDisturbingExistingFields() {
        FixMessage business = FixMessage.ofType("D").set(FixTags.SYMBOL, "MSFT").build();
        FixMessage stamped = business.toBuilder()
                .set(FixTags.SENDER_COMP_ID, "PUB-A")
                .set(FixTags.SENDER_SEQ_NUM, 7)
                .build();
        assertEquals("MSFT", stamped.value(FixTags.SYMBOL));
        assertEquals(7, stamped.longValue(FixTags.SENDER_SEQ_NUM));
        // The business message is unchanged: FixMessage is immutable.
        assertFalse(business.has(FixTags.SENDER_SEQ_NUM));
    }
}
