package com.demo.amps.seqno.fix;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic stream of FIX 4.2 new-order singles, standing in for the
 * business side of a publisher.
 *
 * <p>It produces everything <em>except</em> what the publisher stamps on the
 * way out: no sender (49), no sequence number (8888), no sending time (52).
 * Those belong to {@code SequencedPublisher}, because assigning them is the
 * act the whole design is about, and keeping them out of here makes the
 * boundary visible.
 *
 * <p>ClOrdIDs are numbered from a starting index the caller supplies, so a
 * feed resumed after a restart continues the series rather than repeating it.
 */
public final class OrderFeed {

    private static final String[] SYMBOLS = {"AAPL", "MSFT", "GOOG", "TSLA", "AMZN", "NVDA", "META"};
    private static final String[] PRICES = {"185.25", "410.10", "162.80", "248.50", "178.90", "875.00", "495.30"};
    private static final DateTimeFormatter TRANSACT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final String clOrdIdPrefix;
    private final Instant baseTime;
    private long index;

    /**
     * @param clOrdIdPrefix the stem of every ClOrdID, typically the sender
     * @param startIndex    the number of the first order produced
     * @param baseTime      the TransactTime of the first order; later orders
     *                      advance it by one second each, so runs are
     *                      reproducible
     */
    public OrderFeed(String clOrdIdPrefix, long startIndex, Instant baseTime) {
        this.clOrdIdPrefix = clOrdIdPrefix;
        this.index = startIndex;
        this.baseTime = baseTime;
    }

    /** The next order in the series. */
    public FixMessage next() {
        long n = index++;
        int pick = (int) ((n - 1) % SYMBOLS.length);
        return FixMessage.ofType(FixTags.NEW_ORDER_SINGLE)
                .set(FixTags.CL_ORD_ID, String.format("%s-%06d", clOrdIdPrefix, n))
                .set(FixTags.ACCOUNT, "ACC-DEMO-01")
                .set(FixTags.HANDL_INST, "1")
                .set(FixTags.SYMBOL, SYMBOLS[pick])
                .set(FixTags.SIDE, n % 2 == 1 ? "1" : "2")
                .set(FixTags.ORDER_QTY, 100L * (1 + n % 9))
                .set(FixTags.ORD_TYPE, "2")
                .set(FixTags.PRICE, PRICES[pick])
                .set(FixTags.TIME_IN_FORCE, "0")
                .set(FixTags.CURRENCY, "USD")
                .set(FixTags.TRANSACT_TIME, TRANSACT_TIME.format(baseTime.plusSeconds(n - 1)))
                .build();
    }

    /** The next {@code count} orders. */
    public List<FixMessage> next(int count) {
        List<FixMessage> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            orders.add(next());
        }
        return orders;
    }

    /** The index the next call to {@link #next()} will use. */
    public long nextIndex() {
        return index;
    }
}
