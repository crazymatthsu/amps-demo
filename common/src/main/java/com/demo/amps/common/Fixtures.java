package com.demo.amps.common;

import com.demo.amps.market.v1.InstrumentSnapshot;
import com.demo.amps.market.v1.Order;
import com.demo.amps.market.v1.OrderStatus;
import com.demo.amps.market.v1.PriceLevel;
import com.demo.amps.market.v1.Quote;
import com.demo.amps.market.v1.ReferenceData;
import com.demo.amps.market.v1.Side;
import java.util.List;
import java.util.Random;

/**
 * Deterministic test data. Everything is seeded so two runs of a demo produce the
 * same records, which makes "restart the server and compare" checks meaningful.
 */
public final class Fixtures {

    public static final List<String> SYMBOLS =
            List.of("AAPL", "MSFT", "GOOG", "AMZN", "TSLA", "NVDA", "META", "JPM", "XOM", "WMT");

    public static final List<String> TRADERS =
            List.of("alice", "bob", "carol", "dave", "erin");

    public static final List<String> DESKS =
            List.of("equities", "rates", "credit");

    private Fixtures() {
    }

    /** A stable order id for index {@code i}: {@code ORD-00042}. */
    public static String orderId(int i) {
        return String.format("ORD-%05d", i);
    }

    /** Builds order {@code i} with values derived only from {@code i}. */
    public static Order order(int i) {
        Random random = new Random(i * 0x9E3779B9L);
        int quantity = 100 * (1 + random.nextInt(50));
        return Order.newBuilder()
                .setOrderId(orderId(i))
                .setSymbol(SYMBOLS.get(i % SYMBOLS.size()))
                .setSide(i % 2 == 0 ? Side.SIDE_BUY : Side.SIDE_SELL)
                .setQuantity(quantity)
                .setFilledQuantity(0)
                .setPrice(round2(10.0 + random.nextDouble() * 490.0))
                .setStatus(OrderStatus.ORDER_STATUS_NEW)
                .setTrader(TRADERS.get(i % TRADERS.size()))
                .setDesk(DESKS.get(i % DESKS.size()))
                .setRevision(1)
                .setUpdatedAtEpochSeconds(epochSeconds())
                .addTags("demo")
                .build();
    }

    /**
     * Applies a partial fill, the canonical "small change to an existing record"
     * used by the delta demos.
     */
    public static Order fill(Order order, int fillQuantity) {
        int filled = Math.min(order.getQuantity(), order.getFilledQuantity() + fillQuantity);
        return order.toBuilder()
                .setFilledQuantity(filled)
                .setStatus(filled >= order.getQuantity()
                        ? OrderStatus.ORDER_STATUS_FILLED
                        : OrderStatus.ORDER_STATUS_PARTIALLY_FILLED)
                .setRevision(order.getRevision() + 1)
                .setUpdatedAtEpochSeconds(epochSeconds())
                .build();
    }

    public static Order cancel(Order order) {
        return order.toBuilder()
                .setStatus(OrderStatus.ORDER_STATUS_CANCELLED)
                .setRevision(order.getRevision() + 1)
                .setUpdatedAtEpochSeconds(epochSeconds())
                .build();
    }

    /**
     * A full instrument snapshot: a large, static {@link ReferenceData} block plus
     * a small, volatile {@link Quote}. Roughly 1.5 KB of JSON, of which fewer than
     * 100 bytes change per tick.
     */
    public static InstrumentSnapshot instrument(String symbol) {
        Random random = new Random(symbol.hashCode());
        double mid = 50.0 + random.nextDouble() * 450.0;

        ReferenceData reference = ReferenceData.newBuilder()
                .setLongName(symbol + " Holdings Incorporated, Class A Common Stock")
                .setExchange("XNAS")
                .setCurrency("USD")
                .setIsin("US" + String.format("%010d", Math.abs(symbol.hashCode() % 1_000_000_000)))
                .setCusip(String.format("%09d", Math.abs(symbol.hashCode() % 1_000_000_000)))
                .setSedol(String.format("%07d", Math.abs(symbol.hashCode() % 10_000_000)))
                .setFigi("BBG00" + Integer.toHexString(Math.abs(symbol.hashCode())).toUpperCase())
                .setSector("Information Technology")
                .setIndustry("Semiconductors and Semiconductor Equipment")
                .setCountry("US")
                .setLotSize(100)
                .setTickSize(0.01)
                .setSettlementCycle("T+1")
                .addAllTradingCalendar(List.of(
                        "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03",
                        "2026-05-25", "2026-06-19", "2026-07-03", "2026-09-07",
                        "2026-11-26", "2026-12-25"))
                .addAllEligibleVenues(List.of(
                        "XNAS", "XNYS", "ARCX", "BATS", "EDGX", "IEXG", "MEMX", "LTSE"))
                .setProspectusUrl("https://example.invalid/filings/" + symbol + "/prospectus-2026.pdf")
                .setLegalEntityIdentifier("5493001KJTIIGC8Y1R" + (Math.abs(symbol.hashCode()) % 100))
                .setShortSaleEligible(true)
                .setOptionsEligible(true)
                .setMarginRate(0.25)
                .build();

        InstrumentSnapshot.Builder builder = InstrumentSnapshot.newBuilder()
                .setSymbol(symbol)
                .setReference(reference)
                .setQuote(quote(mid, 1))
                .setRevision(1)
                .setUpdatedAtEpochSeconds(epochSeconds());

        for (int level = 1; level <= 5; level++) {
            builder.addBook(PriceLevel.newBuilder()
                    .setLevel(level)
                    .setBidPrice(round2(mid - 0.01 * level))
                    .setBidSize(100 * level)
                    .setAskPrice(round2(mid + 0.01 * level))
                    .setAskSize(100 * level)
                    .build());
        }
        return builder.build();
    }

    /**
     * Moves the quote and nothing else. This is the change the delta demos publish:
     * a handful of numbers inside a record dominated by static data.
     */
    public static InstrumentSnapshot tick(InstrumentSnapshot snapshot, Random random) {
        double mid = (snapshot.getQuote().getBid() + snapshot.getQuote().getAsk()) / 2.0;
        double moved = Math.max(1.0, mid + (random.nextDouble() - 0.5));
        return snapshot.toBuilder()
                .setQuote(quote(moved, snapshot.getQuote().getSequence() + 1))
                .setRevision(snapshot.getRevision() + 1)
                .setUpdatedAtEpochSeconds(epochSeconds())
                .build();
    }

    private static Quote quote(double mid, int sequence) {
        return Quote.newBuilder()
                .setBid(round2(mid - 0.01))
                .setAsk(round2(mid + 0.01))
                .setBidSize(500)
                .setAskSize(700)
                .setLast(round2(mid))
                .setLastSize(100)
                .setSequence(sequence)
                .build();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double epochSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }
}
