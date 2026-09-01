package com.demo.amps.fix42.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic linking an execution report's quantity tags, pinned as tests.
 *
 * <p>Every fill carries one number that is not a running total -- LastShares
 * (32), this fill's quantity, called LastQty from FIX 4.4 onward. The
 * cumulative trio is derived from it:
 *
 * <pre>
 *   new CumQty    = previous CumQty    + LastShares
 *   new LeavesQty = previous LeavesQty - LastShares
 *   OrderQty      = CumQty + LeavesQty          (on every working report)
 * </pre>
 *
 * <p>Two distinctions this file exists to keep straight, because both are easy
 * to get wrong and neither fails loudly:
 *
 * <ul>
 *   <li><b>LastPx (31) is not AvgPx (6).</b> 31 is the price of this fill
 *       alone; 6 is the quantity-weighted average of every fill so far.</li>
 *   <li><b>Reports that are not trades carry no trade fields.</b> An ack or a
 *       cancel reports no execution, so 31/32 are absent -- while CumQty keeps
 *       whatever total the fills before it established.</li>
 * </ul>
 */
class FillArithmeticTest {

    /**
     * The canonical worked example: 10,000 shares filled in two parts.
     *
     * <pre>
     *   order       OrderQty 10000
     *   fill 1      LastQty 3000 @ 150.00 -> CumQty 3000,  LeavesQty 7000, OrdStatus 1
     *   fill 2      LastQty 7000 @ 150.10 -> CumQty 10000, LeavesQty 0,    OrdStatus 2
     * </pre>
     */
    @Nested
    @DisplayName("10,000 shares in two fills")
    class TwoPartialFills {

        private final List<FixEvent> events = OrderChain
                .forTest("WORKED-EXAMPLE", Instrument.AAPL, 10_000, 150.00)
                .newOrder()
                .ack()
                .partialFill(3_000, 150.00)
                .fill(150.10)
                .events();

        private FixMessage report(int index) {
            return events.get(index).message();
        }

        @Test
        @DisplayName("the order is placed for 10000")
        void orderPlacedForTenThousand() {
            FixMessage order = report(0);
            assertThat(order.msgType()).isEqualTo(FixTags.MsgType.NEW_ORDER_SINGLE);
            assertThat(order.value(FixTags.ORDER_QTY)).isEqualTo("10000");
        }

        @Test
        @DisplayName("the ack reports no trade: nothing filled, everything working")
        void ackReportsNoTrade() {
            FixMessage ack = report(1);
            assertThat(ack.value(FixTags.EXEC_TYPE)).isEqualTo(FixTags.ExecType.NEW);
            assertThat(ack.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.NEW);
            assertThat(ack.value(FixTags.CUM_QTY)).isEqualTo("0");
            assertThat(ack.value(FixTags.LEAVES_QTY)).isEqualTo("10000");
            // No trade happened, so no trade fields.
            assertThat(ack.has(FixTags.LAST_SHARES)).isFalse();
            assertThat(ack.has(FixTags.LAST_PX)).isFalse();
        }

        @Test
        @DisplayName("fill 1: 3000 @ 150.00 -> CumQty 3000, LeavesQty 7000, status 1")
        void firstPartialFill() {
            FixMessage fill = report(2);

            assertThat(fill.value(FixTags.LAST_SHARES)).isEqualTo("3000");
            assertThat(fill.value(FixTags.LAST_PX)).isEqualTo("150");
            assertThat(fill.value(FixTags.CUM_QTY)).isEqualTo("3000");      // 0 + 3000
            assertThat(fill.value(FixTags.LEAVES_QTY)).isEqualTo("7000");   // 10000 - 3000
            assertThat(fill.value(FixTags.ORD_STATUS))
                    .isEqualTo(FixTags.OrdStatus.PARTIALLY_FILLED);
            assertThat(fill.value(FixTags.ORDER_QTY)).isEqualTo("10000");
            // One fill so far, so the average IS that fill's price.
            assertThat(fill.value(FixTags.AVG_PX)).isEqualTo("150");
        }

        @Test
        @DisplayName("fill 2: 7000 @ 150.10 -> CumQty 10000, LeavesQty 0, status 2")
        void secondFillCompletesTheOrder() {
            FixMessage fill = report(3);

            assertThat(fill.value(FixTags.LAST_SHARES)).isEqualTo("7000");
            assertThat(fill.value(FixTags.LAST_PX)).isEqualTo("150.1");
            assertThat(fill.value(FixTags.CUM_QTY)).isEqualTo("10000");     // 3000 + 7000
            assertThat(fill.value(FixTags.LEAVES_QTY)).isEqualTo("0");      // 7000 - 7000
            assertThat(fill.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.FILLED);
            assertThat(fill.value(FixTags.EXEC_TYPE)).isEqualTo(FixTags.ExecType.FILL);
        }

