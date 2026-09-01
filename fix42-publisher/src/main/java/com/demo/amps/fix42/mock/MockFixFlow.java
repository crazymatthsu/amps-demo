package com.demo.amps.fix42.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The scripted FIX 4.2 flow this demo publishes: nine order chains covering
 * every message type, both order scopes, and every execution outcome the
 * publisher has a routing rule for -- trade busts and corrects included.
 *
 * <p>Deterministic by construction -- no randomness, no wall clock -- so the
 * integration test can assert exact stored records, and two runs against a
 * fresh instance produce identical SOW contents.
 *
 * <table>
 *   <caption>Scenarios</caption>
 *   <tr><th>chain</th><th>scope</th><th>exercises</th></tr>
 *   <tr><td>PARENT-AAPL</td><td>parent</td>
 *       <td>D, ack, partial, G amend, replace ack, partial, full fill</td></tr>
 *   <tr><td>PARENT-MSFT</td><td>parent</td>
 *       <td>D, ack, partial, F cancel, pending cancel, cancel confirmed</td></tr>
 *   <tr><td>PARENT-GOOG</td><td>parent</td>
 *       <td>D, ack, F cancel, 35=9 cancel reject (too late to cancel)</td></tr>
 *   <tr><td>PARENT-NVDA</td><td>parent</td>
 *       <td>D, ack, partial, done for day -- and an amend rejected by a 35=9</td></tr>
 *   <tr><td>PARENT-TSLA</td><td>parent</td>
 *       <td>D, ack, partials as its children fill, done for day</td></tr>
 *   <tr><td>CHILD-TSLA-A / -B</td><td>child</td>
 *       <td>tag 9000 to the parent; A amends then fills, B is cancelled</td></tr>
 *   <tr><td>PARENT-AMZN</td><td>parent</td>
 *       <td>D, ack, two partials, then a trade bust (20=1) of the first</td></tr>
 *   <tr><td>PARENT-META</td><td>parent</td>
 *       <td>D, ack, partial, trade correct (20=2) of its price, full fill</td></tr>
 * </table>
 */
public final class MockFixFlow {

    /** A fixed origin so generated TransactTimes never depend on when this runs. */
    private static final Instant OPEN = Instant.parse("2026-08-21T13:30:00Z");

    private MockFixFlow() {
    }

    /** Every scenario's messages, in publication order. */
    public static List<FixEvent> events() {
        List<FixEvent> all = new ArrayList<>();
        chains().forEach(chain -> all.addAll(chain.events()));
        return List.copyOf(all);
    }

    /** The chains themselves, for tests that assert on final chain state. */
    public static List<OrderChain> chains() {
        return List.of(
                appleAmendedAndFilled(),
                microsoftCancelled(),
                googleCancelRejected(),
                nvidiaAmendRejectedThenDoneForDay(),
                teslaParent(),
                teslaChildAmendedAndFilled(),
                teslaChildCancelled(),
                amazonFillBusted(),
                metaFillCorrected());
    }

    /**
     * Amend chain that ends fully filled. Three ClOrdIDs
     * ({@code PARENT-AAPL-1..3}) resolve to one AMPS record, which is the
     * chaining key generator's whole purpose.
     */
    private static OrderChain appleAmendedAndFilled() {
        return parent("PARENT-AAPL", Instrument.AAPL, "ACC-INSTL-01", "TRADER-AH", "1",
                10_000, 185.50, "0")
                .newOrder()
                .ack()
                .partialFill(2_000, 185.45)
                .amend(12_000, 185.75)
                .pendingReplace()
                .amendAck()
                .partialFill(3_000, 185.60)
                .fill(185.70);
    }

    /** Working order cancelled after a partial fill. */
    private static OrderChain microsoftCancelled() {
        return parent("PARENT-MSFT", Instrument.MSFT, "ACC-INSTL-01", "TRADER-AH", "2",
                5_000, 410.25, "0")
                .newOrder()
                .ack()
                .partialFill(1_500, 410.20)
                .cancelRequest()
                .pendingCancel()
                .cancelConfirmed();
    }

    /** Cancel arrives too late; the venue rejects it and the order keeps working. */
    private static OrderChain googleCancelRejected() {
        return parent("PARENT-GOOG", Instrument.GOOG, "ACC-HEDGE-07", "TRADER-BK", "1",
                800, 175.10, "0")
                .newOrder()
                .ack()
                .cancelRequest()
                .cancelReject("F", "0", "too late to cancel");
    }

