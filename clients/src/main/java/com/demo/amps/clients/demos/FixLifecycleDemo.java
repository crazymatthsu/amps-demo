package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.clients.MessageStreams;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.Topics;
import com.demo.amps.common.fix.FixOrderStateMachine;
import com.demo.amps.common.fix.FixOrderStateMachine.Outcome;
import com.demo.amps.market.v1.OrderEvent;
import com.demo.amps.market.v1.OrderState;
import com.demo.amps.market.v1.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The FIX 4.2 order-state pattern end to end: a scripted lifecycle of
 * 35=D/G/F/8/9 messages runs through {@link FixOrderStateMachine}, raw events
 * land in {@code fix.events} (journalled audit trail) and derived state lands
 * in {@code fix.orders} (SOW keyed on the stable chain id).
 */
public final class FixLifecycleDemo implements Demo {

    private int seq;

    @Override
    public String name() {
        return "fix-lifecycle";
    }

    @Override
    public String summary() {
        return "Derive FIX 4.2 order state (status, CumQty, LeavesQty, AvgPx, pendings) into a SOW";
    }

    @Override
    public String explanation() {
        return "Can AMPS itself derive order state from 35=D/G/F/8/9? Mostly it does not "
                + "have to: a FIX 4.2 execution report is a cumulative snapshot -- OrdStatus, "
                + "CumQty, LeavesQty, AvgPx, OrderQty and Price ride on every 35=8, "
                + "maintained by the venue. What no SOW key or view can express is chain "
                + "identity across cancel/replace (41 -> 11), pending-request bookkeeping, "
                + "and duplicate/stale arbitration. That thin remainder is "
                + "FixOrderStateMachine, running here in the publishing gateway: raw events "
                + "are journalled to fix.events as the system of record, derived state is "
                + "published to fix.orders keyed on a chain id that never moves, and every "
                + "AMPS feature in this repo -- query, sow_and_subscribe, OOF, deltas -- now "
                + "applies to order state.";
    }

    @Override
    public void run(Args args) throws Exception {
        try (Client client = AmpsConnections.connect("demo-fix-gateway")) {

            FixOrderStateMachine machine = new FixOrderStateMachine();
            List<OrderEvent> lifecycle = scriptedLifecycle();

            Console.step("Running the lifecycle through the state machine");
            Console.note("Left: the FIX message as received. Right: the derived record "
                    + "published to fix.orders. Watch the acked quantity hold at 500 while "
                    + "the replace to 800 is pending, and the chain id never change.");
            Console.info("");

            for (OrderEvent received : lifecycle) {
                Outcome outcome = machine.apply(received);

                if (outcome instanceof Outcome.Applied applied) {
                    OrderState state = applied.state();
                    // The gateway's enrichment: stamp the resolved chain id so the
                    // audit trail is queryable per chain despite moving ClOrdIDs.
                    OrderEvent enriched = received.toBuilder()
                            .setChainId(state.getChainId())
                            .build();
                    client.publish(Topics.FIX_EVENTS, JsonCodec.toJson(enriched));
                    client.publish(Topics.FIX_ORDERS, JsonCodec.toJson(state));
                    Console.info("   %-34s | %s", describeEvent(received), describeState(state));
                } else if (outcome instanceof Outcome.Ignored ignored) {
                    // Still worth auditing: the event arrived, even though it must
                    // not touch the state. Same /eventId collapses onto the same
                    // SOW record, so a PossDup resend is naturally idempotent.
                    client.publish(Topics.FIX_EVENTS, JsonCodec.toJson(received));
                    Console.info("   %-34s | IGNORED: %s",
                            describeEvent(received), ignored.reason());
                }
            }
            client.publishFlush(DemoConfig.timeoutMillis());

            Console.step("What the SOW now answers, with no consumer-side FIX knowledge");
            OrderState finalState = queryChain(client, "A1");
            if (finalState != null) {
                Console.kv("chain (SOW key)", finalState.getChainId());
                Console.kv("working ClOrdID", finalState.getCurrentClOrdId());
                Console.kv("venue OrderID", finalState.getOrderId());
                Console.kv("order status", finalState.getOrdStatus());
                Console.kv("acked qty @ price", finalState.getAckedOrderQty()
                        + " @ " + finalState.getAckedPrice());
                Console.kv("CumQty / LeavesQty", finalState.getCumQty()
                        + " / " + finalState.getLeavesQty());
                Console.kv("AvgPx", finalState.getAvgPx());
                Console.kv("pending request", finalState.getPendingRequest());
            } else {
                Console.note("fix.orders returned nothing -- is the topic declared in the "
                        + "config this instance is running?");
            }

            Console.step("The audit trail is a query too");
            int events = countEvents(client, "/chainId = 'A1'");
            Console.kv("events recorded for chain A1", events);
            Console.note("fix.events is keyed on /eventId, so every message is its own "
                    + "record and the chain's history is a filter -- while the journal "
                    + "behind it supports bookmark replay for rebuilding state from "
                    + "scratch. fix.orders is deliberately NOT journalled: it is derivable "
                    + "from this stream, so journalling it would record everything twice.");

            Console.step("What to try next");
            Console.bullet("sow_and_subscribe fix.orders with /ordStatus = "
                    + "'FIX_ORD_STATUS_PARTIALLY_FILLED' and watch OOF fire as orders fill");
            Console.bullet("restart the server and query fix.orders again -- the derived "
                    + "state survives (persistent SOW), and fix.events can rebuild it");
            Console.note("The reasoning and the boundaries of the state machine are in "
                    + "docs/src/fix-order-state.md.");
        }
    }

