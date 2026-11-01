package com.demo.amps.fix42.state.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The edge-case scripts are a specification only if their numbers are a
 * venue's: docs/05 phase 1's exit criterion is that the fill arithmetic holds
 * for every new chain under every dialect. These are those checks, plus the
 * identity properties a feed relies on.
 */
class EdgeCaseFlowTest {

    /** Tag 43, PossDupFlag: a resend, which legitimately repeats an ExecID or a ClOrdID. */
    private static final int POSS_DUP_FLAG = 43;

    static Stream<Arguments> chainsUnderEveryDialect() {
        List<Arguments> all = new ArrayList<>();
        for (MockDialect.Named dialect : MockDialect.variants()) {
            for (EdgeCaseFlow.EdgeCase edgeCase : EdgeCaseFlow.cases()) {
                for (OrderChain chain : edgeCase.build(dialect.dialect())) {
                    all.add(Arguments.of(dialect, edgeCase.id(), chain));
                }
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{1} {2} [{0}]")
    @MethodSource("chainsUnderEveryDialect")
    @DisplayName("every working report satisfies OrderQty = CumQty + LeavesQty")
    void cumulativeQuantitiesAddUp(MockDialect.Named dialect, String row, OrderChain chain) {
        for (FixEvent event : chain.events()) {
            FixMessage message = event.message();
            if (!isReport(message) || event.description().equals("amend rejected by report")) {
                // A request reject in report form echoes the REJECTED terms in
                // 38 beside the order's real 14/151 -- the shape docs/03 §7.15
                // exists to survive, and by construction not a working report.
                continue;
            }
            String execType = message.value(FixTags.EXEC_TYPE);
            boolean closing = FixTags.ExecType.CANCELED.equals(execType)
                    || FixTags.ExecType.DONE_FOR_DAY.equals(execType)
                    || FixTags.ExecType.EXPIRED.equals(execType)
                    || FixTags.ExecType.REJECTED.equals(execType);
            boolean closedOutRestatement = message.has(FixTags.EXEC_REF_ID)
                    && "0".equals(message.value(FixTags.LEAVES_QTY));
            if (closing || closedOutRestatement) {
                assertThat(message.value(FixTags.LEAVES_QTY))
                        .as("%s %s zeroes LeavesQty", chain, event.description())
                        .isEqualTo("0");
                continue;
            }
            long orderQty = Long.parseLong(message.value(FixTags.ORDER_QTY));
            long cumQty = Long.parseLong(message.value(FixTags.CUM_QTY));
            long leavesQty = Long.parseLong(message.value(FixTags.LEAVES_QTY));
            assertThat(cumQty + leavesQty)
                    .as("%s %s: 38 = 14 + 151", chain, event.description())
                    .isEqualTo(orderQty);
        }
    }

    @ParameterizedTest(name = "{1} {2} [{0}]")
    @MethodSource("chainsUnderEveryDialect")
    @DisplayName("the chain's AvgPx is the volume-weighted average of the fills the venue stands behind")
    void averagePriceMatchesTheSurvivingFills(MockDialect.Named dialect, String row, OrderChain chain) {
        // Replayed from what the tap SAW, which a dropped or swapped message
        // does not change for fills: every case drops only requests and
        // confirms, never a trade.
        Map<String, double[]> fills = new HashMap<>();
        for (FixEvent event : chain.events()) {
            FixMessage message = event.message();
            if (!isReport(message) || message.has(POSS_DUP_FLAG)
                    || FixTags.ExecTransType.STATUS.equals(message.value(FixTags.EXEC_TRANS_TYPE))) {
                continue;
            }
            String transType = message.value(FixTags.EXEC_TRANS_TYPE);
            if (FixTags.ExecTransType.CANCEL.equals(transType)) {
                fills.remove(message.value(FixTags.EXEC_REF_ID));
            } else if (FixTags.ExecTransType.CORRECT.equals(transType)) {
                fills.put(message.value(FixTags.EXEC_REF_ID), trade(message));
            } else if (message.has(FixTags.LAST_SHARES)) {
                fills.put(message.value(FixTags.EXEC_ID), trade(message));
            }
        }
        double shares = fills.values().stream().mapToDouble(fill -> fill[0]).sum();
        double notional = fills.values().stream().mapToDouble(fill -> fill[0] * fill[1]).sum();
        assertThat((long) shares).as("%s: CumQty", chain).isEqualTo(chain.cumQty());
        assertThat(chain.avgPx())
                .as("%s: AvgPx", chain)
                .isCloseTo(shares == 0 ? 0.0 : notional / shares, within(0.0005));
    }

    @ParameterizedTest(name = "{1} {2} [{0}]")
    @MethodSource("chainsUnderEveryDialect")
    @DisplayName("identity: unique ExecIDs and request ids, no 41 equal to 11")
    void identityHolds(MockDialect.Named dialect, String row, OrderChain chain) {
        Set<String> execIds = new HashSet<>();
        Set<String> requestIds = new HashSet<>();
        for (FixEvent event : chain.events()) {
            FixMessage message = event.message();
            assertThat(message.value(FixTags.ORIG_CL_ORD_ID))
                    .as("%s %s", chain, event.description())
                    .isNotEqualTo(message.value(FixTags.CL_ORD_ID));
            if (message.has(POSS_DUP_FLAG)) {
                continue;
            }
            if (isReport(message) && message.has(FixTags.EXEC_ID)
                    && !"0".equals(message.value(FixTags.EXEC_ID))) {
                assertThat(execIds.add(message.value(FixTags.EXEC_ID)))
                        .as("%s %s: ExecID unique", chain, event.description())
                        .isTrue();
            }
            if (isRequest(message)) {
                assertThat(requestIds.add(message.value(FixTags.CL_ORD_ID)))
                        .as("%s %s: request ClOrdID unique", chain, event.description())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("every edge case builds under every dialect, and the ids are the matrix's")
    void everyCaseBuilds() {
        for (MockDialect.Named dialect : MockDialect.variants()) {
            assertThat(EdgeCaseFlow.chains(dialect.dialect())).isNotEmpty();
        }
        assertThat(EdgeCaseFlow.cases()).extracting(EdgeCaseFlow.EdgeCase::id)
                .doesNotHaveDuplicates()
                .contains("E1", "E2", "E4", "E15", "E20", "E35", "E40", "E57");
    }

    @Test
    @DisplayName("two cancels: the chain itself ends canceled with nothing outstanding, either way round")
    void twoCancelsEndCanceled() {
        for (String row : List.of("E1", "E2")) {
            OrderChain chain = edgeCase(row).build(MockDialect.DEFAULT).getFirst();
            assertThat(chain.ordStatus()).as(row).isEqualTo(FixTags.OrdStatus.CANCELED);
            assertThat(chain.leavesQty()).as(row).isZero();
            assertThat(chain.outstandingClOrdIds()).as(row).isEmpty();
        }
    }

    @Test
    @DisplayName("two cancels: both name the original id, and each answer names its own request")
    void twoCancelsShape() {
        List<FixEvent> events = edgeCase("E1").build(MockDialect.DEFAULT).getFirst().events();
        List<String> printed = events.stream().skip(3).map(event -> event.msgType() + " "
                + event.message().value(FixTags.CL_ORD_ID) + " "
                + event.message().value(FixTags.ORIG_CL_ORD_ID) + " "
                + event.message().value(FixTags.EXEC_TYPE)).toList();
        assertThat(printed).containsExactly(
                "F EC-E1-2 EC-E1-1 ",
                "F EC-E1-3 EC-E1-1 ",
                "8 EC-E1-2 EC-E1-1 4",
                "9 EC-E1-3 EC-E1-1 ");
    }

    @Test
    @DisplayName("cancel-then-new: an old-leg 150=4 then a new-leg 150=0, and no 150=5")
    void cancelThenNewShape() {
        List<String> execTypes = edgeCase("E35").build(MockDialect.DEFAULT).getFirst().events().stream()
                .filter(event -> isReport(event.message()))
                .map(event -> event.message().value(FixTags.EXEC_TYPE)).toList();
        assertThat(execTypes).containsExactly("0", "1", "4", "0", "1");
    }

    @Test
    @DisplayName("the venue dialect changes the confirm's tag 11 to the original id")
    void echoOriginalId() {
        FixMessage confirm = lastReport(edgeCase("E9").build(MockDialect.DEFAULT).getFirst());
        assertThat(confirm.value(FixTags.EXEC_TYPE)).isEqualTo("4");
        assertThat(confirm.value(FixTags.CL_ORD_ID)).isEqualTo("EC-E9-1");
    }

    @Test
    @DisplayName("the late D carries its original TransactTime, earlier than the reports before it")
    void lateNewOrderKeepsItsTime() {
        List<FixEvent> events = edgeCase("E40").build(MockDialect.DEFAULT).getFirst().events();
        assertThat(events.getLast().msgType()).isEqualTo("D");
        assertThat(events.getLast().message().value(FixTags.TRANSACT_TIME))
                .isLessThan(events.getFirst().message().value(FixTags.TRANSACT_TIME));
    }

    private static EdgeCaseFlow.EdgeCase edgeCase(String id) {
        return EdgeCaseFlow.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static FixMessage lastReport(OrderChain chain) {
        List<FixEvent> events = chain.events();
        for (int i = events.size() - 1; i >= 0; i--) {
            if (isReport(events.get(i).message())) {
                return events.get(i).message();
            }
        }
        throw new AssertionError("no report in " + chain);
    }

    private static double[] trade(FixMessage message) {
        return new double[] {
                Double.parseDouble(message.value(FixTags.LAST_SHARES)),
                Double.parseDouble(message.value(FixTags.LAST_PX))};
    }

    private static boolean isReport(FixMessage message) {
        return FixTags.MsgType.EXECUTION_REPORT.equals(message.msgType());
    }

    private static boolean isRequest(FixMessage message) {
        String type = message.msgType();
        return FixTags.MsgType.NEW_ORDER_SINGLE.equals(type)
                || FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST.equals(type)
                || FixTags.MsgType.ORDER_CANCEL_REQUEST.equals(type);
    }
}
