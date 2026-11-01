package com.demo.amps.fix42.state.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The edge cases of docs/06, as mock-venue scripts: one chain (or the two or
 * three chains a case needs) per row of the coverage matrix a mock venue can
 * express, named by the row id.
 *
 * <p>The scripts are the specification the state machine is tested against
 * (docs/05 phase 1): they fix the exact message sequences, and each chain's
 * own state at the end -- its CumQty, LeavesQty, AvgPx and OrdStatus -- is
 * what an applied feed must converge on under the default policies. They
 * double as the demo the runner publishes after the scripted flow.
 *
 * <p>Every case takes a {@link MockDialect}, so the property tests can run
 * the whole set under each dialect; a case that exists <i>for</i> one dialect
 * forces that switch on whatever it is given.
 *
 * <p>Chain ids are {@code EC-<row>}, so a case's records are one filter away
 * on the blotter: {@code /13011 LIKE '^EC-E1-'}.
 */
public final class EdgeCaseFlow {

    /** After the scripted flow's last chain, so the two never interleave in time. */
    private static final Instant OPEN = Instant.parse("2026-08-21T14:00:00Z");

    private EdgeCaseFlow() {
    }

    /**
     * One row of the matrix.
     *
     * @param id     the docs/06 row id, e.g. {@code E1}
     * @param title  what the row is
     * @param chains builds the case's chains under a dialect
     */
    public record EdgeCase(String id, String title, Function<MockDialect, List<OrderChain>> chains) {

        public List<OrderChain> build(MockDialect dialect) {
            return chains.apply(dialect);
        }

        @Override
        public String toString() {
            return id + " " + title;
        }
    }

    /** Every case's chains under the default dialect. */
    public static List<OrderChain> chains() {
        return chains(MockDialect.DEFAULT);
    }

    /** Every case's chains under {@code dialect}. */
    public static List<OrderChain> chains(MockDialect dialect) {
        List<OrderChain> all = new ArrayList<>();
        cases().forEach(edgeCase -> all.addAll(edgeCase.build(dialect)));
        return List.copyOf(all);
    }

    /** Every case's messages under the default dialect, chain after chain. */
    public static List<FixEvent> events() {
        List<FixEvent> all = new ArrayList<>();
        chains().forEach(chain -> all.addAll(chain.events()));
        return List.copyOf(all);
    }

    /** The scripted flow's ten chains, then every edge case: what the demo runner publishes. */
    public static List<FixEvent> scriptedThenEdgeCases() {
        List<FixEvent> all = new ArrayList<>(MockFixFlow.events());
        all.addAll(events());
        return List.copyOf(all);
    }

