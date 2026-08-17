package com.demo.amps.common.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.demo.amps.common.fix.FixOrderStateMachine.Outcome;
import com.demo.amps.market.v1.FixOrdStatus;
import com.demo.amps.market.v1.OrderEvent;
import com.demo.amps.market.v1.OrderState;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderLifecycleSimulatorTest {

    @Test
    @DisplayName("the full script exercises all five message types")
    void coversAllMessageTypes() {
        Set<String> messageTypes = OrderLifecycleSimulator.fullScript().stream()
                .map(OrderEvent::getMsgType)
                .collect(Collectors.toSet());

        assertEquals(Set.of("D", "G", "F", "8", "9"), messageTypes);
    }

    @Test
    @DisplayName("event ids are unique and sequence numbers are monotonic")
    void idsAndSequencing() {
        List<OrderEvent> script = OrderLifecycleSimulator.fullScript();

        Set<String> ids = new HashSet<>();
        int previousSeq = 0;
        for (OrderEvent event : script) {
            assertTrue(ids.add(event.getEventId()), "duplicate event id " + event.getEventId());
            assertTrue(event.getSeq() > previousSeq, "sequence must be monotonic");
            previousSeq = event.getSeq();
        }
    }

    @Test
    @DisplayName("replaying the script through the state machine yields the scripted endings")
    void machineReachesExpectedEndings() {
        FixOrderStateMachine machine = new FixOrderStateMachine();
        int ignored = 0;
        for (OrderEvent event : OrderLifecycleSimulator.fullScript()) {
            if (machine.apply(event) instanceof Outcome.Ignored) {
                ignored++;
            }
        }

        assertEquals(1, ignored, "exactly the PossDup resend should be dropped");

        OrderState replaced = machine.state("A1").orElseThrow();
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_FILLED, replaced.getOrdStatus());
        assertEquals(800, replaced.getCumQty());
        assertEquals("B1", replaced.getCurrentClOrdId(),
                "the replace moved the working ClOrdID");
        assertEquals(100.04, replaced.getAvgPx());

        OrderState cancelled = machine.state("M1").orElseThrow();
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_CANCELED, cancelled.getOrdStatus());
        assertEquals(400, cancelled.getCumQty());
        assertEquals(0, cancelled.getLeavesQty());

        OrderState rejected = machine.state("G1").orElseThrow();
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_REJECTED, rejected.getOrdStatus());

        OrderState filled = machine.state("T1").orElseThrow();
        assertEquals(FixOrdStatus.FIX_ORD_STATUS_FILLED, filled.getOrdStatus());
        assertEquals(300, filled.getCumQty());
    }

    @Test
    @DisplayName("execution reports are internally consistent snapshots")
    void reportsAreInternallyConsistent() {
        OrderLifecycleSimulator.fullScript().stream()
                .filter(event -> "8".equals(event.getMsgType()))
                .filter(event -> event.getOrderQty() > 0)
                .forEach(event -> {
                    switch (event.getOrdStatus()) {
                        // Working (and just-filled) orders: 38 = 14 + 151 holds.
                        case "0", "1", "2", "5", "6", "E" -> assertEquals(
                                event.getOrderQty(),
                                event.getCumQty() + event.getLeavesQty(),
                                "38 = 14 + 151 must hold on " + event.getEventId());
                        // Terminal cancel: LeavesQty is zeroed BY DEFINITION, so the
                        // identity intentionally stops holding -- nothing is left to
                        // work even though 14 < 38. A consumer asserting the identity
                        // on every report will reject legitimate cancels.
                        case "4" -> assertEquals(0, event.getLeavesQty(),
                                "a canceled order leaves nothing working: " + event.getEventId());
                        // Reject: never worked, quantities stay zero.
                        case "8" -> assertEquals(0, event.getCumQty());
                        default -> { }
                    }
                });
    }

    @Test
    @DisplayName("the primary scenario is the fix-lifecycle demo's script")
    void primaryScenarioShape() {
        List<OrderEvent> primary = OrderLifecycleSimulator.primaryScenario();

        assertEquals(10, primary.size());
        assertEquals("D", primary.get(0).getMsgType());
        assertEquals("2", primary.get(9).getOrdStatus(), "ends filled");
        long possDupResends = primary.stream()
                .filter(event -> "EXEC-A2".equals(event.getExecId())).count();
        assertEquals(2, possDupResends, "EXEC-A2 is delivered twice on purpose");
    }
}
