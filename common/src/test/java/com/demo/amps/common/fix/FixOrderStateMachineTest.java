package com.demo.amps.common.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.fix.FixOrderStateMachine.Outcome;
import com.demo.amps.market.v1.FixOrdStatus;
import com.demo.amps.market.v1.OrderEvent;
import com.demo.amps.market.v1.OrderState;
import com.demo.amps.market.v1.PendingRequest;
import com.demo.amps.market.v1.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The FIX 4.2 lifecycle, event by event. Each test doubles as a worked example
 * for docs/src/fix-order-state.md: the scenario names there match these.
 */
class FixOrderStateMachineTest {

    private final FixOrderStateMachine machine = new FixOrderStateMachine();
    private int seq;

    // ---- scenarios ---------------------------------------------------------

    @Test
    @DisplayName("new order lifecycle: D -> ack -> fills -> filled")
    void newAckFillsFilled() {
        OrderState afterRequest = applied(newOrder("A1", 500, 100.05));
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_PENDING_NEW, afterRequest.getOrdStatus());
        assertEquals(PendingRequest.PENDING_NEW, afterRequest.getPendingRequest());
        assertEquals(500, afterRequest.getPendingOrderQty());
        assertEquals(0, afterRequest.getAckedOrderQty(), "nothing acknowledged yet");

