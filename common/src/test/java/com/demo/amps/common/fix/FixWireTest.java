package com.demo.amps.common.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.demo.amps.market.v1.OrderEvent;
import com.demo.amps.market.v1.Side;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixWireTest {

    private static OrderEvent sampleReport() {
        return OrderEvent.newBuilder()
                .setEventId("EVT-042")
                .setSeq(42)
                .setMsgType("8")
                .setClOrdId("A1")
                .setOrigClOrdId("A0")
                .setOrderId("ORD-7")
                .setExecId("EXEC-9")
                .setSymbol("AAPL")
                .setSide(Side.SIDE_BUY)
                .setOrdStatus("1")
                .setExecType("1")
                .setExecTransType("0")
                .setOrderQty(500)
                .setPrice(100.05)
                .setCumQty(200)
                .setLeavesQty(300)
                .setAvgPx(100.0)
                .setLastShares(200)
                .setLastPx(100.0)
                .build();
    }

    @Test
    @DisplayName("FIX payload is SOH-separated tag=value pairs")
    void fixShape() {
        String payload = FixWire.toFix(sampleReport());

        assertTrue(payload.startsWith("35=8\u0001"), FixWire.printable(payload));
        assertTrue(payload.contains("\u000111=A1\u0001"), FixWire.printable(payload));
        assertTrue(payload.contains("\u000139=1\u0001"), FixWire.printable(payload));
        assertTrue(payload.contains("\u00019001=EVT-042\u0001"),
                "the events SOW keys on the custom event-id tag: " + FixWire.printable(payload));
        assertTrue(payload.charAt(payload.length() - 1) == FixWire.SOH,
                "every field ends with SOH, including the last");
    }

    @Test
    @DisplayName("NVFIX carries the same fields by name")
    void nvfixShape() {
        String payload = FixWire.toNvfix(sampleReport());

        assertTrue(payload.startsWith("MsgType=8\u0001"), FixWire.printable(payload));
        assertTrue(payload.contains("\u0001ClOrdID=A1\u0001"), FixWire.printable(payload));
        assertTrue(payload.contains("\u0001OrdStatus=1\u0001"), FixWire.printable(payload));
        assertTrue(payload.contains("\u0001OrderID=ORD-7\u0001"),
                "the orders SOW keys on /OrderID: " + FixWire.printable(payload));
        assertFalse(payload.contains("\u000111="),
                "NVFIX uses names, not tag numbers: " + FixWire.printable(payload));
    }

    @Test
    @DisplayName("round-trips through the official shredder")
    void roundTrips() {
        OrderEvent original = sampleReport();

        Map<Integer, String> tags = FixWire.parseFix(FixWire.toFix(original));
        OrderEvent parsed = FixWire.fromFixMap(tags);

        assertEquals(original.getMsgType(), parsed.getMsgType());
        assertEquals(original.getEventId(), parsed.getEventId());
        assertEquals(original.getClOrdId(), parsed.getClOrdId());
        assertEquals(original.getOrderId(), parsed.getOrderId());
        assertEquals(original.getSide(), parsed.getSide());
        assertEquals(original.getCumQty(), parsed.getCumQty());
        assertEquals(original.getLeavesQty(), parsed.getLeavesQty());
        assertEquals(original.getAvgPx(), parsed.getAvgPx());
        assertEquals(original.getPrice(), parsed.getPrice());
    }

    @Test
    @DisplayName("execution reports always carry the cumulative trio, zeros included")
    void reportCarriesCumulativeTrio() {
        OrderEvent ack = OrderEvent.newBuilder()
                .setEventId("EVT-1").setMsgType("8").setClOrdId("A1")
                .setOrdStatus("0").setOrderQty(500).setPrice(100.0)
                .setLeavesQty(500)
                .build();

        Map<Integer, String> tags = FixWire.parseFix(FixWire.toFix(ack));

        assertEquals("0", tags.get(14), "CumQty=0 on an ack is information, not an omission");
        assertEquals("500", tags.get(151));
        assertEquals("0", tags.get(6));
    }

    @Test
    @DisplayName("requests do not carry execution fields")
    void requestOmitsExecutionFields() {
        OrderEvent request = OrderEvent.newBuilder()
                .setEventId("EVT-1").setMsgType("D").setClOrdId("A1")
                .setSymbol("AAPL").setSide(Side.SIDE_SELL)
                .setOrderQty(500).setPrice(100.05)
                .build();

        Map<Integer, String> tags = FixWire.parseFix(FixWire.toFix(request));

        assertEquals("2", tags.get(54), "sell side maps to 54=2");
        assertFalse(tags.containsKey(14), "no CumQty on a 35=D");
        assertFalse(tags.containsKey(17), "no ExecID on a 35=D");
    }

    @Test
    @DisplayName("prices render as plain decimals, never scientific notation")
    void plainDecimals() {
        OrderEvent event = sampleReport().toBuilder().setPrice(1234567.89).setAvgPx(0.0001).build();

        String payload = FixWire.toFix(event);

        assertTrue(payload.contains("44=1234567.89\u0001"), FixWire.printable(payload));
        assertTrue(payload.contains("6=0.0001\u0001"),
                "no 1.0E-4 on a FIX wire: " + FixWire.printable(payload));
    }

    @Test
    @DisplayName("the tag-name table matches between the two encodings")
    void encodingsAgree() {
        OrderEvent event = sampleReport();

        Map<Integer, String> fix = FixWire.parseFix(FixWire.toFix(event));
        Map<String, String> nvfix = FixWire.parseNvfix(FixWire.toNvfix(event));

        assertEquals(fix.size(), nvfix.size(), "same fields in both encodings");
        fix.forEach((tag, value) ->
                assertEquals(value, nvfix.get(FixWire.nameOf(tag)),
                        "tag " + tag + " and its name must carry the same value"));
    }
}
