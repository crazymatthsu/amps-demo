package com.demo.amps.common.fix;

import com.demo.amps.market.v1.OrderEvent;
import com.demo.amps.market.v1.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic venue: scripted FIX 4.2 order lifecycles covering the five
 * message types (35=D, G, F, 8, 9) and the endings that matter.
 *
 * <p>Four scenarios, each a chain with its own symbol and OrderID:
 *
 * <ol>
 *   <li><b>replace-and-reject</b> (AAPL, ORD-7) -- the full workout: new, ack,
 *       partial fill, cancel/replace with a fill crossing the pending window,
 *       replace ack, cancel attempt rejected by 35=9, a PossDup resend that a
 *       correct consumer must drop, and a final fill to completion.</li>
 *   <li><b>cancelled</b> (MSFT, ORD-8) -- new, ack, partial fill, cancel
 *       request, pending-cancel report, terminal cancel with leaves zero.</li>
 *   <li><b>rejected</b> (GOOG, ORD-9) -- new order rejected outright.</li>
 *   <li><b>plain-fill</b> (TSLA, ORD-10) -- new, ack, two partials, filled;
 *       the boring path that dominates real flow.</li>
 * </ol>
 *
 * <p>Every event gets a unique id and a monotonic sequence number, so the same
 * script can be published to a keyed events SOW, replayed through
 * {@link FixOrderStateMachine}, or encoded to raw FIX/NVFIX by {@link FixWire}
 * -- the demos do all three.
 */
public final class OrderLifecycleSimulator {

    private final List<OrderEvent> events = new ArrayList<>();
    private int seq;

    private OrderLifecycleSimulator() {
    }

    /** Scenario 1 alone -- what the {@code fix-lifecycle} demo walks through. */
    public static List<OrderEvent> primaryScenario() {
        OrderLifecycleSimulator sim = new OrderLifecycleSimulator();
        sim.replaceAndRejectScenario();
        return List.copyOf(sim.events);
    }

    /** All four scenarios, continuously numbered. */
    public static List<OrderEvent> fullScript() {
        OrderLifecycleSimulator sim = new OrderLifecycleSimulator();
        sim.replaceAndRejectScenario();
        sim.cancelledScenario();
        sim.rejectedScenario();
        sim.plainFillScenario();
        return List.copyOf(sim.events);
    }

    // ---- scenario 1: replace, reject, PossDup, filled ----------------------

    private void replaceAndRejectScenario() {
        String symbol = "AAPL";
        String orderId = "ORD-7";
        // 1. New order: buy 500 @ 100.05.
        add(base("D", "A1", symbol).setOrderQty(500).setPrice(100.05));
        // 2. Venue ack.
        add(base("8", "A1", symbol).setOrderId(orderId).setExecId("EXEC-ACK-A")
                .setOrdStatus("0").setExecType("0").setOrderQty(500).setPrice(100.05)
                .setLeavesQty(500));
        // 3. Partial fill 200 @ 100.00.
        add(fill("A1", symbol, orderId, "EXEC-A1", "1", 200, 300, 100.00, 200, 100.00));
        // 4. Cancel/replace: B1 asks for 800 @ 100.10.
        add(base("G", "B1", symbol).setOrigClOrdId("A1").setOrderQty(800).setPrice(100.10));
        // 5. A fill crosses the pending replace: +100 @ 100.06.
        add(fill("A1", symbol, orderId, "EXEC-A2", "1", 300, 200, 100.02, 100, 100.06));
        // 6. Replace accepted: 11=B1, 41=A1, new terms 800 @ 100.10.
        add(base("8", "B1", symbol).setOrigClOrdId("A1").setOrderId(orderId)
                .setExecId("EXEC-A3").setOrdStatus("5").setExecType("5")
                .setOrderQty(800).setPrice(100.10)
                .setCumQty(300).setLeavesQty(500).setAvgPx(100.02));
        // 7. Cancel attempt C1...
        add(base("F", "C1", symbol).setOrigClOrdId("B1"));
        // 8. ...rejected; tag 39 on the 35=9 states what the order still is.
        add(base("9", "C1", symbol).setOrigClOrdId("B1").setOrdStatus("1")
                .setCxlRejResponseTo("1").setText("too late to cancel"));
        // 9. PossDup resend of EXEC-A2: same ExecID, must not double-apply.
        add(fill("A1", symbol, orderId, "EXEC-A2", "1", 300, 200, 100.02, 100, 100.06));
        // 10. Final fill to completion; venue-computed AvgPx.
        add(fill("B1", symbol, orderId, "EXEC-A4", "2", 800, 0, 100.04, 500, 100.05));
    }

