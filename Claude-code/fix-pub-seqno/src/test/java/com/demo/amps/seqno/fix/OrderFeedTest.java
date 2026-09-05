package com.demo.amps.seqno.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderFeedTest {

    private static final Instant BASE = Instant.parse("2026-09-05T00:00:00Z");

    @Test
    void producesBusinessFieldsButNotIdentityOrSequence() {
        FixMessage order = new OrderFeed("PUB-A", 1, BASE).next();
        assertEquals("D", order.value(FixTags.MSG_TYPE));
        assertTrue(order.has(FixTags.SYMBOL));
        assertTrue(order.has(FixTags.TRANSACT_TIME));
        // The publisher stamps these; the feed must not.
        assertFalse(order.has(FixTags.SENDER_COMP_ID));
        assertFalse(order.has(FixTags.SENDER_SEQ_NUM));
    }

    @Test
    void resumesFromTheGivenStartIndex() {
        List<FixMessage> first = new OrderFeed("PUB-A", 1, BASE).next(3);
        assertEquals("PUB-A-000001", first.get(0).value(FixTags.CL_ORD_ID));
        assertEquals("PUB-A-000003", first.get(2).value(FixTags.CL_ORD_ID));

        // A feed resumed at 4 continues the series rather than repeating it.
        FixMessage resumed = new OrderFeed("PUB-A", 4, BASE).next();
        assertEquals("PUB-A-000004", resumed.value(FixTags.CL_ORD_ID));
    }

    @Test
    void isDeterministic() {
        assertEquals(
                new OrderFeed("PUB-A", 1, BASE).next(5).stream().map(FixMessage::printable).toList(),
                new OrderFeed("PUB-A", 1, BASE).next(5).stream().map(FixMessage::printable).toList());
    }
}