        @Test
        @DisplayName("AvgPx is the weighted average of both fills, not the last price")
        void averagePriceIsWeightedNotLast() {
            FixMessage fill = report(3);

            // (3000 * 150.00 + 7000 * 150.10) / 10000 = 150.07
            assertThat(fill.value(FixTags.AVG_PX)).isEqualTo("150.07");
            // Emphatically not LastPx, and not the midpoint of the two prices.
            assertThat(fill.value(FixTags.AVG_PX)).isNotEqualTo(fill.value(FixTags.LAST_PX));
            assertThat(fill.value(FixTags.AVG_PX)).isNotEqualTo("150.05");
        }

        @Test
        @DisplayName("every working report satisfies OrderQty = CumQty + LeavesQty")
        void orderQuantityAlwaysReconciles() {
            for (FixEvent event : events) {
                FixMessage message = event.message();
                if (!FixTags.MsgType.EXECUTION_REPORT.equals(message.msgType())) {
                    continue;
                }
                long orderQty = Long.parseLong(message.value(FixTags.ORDER_QTY));
                long cumQty = Long.parseLong(message.value(FixTags.CUM_QTY));
                long leavesQty = Long.parseLong(message.value(FixTags.LEAVES_QTY));
                assertThat(cumQty + leavesQty)
                        .as("%s: 38=%d, 14=%d, 151=%d", event.description(),
                                orderQty, cumQty, leavesQty)
                        .isEqualTo(orderQty);
            }
        }
    }

    @Nested
    @DisplayName("reports that are not trades")
    class StatusOnlyReports {

        @Test
        @DisplayName("a cancel carries no LastShares/LastPx but keeps CumQty")
        void cancelPreservesCumQtyWithoutTradeFields() {
            List<FixEvent> events = OrderChain
                    .forTest("CANCELLED", Instrument.MSFT, 5_000, 410.00)
                    .newOrder()
                    .ack()
                    .partialFill(1_500, 410.20)
                    .cancelRequest()
                    .cancelConfirmed()
                    .events();

            FixMessage cancel = events.getLast().message();
            assertThat(cancel.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.CANCELED);
            // No execution on this report...
            assertThat(cancel.has(FixTags.LAST_SHARES)).isFalse();
            assertThat(cancel.has(FixTags.LAST_PX)).isFalse();
            // ...but the 1500 already done is still reported, and the balance
            // stops working rather than being filled.
            assertThat(cancel.value(FixTags.CUM_QTY)).isEqualTo("1500");
            assertThat(cancel.value(FixTags.LEAVES_QTY)).isEqualTo("0");
            // AvgPx likewise survives: it describes the fills, not this report.
            assertThat(cancel.value(FixTags.AVG_PX)).isEqualTo("410.2");
        }

        @Test
        @DisplayName("done-for-day behaves the same: history kept, nothing left working")
        void doneForDayPreservesHistory() {
            FixMessage dfd = OrderChain
                    .forTest("EXPIRED", Instrument.NVDA, 4_000, 121.80)
                    .newOrder()
                    .ack()
                    .partialFill(1_000, 121.75)
                    .doneForDay()
                    .events().getLast().message();

            assertThat(dfd.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.DONE_FOR_DAY);
            assertThat(dfd.has(FixTags.LAST_SHARES)).isFalse();
            assertThat(dfd.value(FixTags.CUM_QTY)).isEqualTo("1000");
            assertThat(dfd.value(FixTags.LEAVES_QTY)).isEqualTo("0");
        }

        @Test
        @DisplayName("a pending-cancel acknowledgement changes no quantity at all")
        void pendingCancelChangesNothing() {
            List<FixEvent> events = OrderChain
                    .forTest("PENDING", Instrument.GOOG, 800, 175.00)
                    .newOrder()
                    .ack()
                    .partialFill(300, 175.05)
                    .cancelRequest()
                    .pendingCancel()
                    .events();

            FixMessage fill = events.get(2).message();
            FixMessage pending = events.getLast().message();

            assertThat(pending.value(FixTags.ORD_STATUS))
                    .isEqualTo(FixTags.OrdStatus.PENDING_CANCEL);
            assertThat(pending.has(FixTags.LAST_SHARES)).isFalse();
            // Still 300 done and 500 working -- a pending state is not an event.
            assertThat(pending.value(FixTags.CUM_QTY)).isEqualTo(fill.value(FixTags.CUM_QTY));
            assertThat(pending.value(FixTags.LEAVES_QTY)).isEqualTo(fill.value(FixTags.LEAVES_QTY));
        }
    }