    // ---- the scripted lifecycle -------------------------------------------

    private List<OrderEvent> scriptedLifecycle() {
        List<OrderEvent> events = new ArrayList<>();
        // 1. New order: buy 500 AAPL @ 100.05.
        events.add(base("D", "A1").setOrderQty(500).setPrice(100.05).build());
        // 2. Venue ack.
        events.add(base("8", "A1").setOrderId("ORD-7").setExecId("EXEC-ACK")
                .setOrdStatus("0").setExecType("0").setOrderQty(500).setPrice(100.05)
                .setLeavesQty(500).build());
        // 3. Partial fill 200 @ 100.00.
        events.add(fill("A1", "EXEC-1", "1", 200, 300, 100.00, 200, 100.00));
        // 4. Cancel/replace: B1 asks for 800 @ 100.10.
        events.add(base("G", "B1").setOrigClOrdId("A1")
                .setOrderQty(800).setPrice(100.10).build());
        // 5. A fill crosses the pending replace: +100 @ 100.06.
        events.add(fill("A1", "EXEC-2", "1", 300, 200, 100.02, 100, 100.06));
        // 6. Replace accepted: 11=B1, 41=A1, new terms 800 @ 100.10.
        events.add(base("8", "B1").setOrigClOrdId("A1").setOrderId("ORD-7")
                .setExecId("EXEC-3").setOrdStatus("5").setExecType("5")
                .setOrderQty(800).setPrice(100.10)
                .setCumQty(300).setLeavesQty(500).setAvgPx(100.02).build());
        // 7. Cancel attempt C1...
        events.add(base("F", "C1").setOrigClOrdId("B1").build());
        // 8. ...rejected; 39 on the 35=9 states what the order still is.
        events.add(base("9", "C1").setOrigClOrdId("B1").setOrdStatus("1")
                .setCxlRejResponseTo("1").setText("too late to cancel").build());
        // 9. PossDup resend of EXEC-2: must not double-apply.
        events.add(fill("A1", "EXEC-2", "1", 300, 200, 100.02, 100, 100.06));
        // 10. Final fill to completion: 800 done, venue-computed AvgPx.
        events.add(fill("B1", "EXEC-4", "2", 800, 0, 100.04, 500, 100.05));
        return events;
    }

    private OrderEvent.Builder base(String msgType, String clOrdId) {
        seq++;
        return OrderEvent.newBuilder()
                .setEventId("EVT-" + String.format("%03d", seq))
                .setSeq(seq)
                .setMsgType(msgType)
                .setClOrdId(clOrdId)
                .setSymbol("AAPL")
                .setSide(Side.SIDE_BUY);
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

    // ---- rendering ---------------------------------------------------------

    private static String describeEvent(OrderEvent event) {
        StringBuilder out = new StringBuilder("35=").append(event.getMsgType())
                .append(" 11=").append(event.getClOrdId());
        if (!event.getOrigClOrdId().isEmpty()) {
            out.append(" 41=").append(event.getOrigClOrdId());
        }
        if (!event.getOrdStatus().isEmpty()) {
            out.append(" 39=").append(event.getOrdStatus());
        }
        if (event.getLastShares() > 0) {
            out.append(" 32=").append(event.getLastShares());
        }
        return out.toString();
    }

    private static String describeState(OrderState state) {
        String status = state.getOrdStatus().name().replace("FIX_ORD_STATUS_", "");
        String pending = switch (state.getPendingRequest()) {
            case PENDING_REPLACE -> String.format(Locale.ROOT, "  pending REPLACE %d@%.2f",
                    state.getPendingOrderQty(), state.getPendingPrice());
            case PENDING_CANCEL -> "  pending CANCEL";
            case PENDING_NEW -> String.format(Locale.ROOT, "  pending NEW %d@%.2f",
                    state.getPendingOrderQty(), state.getPendingPrice());
            default -> "";
        };
        return String.format(Locale.ROOT, "%-17s acked %3d@%-7.2f cum %3d leaves %3d avg %.4f%s",
                status, state.getAckedOrderQty(), state.getAckedPrice(),
                state.getCumQty(), state.getLeavesQty(), state.getAvgPx(), pending);
    }

    // ---- queries -----------------------------------------------------------

    private static OrderState queryChain(Client client, String chainId) throws Exception {
        OrderState[] found = {null};
        try (MessageStream stream = client.execute(new Command("sow")
                .setTopic(Topics.FIX_ORDERS)
                .setFilter("/chainId = '" + chainId + "'")
                .setTimeout(DemoConfig.timeoutMillis()))) {
            MessageStreams.forEach(stream, 5000, message -> {
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    found[0] = JsonCodec.fromJson(message.getData(), OrderState.getDefaultInstance());
                }
                return true;
            });
        }
        return found[0];
    }

    private static int countEvents(Client client, String filter) throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (MessageStream stream = client.execute(new Command("sow")
                .setTopic(Topics.FIX_EVENTS)
                .setFilter(filter)
                .setBatchSize(100)
                .setTimeout(DemoConfig.timeoutMillis()))) {
            MessageStreams.forEach(stream, 5000, message -> {
                if (message.getCommand() == Message.Command.SOW) {
                    count.incrementAndGet();
                }
                return true;
            });
        }
        return count.get();
    }
}
