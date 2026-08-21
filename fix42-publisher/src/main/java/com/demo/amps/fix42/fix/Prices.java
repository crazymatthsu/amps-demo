package com.demo.amps.fix42.fix;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decimal rendering and arithmetic for FIX price fields.
 *
 * <p>FIX values are text. Two rules follow, and both bite in practice:
 * scientific notation is not legal in a FIX decimal field (so
 * {@code Double.toString} is unusable for small or large values), and prices
 * that are numerically equal must render identically, or a delta publish
 * "changes" a field that did not change.
 *
 * <p>Average price gets its own function because it is the one number in a
 * mock feed that is easy to make internally inconsistent -- and an AvgPx that
 * does not match its own fills is exactly the sort of thing a demo should not
 * quietly ship.
 */
public final class Prices {

    /** Prices round to four decimal places, which covers every venue here. */
    private static final int PRICE_SCALE = 4;

    private Prices() {
    }

    /** A double as plain FIX decimal text: no exponent, no trailing zeros. */
    public static String plain(double value) {
        return BigDecimal.valueOf(value)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Volume-weighted average price after adding a fill.
     *
     * <p>{@code (previousCumQty * previousAvgPx + lastShares * lastPx) / newCumQty},
     * which is what a venue's own matching engine publishes on tag 6 -- the
     * cumulative average over all fills, not the average of the fill prices.
     */
    public static double averagePrice(long previousCumQty, double previousAvgPx,
                                      long lastShares, double lastPx) {
        long newCumQty = previousCumQty + lastShares;
        if (newCumQty <= 0) {
            return 0.0;
        }
        BigDecimal previousNotional = BigDecimal.valueOf(previousCumQty)
                .multiply(BigDecimal.valueOf(previousAvgPx));
        BigDecimal fillNotional = BigDecimal.valueOf(lastShares)
                .multiply(BigDecimal.valueOf(lastPx));
        return previousNotional.add(fillNotional)
                .divide(BigDecimal.valueOf(newCumQty), PRICE_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