    /**
     * Busts (20=1) and corrects (20=2) restate the cumulative trio as
     * absolutes recomputed over the surviving fills -- not as a subtraction
     * from the rounded running totals, which would leak rounding error.
     */
    @Nested
    @DisplayName("busts and corrects (ExecTransType 20=1/2)")
    class BustsAndCorrects {

        @Test
        @DisplayName("busting one of several fills recomputes the trio over the survivors")
        void bustOfOneOfSeveralFills() {
            FixMessage bust = OrderChain
                    .forTest("BUSTED", Instrument.AMZN, 6_000, 210.00)
                    .newOrder()
                    .ack()
                    .partialFill(2_000, 209.95)
                    .partialFill(1_000, 210.05)
                    .bust(1)
                    .events().getLast().message();

            assertThat(bust.value(FixTags.EXEC_TRANS_TYPE))
                    .isEqualTo(FixTags.ExecTransType.CANCEL);
            // Tag 19 names the busted execution; tag 17 is a fresh id of its own.
            assertThat(bust.value(FixTags.EXEC_REF_ID)).isEqualTo("EXEC-BUSTED-2");
            assertThat(bust.value(FixTags.EXEC_ID)).isEqualTo("EXEC-BUSTED-4");
            // Only the surviving 1000 @ 210.05 counts.
            assertThat(bust.value(FixTags.CUM_QTY)).isEqualTo("1000");
            assertThat(bust.value(FixTags.LEAVES_QTY)).isEqualTo("5000");
            assertThat(bust.value(FixTags.AVG_PX)).isEqualTo("210.05");
            // 150 mirrors the restated 39: 4.2 has no bust ExecType, the
            // semantics ride on tag 20.
            assertThat(bust.value(FixTags.ORD_STATUS))
                    .isEqualTo(FixTags.OrdStatus.PARTIALLY_FILLED);
            assertThat(bust.value(FixTags.EXEC_TYPE)).isEqualTo(FixTags.ExecType.PARTIAL_FILL);
            // A bust reports no new trade.
            assertThat(bust.has(FixTags.LAST_SHARES)).isFalse();
            assertThat(bust.has(FixTags.LAST_PX)).isFalse();
        }

        @Test
        @DisplayName("busting the only fill returns the order to NEW with zeroed economics")
        void bustOfTheOnlyFill() {
            FixMessage bust = OrderChain
                    .forTest("UNWOUND", Instrument.AAPL, 4_000, 150.00)
                    .newOrder()
                    .ack()
                    .partialFill(500, 150.10)
                    .bust(1)
                    .events().getLast().message();

            assertThat(bust.value(FixTags.CUM_QTY)).isEqualTo("0");
            assertThat(bust.value(FixTags.LEAVES_QTY)).isEqualTo("4000");
            assertThat(bust.value(FixTags.AVG_PX)).isEqualTo("0");
            assertThat(bust.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.NEW);
            assertThat(bust.value(FixTags.EXEC_TYPE)).isEqualTo(FixTags.ExecType.NEW);
        }

        @Test
        @DisplayName("busting the last fill of a FILLED order reopens it")
        void bustAfterFullFillReopens() {
            FixMessage bust = OrderChain
                    .forTest("REOPENED", Instrument.MSFT, 1_000, 410.00)
                    .newOrder()
                    .ack()
                    .partialFill(400, 410.10)
                    .fill(410.20)
                    .bust(2)
                    .events().getLast().message();

            // Contract edge case 6: FILLED goes back to PARTIALLY_FILLED, and
            // the busted 600 return to the working balance.
            assertThat(bust.value(FixTags.CUM_QTY)).isEqualTo("400");
            assertThat(bust.value(FixTags.LEAVES_QTY)).isEqualTo("600");
            assertThat(bust.value(FixTags.AVG_PX)).isEqualTo("410.1");
            assertThat(bust.value(FixTags.ORD_STATUS))
                    .isEqualTo(FixTags.OrdStatus.PARTIALLY_FILLED);
        }