        OrderState afterAck = applied(ack("A1", "ORD-1", 500, 100.05));
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_NEW, afterAck.getOrdStatus());
        assertEquals(PendingRequest.PENDING_NONE, afterAck.getPendingRequest());
        assertEquals(500, afterAck.getAckedOrderQty());
        assertEquals(100.05, afterAck.getAckedPrice());
        assertEquals("ORD-1", afterAck.getOrderId());

        OrderState afterPartial = applied(fill("A1", "E1", "1", 200, 300, 100.00, 200, 100.00));
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_PARTIALLY_FILLED, afterPartial.getOrdStatus());
        assertEquals(200, afterPartial.getCumQty());
        assertEquals(300, afterPartial.getLeavesQty());
        assertEquals(100.00, afterPartial.getAvgPx(), "AvgPx is the venue's tag 6, not computed here");

        OrderState afterFill = applied(fill("A1", "E2", "2", 500, 0, 100.03, 300, 100.05));
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_FILLED, afterFill.getOrdStatus());
        assertEquals(500, afterFill.getCumQty());
        assertEquals(0, afterFill.getLeavesQty());
        assertEquals(100.03, afterFill.getAvgPx());
    }

    @Test
    @DisplayName("cancel/replace: pending qty visible while working, chain id never moves")
    void replaceChain() {
        applied(newOrder("A1", 500, 100.05));
        applied(ack("A1", "ORD-1", 500, 100.05));
        applied(fill("A1", "E1", "1", 200, 300, 100.00, 200, 100.00));

        // 35=G: new ClOrdID B1 asks for 800. Working state is untouched.
        OrderState pending = applied(replaceRequest("B1", "A1", 800, 100.10));
        assertEquals(500, pending.getAckedOrderQty(), "acknowledged qty is still 500");
        assertEquals(PendingRequest.PENDING_REPLACE, pending.getPendingRequest());
        assertEquals(800, pending.getPendingOrderQty(), "pending order qty is the requested 800");
        assertEquals("A1", pending.getChainId());

        // A fill arrives while the replace is in flight: pending survives it.
        OrderState filledWhilePending = applied(fill("A1", "E2", "1", 300, 200, 100.02, 100, 100.06));
        assertEquals(PendingRequest.PENDING_REPLACE, filledWhilePending.getPendingRequest());
        assertEquals(300, filledWhilePending.getCumQty());

        // Replace ack: 11=B1, 41=A1, 38=800. Terms move, chain id does not.
        OrderState replaced = applied(replaceAck("B1", "A1", "ORD-1", 800, 100.10, 300, 500, 100.02));
        assertEquals("A1", replaced.getChainId(), "the SOW key is stable across the replace");
        assertEquals("B1", replaced.getCurrentClOrdId());
        assertEquals(800, replaced.getAckedOrderQty());
        assertEquals(PendingRequest.PENDING_NONE, replaced.getPendingRequest());
        assertEquals(300, replaced.getCumQty(), "cum qty carries across the replace");
        assertEquals(500, replaced.getLeavesQty());
    }

    @Test
    @DisplayName("cancel reject clears the pending request and reports the surviving status")
    void cancelRejectReverts() {
        applied(newOrder("A1", 500, 100.05));
        applied(ack("A1", "ORD-1", 500, 100.05));
        applied(fill("A1", "E1", "1", 200, 300, 100.00, 200, 100.00));
        applied(cancelRequest("C1", "A1"));

        // 35=9 carries OrdStatus (39) as a required tag: the venue says what the
        // order still is, so nothing has to be remembered client-side.
        OrderState state = applied(cancelReject("C1", "A1", "1", "1", "too late to cancel"));
        assertEquals(PendingRequest.PENDING_NONE, state.getPendingRequest());
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_PARTIALLY_FILLED, state.getOrdStatus());
        assertEquals(200, state.getCumQty(), "execution state untouched by the reject");
        assertEquals("too late to cancel", state.getText());
    }

    @Test
    @DisplayName("cancel: pending, then terminal with leaves zero from the report")
    void cancelToTerminal() {
        applied(newOrder("A1", 500, 100.05));
        applied(ack("A1", "ORD-1", 500, 100.05));
        applied(cancelRequest("C1", "A1"));

        OrderState canceled = applied(report("C1", "A1", "E9", "4", "4", 500, 100.05, 0, 0, 0));
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_CANCELED, canceled.getOrdStatus());
        assertEquals(0, canceled.getLeavesQty());
        assertEquals(PendingRequest.PENDING_NONE, canceled.getPendingRequest());

        // Requests against a terminal order are refused by the machine itself.
        Outcome afterTerminal = machine.apply(replaceRequest("D1", "C1", 900, 101.0));
        assertTrue(afterTerminal instanceof Outcome.Ignored);
    }

    @Test
    @DisplayName("duplicate ExecID (PossDup resend) is dropped")
    void duplicateExecIdIgnored() {
        applied(newOrder("A1", 500, 100.05));
        applied(ack("A1", "ORD-1", 500, 100.05));
        applied(fill("A1", "E1", "1", 200, 300, 100.00, 200, 100.00));

        Outcome resend = machine.apply(fill("A1", "E1", "1", 200, 300, 100.00, 200, 100.00));

        assertTrue(resend instanceof Outcome.Ignored);
        assertTrue(((Outcome.Ignored) resend).reason().contains("ExecID"));
        assertEquals(200, machine.state("A1").orElseThrow().getCumQty());
    }

    @Test
    @DisplayName("out-of-order report with lower CumQty is dropped as stale")
    void staleReportIgnored() {
        applied(newOrder("A1", 500, 100.05));
        applied(ack("A1", "ORD-1", 500, 100.05));
        applied(fill("A1", "E1", "1", 300, 200, 100.01, 300, 100.01));

        Outcome stale = machine.apply(fill("A1", "E0", "1", 100, 400, 100.00, 100, 100.00));

        assertTrue(stale instanceof Outcome.Ignored);
        assertTrue(((Outcome.Ignored) stale).reason().contains("stale"));
        assertEquals(300, machine.state("A1").orElseThrow().getCumQty());
    }

    @Test
    @DisplayName("venue reject of a new order is terminal")
    void rejectedNew() {
        applied(newOrder("A1", 500, 100.05));

        OrderState rejected = applied(report("A1", "", "E1", "8", "8", 500, 100.05, 0, 0, 0));

        assertEquals(FixOrdStatus.FIX_ORD_STATUS_REJECTED, rejected.getOrdStatus());
        assertEquals(PendingRequest.PENDING_NONE, rejected.getPendingRequest());
    }

    @Test
    @DisplayName("a report for a new ClOrdID resolves through OrigClOrdID")
    void resolvesThroughOrigClOrdId() {
        applied(newOrder("A1", 500, 100.05));
        applied(ack("A1", "ORD-1", 500, 100.05));

        // Replace ack arrives although we never saw the 35=G (a gap, or a
        // drop-copy feed): 11=B1 is unknown, 41=A1 finds the chain.
        OrderState replaced = applied(replaceAck("B1", "A1", "ORD-1", 800, 100.10, 0, 800, 0));

        assertEquals("A1", replaced.getChainId());
        assertEquals("B1", replaced.getCurrentClOrdId());
        assertEquals(800, replaced.getAckedOrderQty());
    }

    @Test
    @DisplayName("drop-copy: a chain can be created from a report alone")
    void dropCopyCreatesChain() {
        OrderState state = applied(fill("X9", "E1", "1", 100, 400, 50.00, 100, 50.00));

        assertEquals("X9", state.getChainId());
        assertEquals(100, state.getCumQty());
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_PARTIALLY_FILLED, state.getOrdStatus());
    }

    @Test
    @DisplayName("the derived record is SOW-ready: chainId is the JSON key member")
    void stateJsonCarriesTheSowKey() {
        applied(newOrder("A1", 500, 100.05));
        OrderState state = applied(ack("A1", "ORD-1", 500, 100.05));

        String json = JsonCodec.toJson(state);

        assertTrue(json.contains("\"chainId\":\"A1\""),
                "fix.orders declares <Key>/chainId</Key>; the member must match: " + json);
        assertTrue(json.contains("\"ordStatus\":\"FIX_ORD_STATUS_NEW\""),
                "consumers filter on readable names, e.g. /ordStatus = 'FIX_ORD_STATUS_NEW'");
    }

    // ---- event builders ----------------------------------------------------

    private OrderState applied(OrderEvent event) {
        Outcome outcome = machine.apply(event);
        assertTrue(outcome instanceof Outcome.Applied,
                () -> "expected Applied but was " + outcome + " for seq " + event.getSeq());
        return ((Outcome.Applied) outcome).state();
    }

    private OrderEvent.Builder base(String msgType, String clOrdId) {
        return OrderEvent.newBuilder()
                .setEventId("EVT-" + (++seq))
                .setSeq(seq)
                .setMsgType(msgType)
                .setClOrdId(clOrdId)
                .setSymbol("AAPL")
                .setSide(Side.SIDE_BUY);
    }

    private OrderEvent newOrder(String clOrdId, int qty, double price) {
        return base("D", clOrdId).setOrderQty(qty).setPrice(price).build();
    }

    private OrderEvent replaceRequest(String clOrdId, String origClOrdId, int qty, double price) {
        return base("G", clOrdId).setOrigClOrdId(origClOrdId)
                .setOrderQty(qty).setPrice(price).build();
    }

    private OrderEvent cancelRequest(String clOrdId, String origClOrdId) {
        return base("F", clOrdId).setOrigClOrdId(origClOrdId).build();
    }

    private OrderEvent ack(String clOrdId, String orderId, int qty, double price) {
        return report(clOrdId, "", "ACK-" + clOrdId, "0", "0", qty, price, 0, qty, 0)
                .toBuilder().setOrderId(orderId).build();
    }

    private OrderEvent fill(String clOrdId, String execId, String ordStatus,
                            int cumQty, int leavesQty, double avgPx, int lastShares, double lastPx) {
        return base("8", clOrdId)
                .setExecId(execId)
                .setOrdStatus(ordStatus)
                .setExecType(ordStatus)
                .setExecTransType("0")
                .setCumQty(cumQty)
                .setLeavesQty(leavesQty)
                .setAvgPx(avgPx)
                .setLastShares(lastShares)
                .setLastPx(lastPx)
                .build();
    }

    private OrderEvent replaceAck(String clOrdId, String origClOrdId, String orderId,
                                  int qty, double price, int cumQty, int leavesQty, double avgPx) {
        return base("8", clOrdId)
                .setOrigClOrdId(origClOrdId)
                .setOrderId(orderId)
                .setExecId("RPL-" + clOrdId)
                .setOrdStatus("5")
                .setExecType("5")
                .setOrderQty(qty)
                .setPrice(price)
                .setCumQty(cumQty)
                .setLeavesQty(leavesQty)
                .setAvgPx(avgPx)
                .build();
    }

    private OrderEvent report(String clOrdId, String origClOrdId, String execId,
                              String ordStatus, String execType, int orderQty, double price,
                              int cumQty, int leavesQty, double avgPx) {
        return base("8", clOrdId)
                .setOrigClOrdId(origClOrdId)
                .setExecId(execId)
                .setOrdStatus(ordStatus)
                .setExecType(execType)
                .setOrderQty(orderQty)
                .setPrice(price)
                .setCumQty(cumQty)
                .setLeavesQty(leavesQty)
                .setAvgPx(avgPx)
                .build();
    }

    private OrderEvent cancelReject(String clOrdId, String origClOrdId, String ordStatus,
                                    String responseTo, String text) {
        return base("9", clOrdId)
                .setOrigClOrdId(origClOrdId)
                .setOrdStatus(ordStatus)
                .setCxlRejResponseTo(responseTo)
                .setText(text)
                .build();
    }
}