    /**
     * An amend rejected (434=2), then the balance goes done-for-day. The reject
     * matters because it is the case where the chained blotter and a real state
     * machine disagree: the record keeps the rejected request's proposed terms.
     */
    private static OrderChain nvidiaAmendRejectedThenDoneForDay() {
        return parent("PARENT-NVDA", Instrument.NVDA, "ACC-HEDGE-07", "TRADER-BK", "1",
                4_000, 121.80, "0")
                .newOrder()
                .ack()
                .partialFill(1_000, 121.75)
                .amend(6_000, 122.10)
                .cancelReject("G", "2", "order already in pending status")
                .doneForDay();
    }

    /** The parent of the two TSLA children: filled by its slices, then closed out. */
    private static OrderChain teslaParent() {
        return parent("PARENT-TSLA", Instrument.TSLA, "ACC-INSTL-02", "TRADER-CM", "1",
                20_000, 242.00, "0")
                .newOrder()
                .ack()
                .partialFill(5_000, 242.05)
                .partialFill(4_000, 242.10)
                .doneForDay();
    }

    /** Child slice A: amended, then filled in full. */
    private static OrderChain teslaChildAmendedAndFilled() {
        return child("CHILD-TSLA-A", "PARENT-TSLA-1", Instrument.TSLA, "ACC-INSTL-02",
                "ALGO-VWAP", "1", 12_000, 242.00, "0")
                .newOrder()
                .ack()
                .partialFill(5_000, 242.05)
                .amend(12_000, 242.15)
                .amendAck()
                .fill(242.12);
    }

    /** Child slice B: partially filled, then pulled. */
    private static OrderChain teslaChildCancelled() {
        return child("CHILD-TSLA-B", "PARENT-TSLA-1", Instrument.TSLA, "ACC-INSTL-02",
                "ALGO-VWAP", "1", 8_000, 242.00, "0")
                .newOrder()
                .ack()
                .partialFill(4_000, 242.10)
                .cancelRequest()
                .cancelConfirmed();
    }

    /**
     * A fill busted by the venue (20=1). The chain is deliberately left
     * WORKING after the bust, so the stored blotter record pins the restated
     * absolutes directly rather than whatever a later event overwrote.
     */
    private static OrderChain amazonFillBusted() {
        return parent("PARENT-AMZN", Instrument.AMZN, "ACC-INSTL-01", "TRADER-AH", "1",
                6_000, 210.00, "0")
                .newOrder()
                .ack()
                .partialFill(2_000, 209.95)
                .partialFill(1_000, 210.05)
                .bust(1);
    }

    /**
     * A fill corrected by the venue (20=2): same shares, new price, then
     * filled out. The terminal AvgPx is only right if the correction applied
     * -- 511.98 with it, 512.04 without.
     */
    private static OrderChain metaFillCorrected() {
        return parent("PARENT-META", Instrument.META, "ACC-HEDGE-07", "TRADER-BK", "2",
                3_000, 512.00, "0")
                .newOrder()
                .ack()
                .partialFill(1_200, 512.10)
                .correct(1, 1_200, 511.95)
                .fill(512.00);
    }

    // ---- chain factories ----------------------------------------------------

    private static OrderChain parent(String chainId, Instrument instrument, String account,
                                     String clientId, String side, long qty, double price,
                                     String timeInForce) {
        return new OrderChain(chainId, OrderScope.PARENT, null, instrument, account, clientId,
                side, qty, price, timeInForce, startOf(chainId));
    }

    private static OrderChain child(String chainId, String parentClOrdId, Instrument instrument,
                                    String account, String clientId, String side, long qty,
                                    double price, String timeInForce) {
        return new OrderChain(chainId, OrderScope.CHILD, parentClOrdId, instrument, account,
                clientId, side, qty, price, timeInForce, startOf(chainId));
    }

    /**
     * Each chain starts a minute after the previous one, derived from its
     * position in {@link #chainOrder()} rather than a counter, so a chain
     * generated on its own carries the same timestamps as it does in a full run.
     */
    private static Instant startOf(String chainId) {
        int index = chainOrder().indexOf(chainId);
        return OPEN.plusSeconds(60L * Math.max(index, 0));
    }

    private static List<String> chainOrder() {
        return Stream.of("PARENT-AAPL", "PARENT-MSFT", "PARENT-GOOG", "PARENT-NVDA",
                "PARENT-TSLA", "CHILD-TSLA-A", "CHILD-TSLA-B",
                "PARENT-AMZN", "PARENT-META").toList();
    }
}
