package com.demo.amps.fix42.state.machine;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.Prices;
import com.demo.amps.fix42.state.hooks.RejectStatusAtRequestTimeHook;
import com.demo.amps.fix42.state.mock.EdgeCaseFlow;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.mock.MockDialect;
import com.demo.amps.fix42.state.mock.MockFixFlow;
import com.demo.amps.fix42.state.mock.OrderChain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * docs/05 phase 2's property test: for every chain under every dialect,
 * applying its events in order ends with 14/151/6/39 equal to the
 * {@link OrderChain}'s own final state -- the venue's truth, which no
 * delivery fault the chain scripted changes.
 *
 * <p>All the chains of one dialect go through ONE machine, so a case that
 * needs two orders to stay apart (E57's OrderID equal to another order's
 * ClOrdID, E58's G naming another order) is tested against its neighbours
 * too. The merged record is read as a subscriber would read it, from the
 * published payloads, as well as from the cache.
 */
class EdgeCaseFlowPropertyTest {

    static List<MockDialect.Named> dialects() {
        return MockDialect.variants();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void everyChainEndsWhereTheVenueSaysItIs(MockDialect.Named dialect) {
        assertThat(divergences(dialect, new MachineTester()))
                .as("chains whose record disagrees with the venue under %s", dialect)
                .containsExactlyInAnyOrderElementsOf(KNOWN.getOrDefault(dialect.name(), List.of()));
    }

    /**
     * docs/11 F2's fix, a hook for the venue that needs it: with it on, the F2
     * entries are gone under that venue's dialect, and no other dialect --
     * whose rejects carry the status after the reject -- moves at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void theRequestTimeRejectHookClosesF2AndMovesNothingElse(MockDialect.Named dialect) {
        List<String> expected = new ArrayList<>(KNOWN.getOrDefault(dialect.name(), List.of()));
        expected.removeAll(KNOWN.getOrDefault(F2_DIALECT, List.of()));
        assertThat(divergences(dialect, new MachineTester().withHook(new RejectStatusAtRequestTimeHook())))
                .as("chains whose record disagrees with the venue under %s, with the hook", dialect)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /** Every chain of the scripted flow and the edge cases under {@code dialect}, through ONE machine. */
    private static List<String> divergences(MockDialect.Named dialect, MachineTester m) {
        List<OrderChain> chains = new ArrayList<>(MockFixFlow.chains());
        chains.addAll(EdgeCaseFlow.chains(dialect.dialect()));
        for (OrderChain chain : chains) {
            for (FixEvent event : chain.events()) {
                m.send(event.message());
            }
        }

        List<String> wrong = new ArrayList<>();
        for (OrderChain chain : chains) {
            String key = m.machine.keyForClOrdId(chain.rootClOrdId());
            if (key == null) {
                wrong.add(chain + ": no record for " + chain.rootClOrdId());
                continue;
            }
            FixMessage record = m.record(key);
            check(wrong, chain, "39", chain.ordStatus(), record.value(FixTags.ORD_STATUS));
            check(wrong, chain, "14", Long.toString(chain.cumQty()), record.value(FixTags.CUM_QTY));
            check(wrong, chain, "151", Long.toString(chain.leavesQty()), record.value(FixTags.LEAVES_QTY));
            checkNumber(wrong, chain, "6", Prices.plain(chain.avgPx()), record.value(FixTags.AVG_PX));
            check(wrong, chain, "9017", OrderState.isTerminal(chain.ordStatus()) ? "Y" : "N",
                    record.value(FixTags.Record.TERMINAL));
            assertThat(m.cached(key).value(FixTags.CUM_QTY))
                    .as("%s: the cache agrees with the merged record", chain)
                    .isEqualTo(record.value(FixTags.CUM_QTY));
        }
        // E58's G names another order's 37: the one conflict the flow scripts.
        assertThat(m.machine.counters().get(Counter.ID_CONFLICTS)).isEqualTo(1);
        return wrong;
    }

    private static final String F2_DIALECT = "reject-status-at-request-time-6";

    /**
     * Divergences the design accepts, each a finding of the build
     * (docs/11 F2, F3). Listed exactly, so a new one fails and a fix flags
     * the entry as stale. F2's entries are what the machine does on its own;
     * the venue that sends such rejects runs {@link RejectStatusAtRequestTimeHook}.
     */
    private static final java.util.Map<String, List<String>> KNOWN = java.util.Map.of(
            // F2: a 150=6 set 39=6; the venue then rejects the cancel with its
            // request-time 39=6, which WHEN_NOT_TERMINAL rightly refuses -- and
            // nothing else restores the record's own 39, so it reads pending
            // cancel with nothing pending until the next report.
            F2_DIALECT, List.of(
                    "EC-E15 39=6 (venue: 1)",
                    "EC-E31 39=6 (venue: 1)"),
            // F3: the re-ack is 250ms after the erroneous cancel, the same
            // whole second; without milliseconds a reopen needs a later
            // second (S4), so the conservative side keeps it canceled.
            "transact-time-in-seconds", List.of(
                    "EC-E36 39=4 (venue: 1)",
                    "EC-E36 151=0 (venue: 500)",
                    "EC-E36 9017=Y (venue: N)"));

    private static void check(List<String> wrong, OrderChain chain, String tag, String expected, String actual) {
        if (!expected.equals(actual)) {
            wrong.add(chain + " " + tag + "=" + actual + " (venue: " + expected + ")");
        }
    }

    private static void checkNumber(List<String> wrong, OrderChain chain, String tag, String expected,
                                    String actual) {
        if (!Quantities.equal(expected, actual)) {
            wrong.add(chain + " " + tag + "=" + actual + " (venue: " + expected + ")");
        }
    }
}