    public static List<EdgeCase> cases() {
        return List.of(
                // ---- normal lifecycles the scripted flow does not have ----
                of("N6", "rejected before working", d -> one(chain("N6", d, 200, 50.0)
                        .newOrder().reject("1", "unknown symbol"))),
                of("N7", "done for day", d -> one(chain("N7", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(400, 50.0).doneForDay())),
                of("N8", "GTD expired", d -> one(chain("N8", d, 1_000, 50.0, "6")
                        .newOrder().ack().partialFill(400, 50.0).expire())),
                of("N13", "unsolicited cancel", d -> one(chain("N13", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(250, 50.0).cancelConfirmed())),
                of("N14", "IOC filled by its first report", d -> one(chain("N14", d, 500, 50.0, "3")
                        .newOrder().fill(50.0))),
                of("N15", "IOC canceled by its first report", d -> one(chain("N15", d, 500, 50.0, "3")
                        .newOrder().cancelConfirmed())),
                of("N16", "pending new, then the ack", d -> one(chain("N16", d, 1_000, 50.0)
                        .newOrder().pendingNew().ack().partialFill(100, 50.0))),
                of("N18", "a 150=I status response", d -> one(chain("N18", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(300, 50.0).statusResponse())),
                of("N19", "an amend from limit to market", d -> one(chain("N19", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(200, 50.0).amendToMarket(1_000).amendAck()
                        .partialFill(300, 50.05))),

                // ---- requests racing their answers ----
                of("E1", "two cancels, confirm then reject", d -> {
                    OrderChain c = chain("E1", d, 1_000, 50.0).newOrder().ack().partialFill(300, 50.0);
                    return one(c.cancelRequest().cancelRequestNaming(c.rootClOrdId())
                            .cancelConfirmed(id(c, 2)).cancelRejectFor(id(c, 3), "0", "too late to cancel"));
                }),
                of("E2", "two cancels, reject then confirm", d -> {
                    OrderChain c = chain("E2", d, 1_000, 50.0).newOrder().ack().partialFill(300, 50.0);
                    return one(c.cancelRequest().cancelRequestNaming(c.rootClOrdId())
                            .cancelRejectFor(id(c, 3), "3", "already pending").cancelConfirmed(id(c, 2)));
                }),
                of("E3", "a third cancel after the order is canceled", d -> {
                    OrderChain c = chain("E3", d, 1_000, 50.0).newOrder().ack().partialFill(300, 50.0);
                    return one(c.cancelRequest().cancelRequestNaming(c.rootClOrdId())
                            .cancelConfirmed(id(c, 2)).cancelRejectFor(id(c, 3), "0", "too late")
                            .cancelRequest().cancelRejectFor(id(c, 4), "0", "already canceled"));
                }),
                of("E4", "amend and cancel in flight, the cancel rejected", d -> {
                    OrderChain c = chain("E4", d, 10_000, 185.5).newOrder().ack();
                    return one(c.amend(12_000, 185.75).cancelRequest()
                            .cancelRejectFor(id(c, 3), "3", "pending replace").amendAck(id(c, 2)));
                }),
                of("E5", "two amends in flight, each answered", d -> {
                    OrderChain c = chain("E5", d, 10_000, 100.0).newOrder().ack().partialFill(1_000, 100.0);
                    return one(c.amend(12_000, 100.10).amend(13_000, 100.20)
                            .amendAck(id(c, 2)).amendAck(id(c, 3)).partialFill(2_000, 100.20));
                }),
                of("E6", "a 150=5 before its G", d -> one(chain("E6", d, 1_000, 50.0)
                        .newOrder().ack().amend(1_200, 50.10).amendAck().deliverLastTwoSwapped())),
                of("E7", "a 150=4 before its F", d -> one(chain("E7", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(100, 50.0).cancelRequest().cancelConfirmed()
                        .deliverLastTwoSwapped())),
                of("E9", "a cancel confirm echoing the original id", d -> one(
                        chain("E9", d.confirmsEchoOriginalId(true), 1_000, 50.0)
                                .newOrder().ack().partialFill(300, 50.0).cancelRequest().cancelConfirmed())),
                of("E10", "a replace confirm echoing the original id", d -> one(
                        chain("E10", d.confirmsEchoOriginalId(true), 1_000, 50.0)
                                .newOrder().ack().amend(1_500, 50.20).amendAck().partialFill(500, 50.20))),
                of("E11", "a 35=9 for a request the tap never saw", d -> one(chain("E11", d, 1_000, 50.0)
                        .newOrder().ack().cancelRequest().dropLast().cancelReject("F", "0", "too late"))),
                of("E12", "a 35=9 with 37=NONE", d -> one(chain("E12", d, 1_000, 50.0)
                        .newOrder().cancelRequest().cancelReject("F", "1", "unknown order").ack())),
                of("E13", "an amend after the full fill", d -> one(chain("E13", d, 1_000, 50.0)
                        .newOrder().ack().fill(50.0).amend(1_200, 50.10)
                        .cancelReject("G", "0", "already filled"))),
                of("E14", "a request the venue never answers", d -> one(chain("E14", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(300, 50.0).cancelRequest().doneForDay())),
                of("E15", "a fill while a cancel is pending, under the cancel's id", d -> one(
                        chain("E15", d, 1_000, 50.0).newOrder().ack().cancelRequest().pendingCancel()
                                .partialFill(300, 50.0).cancelReject("F", "0", "too late to cancel"))),
                of("E16", "an amend rejected (35=9, or 150=8 in that dialect)", d -> {
                    OrderChain c = chain("E16", d, 10_000, 185.5).newOrder().ack().partialFill(2_000, 185.5);
                    return one(c.amend(12_000, 185.75).amendReject(id(c, 2), "0", "price out of band"));
                }),
                of("E16a", "an amend reject for a request the tap never saw", d -> {
                    OrderChain c = chain("E16a", d, 10_000, 185.5).newOrder().ack().partialFill(2_000, 185.5);
                    return one(c.amend(12_000, 185.75).dropLast()
                            .amendReject(id(c, 2), "0", "price out of band"));
                }),
                of("E17", "a pending cancel ack overtaking its F", d -> one(chain("E17", d, 1_000, 50.0)
                        .newOrder().ack().cancelRequest().pendingCancel().deliverLastTwoSwapped()
                        .cancelConfirmed())),
                of("E17a", "a pending replace ack naming the working id, overtaking its G", d -> one(
                        chain("E17a", d.pendingAcksEchoWorkingId(true), 1_000, 50.0)
                                .newOrder().ack().amend(1_200, 50.10).pendingReplace()
                                .deliverLastTwoSwapped().amendAck())),
                of("E18", "a 35=9 carrying 39=4 when the 150=4 was never seen", d -> {
                    OrderChain c = chain("E18", d, 1_000, 50.0).newOrder().ack().partialFill(300, 50.0);
                    return one(c.cancelRequest().cancelRequestNaming(c.rootClOrdId())
                            .cancelConfirmed(id(c, 2)).dropLast()
                            .cancelRejectFor(id(c, 3), "0", "already canceled"));
                }),

                // ---- reports racing each other ----
                of("E20", "a late fill after the cancel confirm, resent, then an old fill", d -> one(
                        chain("E20", d, 1_000, 50.0).newOrder().ack().partialFill(300, 49.9)
                                .cancelRequest().cancelConfirmed().lateFill(200, 50.0)
                                .resendLastReport().redeliver(2))),
                of("E21", "a fill the cancel already counted, arriving after it", d -> one(
                        chain("E21", d, 1_000, 50.0).newOrder().ack().partialFill(300, 50.0)
                                .cancelRequest().partialFill(200, 50.10).cancelConfirmed()
                                .deliverLastTwoSwapped())),
                of("E22", "an older fill redelivered after the cancel", d -> one(
                        chain("E22", d, 1_000, 50.0).newOrder().ack().partialFill(300, 50.0)
                                .partialFill(200, 50.10).cancelRequest().cancelConfirmed().redeliver(2))),
                of("E23", "a pending cancel ack overtaken by the confirm", d -> one(
                        chain("E23", d, 1_000, 50.0).newOrder().ack().partialFill(300, 50.0)
                                .cancelRequest().pendingCancel().cancelConfirmed().deliverLastTwoSwapped())),
                of("E24", "a bust after the full fill reopens", d -> one(chain("E24", d, 3_000, 50.0)
                        .newOrder().ack().partialFill(1_200, 50.0).fill(50.10).bust(1))),
                of("E25", "a correct after the full fill", d -> one(chain("E25", d, 3_000, 50.0)
                        .newOrder().ack().partialFill(1_200, 50.0).fill(50.10).correct(1, 1_200, 49.95))),
                of("E26", "a bust of the last fill after the cancel", d -> one(
                        chain("E26", d.bustAfterCloseOut(true), 1_000, 50.0).newOrder().ack()
                                .partialFill(500, 50.0).partialFill(300, 50.20).cancelRequest()
                                .cancelConfirmed().bust(2))),
                of("E27", "a correct after the cancel", d -> one(
                        chain("E27", d.bustAfterCloseOut(true), 1_000, 50.0).newOrder().ack()
                                .partialFill(500, 50.0).partialFill(300, 50.20).cancelRequest()
                                .cancelConfirmed().correct(1, 500, 49.95))),
                of("E29", "done for day after the cancel", d -> one(chain("E29", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(300, 50.0).cancelConfirmed().doneForDay())),
                of("E30", "a PossDup resend of the last report", d -> one(chain("E30", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(300, 50.0).resendLastReport().partialFill(200, 50.0))),
                of("E31", "old reports redelivered while a cancel is pending", d -> one(
                        chain("E31", d, 1_000, 50.0).newOrder().ack().partialFill(500, 50.0)
                                .partialFill(300, 50.10).cancelRequest().pendingCancel()
                                .redeliverAsStatus(3).redeliver(1)
                                .cancelReject("F", "0", "too late to cancel"))),
                of("E31a", "a bust redelivered", d -> one(chain("E31a", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(500, 50.0).partialFill(300, 50.10).bust(1)
                        .redeliverLastBust())),
                of("E33", "20=3 status reports", d -> one(chain("E33", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(300, 50.0).statusReport().partialFill(200, 50.0)
                        .statusReport())),
                of("E35", "a replace reported as cancel-then-new", d -> one(
                        chain("E35", d.replaceAsCancelThenNew(true), 10_000, 50.0).newOrder().ack()
                                .partialFill(2_000, 50.0).amend(12_000, 50.10).amendAck()
                                .partialFill(1_000, 50.10))),
                of("E36", "a re-ack after an erroneous cancel", d -> one(chain("E36", d, 1_000, 50.0)
                        .newOrder().ack().partialFill(300, 50.0).cancelConfirmed().reack()
                        .partialFill(200, 50.0))),
                of("E37", "an IOC ack overtaken by its cancel, in whole seconds", d -> one(
                        chain("E37", d.transactTimeInSeconds(true), 500, 50.0, "3").newOrder().ack()
                                .cancelConfirmed().deliverLastTwoSwapped())),

                // ---- mid-stream and identity ----
                of("E40", "reports before the D, then the late D", d -> one(chain("E40", d, 500, 10.0)
                        .withoutNewOrder().ack().partialFill(100, 10.0).lateNewOrder())),
                of("E42", "a resent D", d -> one(chain("E42", d, 1_000, 50.0)
                        .newOrder().ack().resendNewOrder().partialFill(300, 50.0))),
                of("E43", "two replaces missed; the confirm resolves by 37", d -> one(
                        chain("E43", d, 1_000, 50.0).newOrder().ack()
                                .amend(1_100, 50.05).dropLast().amendAck().dropLast()
                                .amend(1_200, 50.10).dropLast().amendAck().partialFill(100, 50.10))),
                of("E44", "the venue rotates OrderID across replaces", d -> one(
                        chain("E44", d.rotateOrderIdOnReplace(true), 1_000, 50.0).newOrder().ack()
                                .amend(1_200, 50.10).amendAck().partialFill(300, 50.10))),
                of("E57", "an OrderID equal to another order's ClOrdID", d -> List.of(
                        chain("E57A", d, 100, 50.0).withRootClOrdId("123456").newOrder().ack(),
                        chain("E57B", d, 500, 50.0).withOrderId("123456").withoutNewOrder().ack()
                                .partialFill(100, 50.0))),
                of("E58", "a G whose 37 names another order; a D carrying 41", d -> {
                    OrderChain other = chain("E58B", d, 100, 50.0).newOrder().ack();
                    OrderChain main = chain("E58A", d, 1_000, 50.0).newOrder().ack()
                            .amendWithOrderId(other.orderId(), 1_200, 50.10).amendAck();
                    OrderChain linked = chain("E58C", d, 300, 50.0).linkedTo(main.rootClOrdId())
                            .newOrder().ack();
                    return List.of(other, main, linked);
                }),
                of("E59", "ClOrdIDs containing : ; , and %", d -> List.of(
                        new OrderChain("EC-E59:DESK7;A,B%1", OrderScope.PARENT, null, Instrument.MSFT,
                                "ACC-EDGE", "EDGE", "1", 1_000, 50.0, "0", start("E59")).dialect(d)
                                .newOrder().ack().amend(1_200, 50.10).amendAck().cancelRequest()
                                .cancelConfirmed())),
                of("E60", "a D carrying session header tags and tags in the machine's ranges", d -> one(
                        chain("E60", d, 1_000, 50.0)
                                .withNewOrderTag(8, "FIX.4.2").withNewOrderTag(34, "17")
                                .withNewOrderTag(49, "OMS").withNewOrderTag(56, "VENUE")
                                .withNewOrderTag(9015, "NONE").withNewOrderTag(9050, "venue")
                                .withNewOrderTag(13011, "BOGUS").withNewOrderTag(15038, "1")
                                .newOrder().ack().partialFill(300, 50.0))),
                of("E61", "a pegged child re-priced a thousand times", d -> one(chain("E61", d, 1_000, 50.0)
                        .newOrder().ack().amendRepeatedly(1_000).fill(60.0))));
    }

    // ---- helpers --------------------------------------------------------------

    private static EdgeCase of(String id, String title, Function<MockDialect, List<OrderChain>> chains) {
        return new EdgeCase(id, title, chains);
    }

    private static List<OrderChain> one(OrderChain chain) {
        return List.of(chain);
    }

    /** The {@code n}-th ClOrdID the chain minted: 1 is the D's. */
    private static String id(OrderChain chain, int n) {
        return chain.chainId() + "-" + n;
    }

    private static OrderChain chain(String row, MockDialect dialect, long qty, double price) {
        return chain(row, dialect, qty, price, "0");
    }

    private static OrderChain chain(String row, MockDialect dialect, long qty, double price,
                                    String timeInForce) {
        return new OrderChain("EC-" + row, OrderScope.PARENT, null, Instrument.MSFT, "ACC-EDGE",
                "EDGE", "1", qty, price, timeInForce, start(row)).dialect(dialect);
    }

    /** A minute apart per row, from the row id's hash, so a chain built alone keeps its timestamps. */
    private static Instant start(String row) {
        return OPEN.plusSeconds(60L * Math.floorMod(row.hashCode(), 600));
    }
}