    // ---- scenario 2: cancelled --------------------------------------------

    private void cancelledScenario() {
        String symbol = "MSFT";
        String orderId = "ORD-8";
        add(base("D", "M1", symbol).setOrderQty(1000).setPrice(415.50));
        add(base("8", "M1", symbol).setOrderId(orderId).setExecId("EXEC-ACK-M")
                .setOrdStatus("0").setExecType("0").setOrderQty(1000).setPrice(415.50)
                .setLeavesQty(1000));
        add(fill("M1", symbol, orderId, "EXEC-M1", "1", 400, 600, 415.45, 400, 415.45));
        add(base("F", "M2", symbol).setOrigClOrdId("M1"));
        // Pending-cancel report, then the terminal cancel with leaves zero.
        add(base("8", "M2", symbol).setOrigClOrdId("M1").setOrderId(orderId)
                .setExecId("EXEC-M2").setOrdStatus("6").setExecType("6")
                .setOrderQty(1000).setPrice(415.50)
                .setCumQty(400).setLeavesQty(600).setAvgPx(415.45));
        add(base("8", "M2", symbol).setOrigClOrdId("M1").setOrderId(orderId)
                .setExecId("EXEC-M3").setOrdStatus("4").setExecType("4")
                .setOrderQty(1000).setPrice(415.50)
                .setCumQty(400).setLeavesQty(0).setAvgPx(415.45));
    }

    // ---- scenario 3: rejected ---------------------------------------------

    private void rejectedScenario() {
        String symbol = "GOOG";
        add(base("D", "G1", symbol).setOrderQty(200).setPrice(180.00));
        add(base("8", "G1", symbol).setOrderId("ORD-9").setExecId("EXEC-G1")
                .setOrdStatus("8").setExecType("8").setOrderQty(200).setPrice(180.00)
                .setText("unknown account"));
    }

    // ---- scenario 4: plain fill -------------------------------------------

    private void plainFillScenario() {
        String symbol = "TSLA";
        String orderId = "ORD-10";
        add(base("D", "T1", symbol).setOrderQty(300).setPrice(250.20));
        add(base("8", "T1", symbol).setOrderId(orderId).setExecId("EXEC-ACK-T")
                .setOrdStatus("0").setExecType("0").setOrderQty(300).setPrice(250.20)
                .setLeavesQty(300));
        add(fill("T1", symbol, orderId, "EXEC-T1", "1", 100, 200, 250.10, 100, 250.10));
        add(fill("T1", symbol, orderId, "EXEC-T2", "1", 250, 50, 250.14, 150, 250.16));
        add(fill("T1", symbol, orderId, "EXEC-T3", "2", 300, 0, 250.15, 50, 250.20));
    }

    // ---- builders ----------------------------------------------------------

    private void add(OrderEvent.Builder event) {
        events.add(event.build());
    }

    private OrderEvent.Builder base(String msgType, String clOrdId, String symbol) {
        seq++;
        return OrderEvent.newBuilder()
                .setEventId("EVT-" + String.format("%03d", seq))
                .setSeq(seq)
                .setMsgType(msgType)
                .setClOrdId(clOrdId)
                .setSymbol(symbol)
                .setSide(Side.SIDE_BUY);
    }

    private OrderEvent.Builder fill(String clOrdId, String symbol, String orderId, String execId,
                                    String ordStatus, int cumQty, int leavesQty, double avgPx,
                                    int lastShares, double lastPx) {
        return base("8", clOrdId, symbol)
                .setOrderId(orderId)
                .setExecId(execId)
                .setOrdStatus(ordStatus)
                .setExecType(ordStatus)
                .setExecTransType("0")
                .setCumQty(cumQty)
                .setLeavesQty(leavesQty)
                .setAvgPx(avgPx)
                .setLastShares(lastShares)
                .setLastPx(lastPx);
    }
}
