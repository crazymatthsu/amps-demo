package com.demo.amps.fix42.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.fix.Prices;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The mock feed is only worth publishing if it is internally consistent -- a
 * fixture whose numbers do not add up teaches the wrong lesson and hides real
 * bugs behind plausible-looking output. These tests are what keep it honest.
 */
class MockFixFlowTest {

    static List<OrderChain> chains() {
        return MockFixFlow.chains();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chains")
    @DisplayName("every execution report satisfies OrderQty = CumQty + LeavesQty while working")
    void cumulativeQuantitiesAddUp(OrderChain chain) {
        for (FixEvent event : chain.events()) {
            FixMessage message = event.message();
            if (!FixTags.MsgType.EXECUTION_REPORT.equals(message.msgType())) {
                continue;
            }
            String execType = message.value(FixTags.EXEC_TYPE);
            // Terminal cancel and done-for-day zero LeavesQty by definition:
            // the unfilled balance stops working, it is not filled.
            boolean terminalWithZeroedLeaves = FixTags.ExecType.CANCELED.equals(execType)
                    || FixTags.ExecType.DONE_FOR_DAY.equals(execType)
                    || FixTags.ExecType.REJECTED.equals(execType);
            if (terminalWithZeroedLeaves) {
                assertThat(message.value(FixTags.LEAVES_QTY))
                        .as("%s %s zeroes LeavesQty", chain.chainId(), event.description())
                        .isEqualTo("0");
                continue;
            }

            long orderQty = Long.parseLong(message.value(FixTags.ORDER_QTY));
            long cumQty = Long.parseLong(message.value(FixTags.CUM_QTY));
            long leavesQty = Long.parseLong(message.value(FixTags.LEAVES_QTY));
            assertThat(cumQty + leavesQty)
                    .as("%s %s: 38=%d but 14=%d + 151=%d", chain.chainId(), event.description(),
                            orderQty, cumQty, leavesQty)
                    .isEqualTo(orderQty);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chains")
    @DisplayName("AvgPx on every report is the volume-weighted average of the fills so far")
    void averagePriceMatchesItsOwnFills(OrderChain chain) {
        long cumQty = 0;
        double avgPx = 0.0;

        for (FixEvent event : chain.events()) {
            FixMessage message = event.message();
            if (!FixTags.MsgType.EXECUTION_REPORT.equals(message.msgType())) {
                continue;
            }
            if (message.has(FixTags.LAST_SHARES)) {
                long lastShares = Long.parseLong(message.value(FixTags.LAST_SHARES));
                double lastPx = Double.parseDouble(message.value(FixTags.LAST_PX));
                avgPx = Prices.averagePrice(cumQty, avgPx, lastShares, lastPx);
                cumQty += lastShares;
            }
            assertThat(message.value(FixTags.AVG_PX))
                    .as("%s %s: AvgPx should be the VWAP of fills so far",
                            chain.chainId(), event.description())
                    .isEqualTo(Prices.plain(avgPx));
            assertThat(Long.parseLong(message.value(FixTags.CUM_QTY)))
                    .as("%s %s: CumQty should be the sum of fills so far",
                            chain.chainId(), event.description())
                    .isEqualTo(cumQty);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chains")
    @DisplayName("each new ClOrdID links back to the previous one through tag 41")
    void clOrdIdChainIsLinked(OrderChain chain) {
        String working = null;

        for (FixEvent event : chain.events()) {
            FixMessage message = event.message();
            String msgType = message.msgType();
            if (FixTags.MsgType.NEW_ORDER_SINGLE.equals(msgType)) {
                working = message.value(FixTags.CL_ORD_ID);
                assertThat(message.has(FixTags.ORIG_CL_ORD_ID))
                        .as("%s: a 35=D opens the chain and has nothing to reference",
                                chain.chainId())
                        .isFalse();
            } else if (FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST.equals(msgType)
                    || FixTags.MsgType.ORDER_CANCEL_REQUEST.equals(msgType)) {
                assertThat(message.value(FixTags.ORIG_CL_ORD_ID))
                        .as("%s %s: tag 41 must name the ClOrdID being acted on",
                                chain.chainId(), event.description())
                        .isEqualTo(working);
                assertThat(message.value(FixTags.CL_ORD_ID))
                        .as("%s %s: tag 11 must be a NEW value on every request",
                                chain.chainId(), event.description())
                        .isNotEqualTo(working);
                working = message.value(FixTags.CL_ORD_ID);
            }
        }
    }

    @Test
    @DisplayName("every ClOrdID in the whole flow is unique")
    void clOrdIdsAreUnique() {
        List<String> requestIds = new ArrayList<>();
        for (FixEvent event : MockFixFlow.events()) {
            String msgType = event.msgType();
            if (FixTags.MsgType.NEW_ORDER_SINGLE.equals(msgType)
                    || FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST.equals(msgType)
                    || FixTags.MsgType.ORDER_CANCEL_REQUEST.equals(msgType)) {
                requestIds.add(event.message().value(FixTags.CL_ORD_ID));
            }
        }
        assertThat(requestIds).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every ExecID in the whole flow is unique -- it is the execs_audit key")
    void execIdsAreUnique() {
        List<String> execIds = MockFixFlow.events().stream()
                .map(FixEvent::message)
                .filter(message -> FixTags.MsgType.EXECUTION_REPORT.equals(message.msgType()))
                .map(message -> message.value(FixTags.EXEC_ID))
                .toList();

        assertThat(execIds).isNotEmpty().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("OrderID is stable across a chain once the venue assigns it")
    void orderIdIsStablePerChain() {
        for (OrderChain chain : MockFixFlow.chains()) {
            Set<String> orderIds = chain.events().stream()
                    .map(FixEvent::message)
                    .filter(message -> FixTags.MsgType.EXECUTION_REPORT.equals(message.msgType()))
                    .map(message -> message.value(FixTags.ORDER_ID))
                    .collect(Collectors.toCollection(HashSet::new));

            assertThat(orderIds)
                    .as("%s: tag 37 must not move across the chain", chain.chainId())
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("child chains stamp tag 9000 on every request they originate")
    void childRequestsCarryParentOrderId() {
        for (OrderChain chain : MockFixFlow.chains()) {
            for (FixEvent event : chain.events()) {
                FixMessage message = event.message();
                boolean isRequest = List.of(FixTags.MsgType.NEW_ORDER_SINGLE,
                                FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST,
                                FixTags.MsgType.ORDER_CANCEL_REQUEST)
                        .contains(message.msgType());
                if (!isRequest) {
                    continue;
                }
                // The stateless router reads scope from this tag alone, so it
                // has to be on every request, not only the 35=D.
                assertThat(message.has(FixTags.PARENT_ORDER_ID))
                        .as("%s %s: tag 9000 presence must match scope %s",
                                chain.chainId(), event.description(), chain.scope())
                        .isEqualTo(chain.scope() == OrderScope.CHILD);
            }
        }
    }

    @Test
    @DisplayName("child orders point at a parent chain that exists in the flow")
    void childrenReferenceARealParent() {
        Set<String> parentClOrdIds = MockFixFlow.events().stream()
                .filter(event -> event.scope() == OrderScope.PARENT)
                .map(event -> event.message().value(FixTags.CL_ORD_ID))
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toSet());

        List<String> referenced = MockFixFlow.events().stream()
                .map(FixEvent::message)
                .filter(message -> message.has(FixTags.PARENT_ORDER_ID))
                .map(message -> message.value(FixTags.PARENT_ORDER_ID))
                .distinct()
                .toList();

        assertThat(referenced).isNotEmpty();
        assertThat(parentClOrdIds).containsAll(referenced);
    }

    @Test
    @DisplayName("the flow covers every message type and both order scopes")
    void flowIsRepresentative() {
        Map<String, Long> byType = MockFixFlow.events().stream()
                .collect(Collectors.groupingBy(FixEvent::msgType, Collectors.counting()));

        assertThat(byType).containsKeys("D", "G", "F", "8", "9");
        assertThat(MockFixFlow.events()).extracting(FixEvent::scope)
                .contains(OrderScope.PARENT, OrderScope.CHILD);
    }

    @Test
    @DisplayName("the flow covers every ExecType the routing rules split on")
    void flowCoversRoutedExecTypes() {
        Set<String> execTypes = MockFixFlow.events().stream()
                .map(FixEvent::message)
                .filter(message -> FixTags.MsgType.EXECUTION_REPORT.equals(message.msgType()))
                .map(message -> message.value(FixTags.EXEC_TYPE))
                .collect(Collectors.toSet());

        assertThat(execTypes).contains(
                FixTags.ExecType.NEW,             // exec-new-ack
                FixTags.ExecType.PARTIAL_FILL,    // exec-partial-fill
                FixTags.ExecType.FILL,            // exec-fill
                FixTags.ExecType.CANCELED,        // exec-cancel-or-done
                FixTags.ExecType.DONE_FOR_DAY,    // exec-cancel-or-done
                FixTags.ExecType.REPLACED,        // exec-other
                FixTags.ExecType.PENDING_CANCEL); // exec-other
    }

    @Test
    @DisplayName("generation is deterministic: two runs produce identical payloads")
    void isDeterministic() {
        List<String> first = MockFixFlow.events().stream()
                .map(event -> event.message().printable()).toList();
        List<String> second = MockFixFlow.events().stream()
                .map(event -> event.message().printable()).toList();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a cancel reject reverts the working ClOrdID to the pre-request one")
    void cancelRejectRevertsWorkingId() {
        OrderChain google = MockFixFlow.chains().stream()
                .filter(chain -> chain.chainId().equals("PARENT-GOOG"))
                .findFirst()
                .orElseThrow();

        FixEvent reject = google.events().getLast();
        assertThat(reject.msgType()).isEqualTo(FixTags.MsgType.ORDER_CANCEL_REJECT);
        // Tag 11 names the request that was rejected; tag 41 the id still working.
        assertThat(reject.message().value(FixTags.CL_ORD_ID)).isEqualTo("PARENT-GOOG-2");
        assertThat(reject.message().value(FixTags.ORIG_CL_ORD_ID)).isEqualTo("PARENT-GOOG-1");
        assertThat(reject.message().value(FixTags.CXL_REJ_RESPONSE_TO)).isEqualTo("1");
        assertThat(google.currentClOrdId()).isEqualTo("PARENT-GOOG-1");
    }

    @Test
    @DisplayName("tag 41 is never equal to tag 11 on any message")
    void origClOrdIdIsNeverSelfReferencing() {
        // A self-link would be nonsense for the chaining key generator, whose
        // whole job is resolving 41 back to a DIFFERENT message's 11.
        for (FixEvent event : MockFixFlow.events()) {
            FixMessage message = event.message();
            if (!message.has(FixTags.ORIG_CL_ORD_ID)) {
                continue;
            }
            assertThat(message.value(FixTags.ORIG_CL_ORD_ID))
                    .as("%s %s: 41 must name a different request than 11",
                            event.chainId(), event.description())
                    .isNotEqualTo(message.value(FixTags.CL_ORD_ID));
        }
    }

    @Test
    @DisplayName("an amend is not applied until its 150=5 confirms")
    void amendTermsAreStagedUntilConfirmed() {
        OrderChain apple = MockFixFlow.chains().stream()
                .filter(chain -> chain.chainId().equals("PARENT-AAPL"))
                .findFirst()
                .orElseThrow();

        List<FixEvent> events = apple.events();
        FixEvent amendRequest = events.stream()
                .filter(event -> event.msgType().equals(FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST))
                .findFirst().orElseThrow();
        FixEvent pendingReplace = events.get(events.indexOf(amendRequest) + 1);
        FixEvent amendAck = events.get(events.indexOf(amendRequest) + 2);

        assertThat(amendRequest.message().value(FixTags.ORDER_QTY)).isEqualTo("12000");
        // Still the OLD quantity while pending: the venue has not agreed yet.
        assertThat(pendingReplace.message().value(FixTags.ORDER_QTY)).isEqualTo("10000");
        // And the new one only once 150=5 arrives.
        assertThat(amendAck.message().value(FixTags.EXEC_TYPE)).isEqualTo(FixTags.ExecType.REPLACED);
        assertThat(amendAck.message().value(FixTags.ORDER_QTY)).isEqualTo("12000");
        // A replace confirm on a partly filled order stays PARTIALLY_FILLED.
        assertThat(amendAck.message().value(FixTags.ORD_STATUS))
                .isEqualTo(FixTags.OrdStatus.PARTIALLY_FILLED);
    }
}