        @Test
        @DisplayName("a price-only correct moves AvgPx and carries the replacement 32/31")
        void correctPriceOnly() {
            FixMessage correct = OrderChain
                    .forTest("REPRICED", Instrument.META, 3_000, 512.00)
                    .newOrder()
                    .ack()
                    .partialFill(1_200, 512.10)
                    .correct(1, 1_200, 511.95)
                    .events().getLast().message();

            assertThat(correct.value(FixTags.EXEC_TRANS_TYPE))
                    .isEqualTo(FixTags.ExecTransType.CORRECT);
            assertThat(correct.value(FixTags.EXEC_REF_ID)).isEqualTo("EXEC-REPRICED-2");
            // Unlike a bust, a correct DOES carry trade fields: the
            // correcting report's 32/31 become the execution's current values.
            assertThat(correct.value(FixTags.LAST_SHARES)).isEqualTo("1200");
            assertThat(correct.value(FixTags.LAST_PX)).isEqualTo("511.95");
            assertThat(correct.value(FixTags.CUM_QTY)).isEqualTo("1200");
            assertThat(correct.value(FixTags.LEAVES_QTY)).isEqualTo("1800");
            assertThat(correct.value(FixTags.AVG_PX)).isEqualTo("511.95");
        }

        @Test
        @DisplayName("a correct that changes shares restates the whole trio")
        void correctChangingShares() {
            FixMessage correct = OrderChain
                    .forTest("RESIZED", Instrument.GOOG, 2_000, 175.00)
                    .newOrder()
                    .ack()
                    .partialFill(600, 175.05)
                    .partialFill(400, 175.15)
                    .correct(1, 500, 175.05)
                    .events().getLast().message();

            // Survivors: 500 @ 175.05 and 400 @ 175.15.
            // (500 * 175.05 + 400 * 175.15) / 900 = 175.0944 (4dp, HALF_UP)
            assertThat(correct.value(FixTags.CUM_QTY)).isEqualTo("900");
            assertThat(correct.value(FixTags.LEAVES_QTY)).isEqualTo("1100");
            assertThat(correct.value(FixTags.AVG_PX)).isEqualTo("175.0944");
        }

        @Test
        @DisplayName("a fill after a bust continues from the restated totals")
        void subsequentFillContinuesFromRestatement() {
            FixMessage fill = OrderChain
                    .forTest("RESUMED", Instrument.NVDA, 5_000, 121.00)
                    .newOrder()
                    .ack()
                    .partialFill(2_000, 121.10)
                    .bust(1)
                    .partialFill(1_000, 121.20)
                    .events().getLast().message();

            // The busted 2000 never happened: only the new 1000 counts.
            assertThat(fill.value(FixTags.CUM_QTY)).isEqualTo("1000");
            assertThat(fill.value(FixTags.LEAVES_QTY)).isEqualTo("4000");
            assertThat(fill.value(FixTags.AVG_PX)).isEqualTo("121.2");
        }

        @Test
        @DisplayName("a bust or correct that cannot mean anything is rejected loudly")
        void restatementGuards() {
            OrderChain busted = OrderChain.forTest("GUARD-A", Instrument.AAPL, 1_000, 150.00)
                    .newOrder().ack().partialFill(300, 150.00).bust(1);
            assertThatThrownBy(() -> busted.bust(1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already busted");
            assertThatThrownBy(() -> busted.correct(1, 200, 150.00))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> busted.bust(2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no fill #2");

            OrderChain working = OrderChain.forTest("GUARD-B", Instrument.MSFT, 1_000, 410.00)
                    .newOrder().ack().partialFill(300, 410.00);
            assertThatThrownBy(() -> working.correct(1, 1_500, 410.00))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CumQty would be");

            // After a cancel the stopped balance must not resurrect.
            OrderChain cancelled = OrderChain.forTest("GUARD-C", Instrument.GOOG, 1_000, 175.00)
                    .newOrder().ack().partialFill(300, 175.00)
                    .cancelRequest().cancelConfirmed();
            assertThatThrownBy(() -> cancelled.bust(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed out");
        }
    }

    @Test
    @DisplayName("a fill larger than the working balance is rejected, not silently clamped")
    void cannotOverfill() {
        OrderChain chain = OrderChain.forTest("OVERFILL", Instrument.TSLA, 1_000, 242.00)
                .newOrder()
                .ack();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> chain.partialFill(1_500, 242.00));
    }
}
