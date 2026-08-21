package com.demo.amps.fix42.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PricesTest {

    @Test
    @DisplayName("renders plain decimals, never scientific notation")
    void neverUsesScientificNotation() {
        // Double.toString(0.0000001) is "1.0E-7", which is not a legal FIX
        // decimal -- the reason this class exists at all.
        assertThat(Prices.plain(0.0000001)).doesNotContain("E").isEqualTo("0");
        assertThat(Prices.plain(12345678.9)).isEqualTo("12345678.9");
    }

    @Test
    @DisplayName("equal prices render identically, so a delta cannot invent a change")
    void equalPricesRenderIdentically() {
        assertThat(Prices.plain(50.20)).isEqualTo(Prices.plain(50.2));
        assertThat(Prices.plain(50.0)).isEqualTo("50");
    }

    @Test
    @DisplayName("average price is volume-weighted across fills, not a mean of prices")
    void averagePriceIsVolumeWeighted() {
        // 200 @ 100 then 800 @ 110 -> 108, not the 105 a simple mean would give.
        double afterFirst = Prices.averagePrice(0, 0.0, 200, 100.0);
        double afterSecond = Prices.averagePrice(200, afterFirst, 800, 110.0);

        assertThat(afterFirst).isEqualTo(100.0);
        assertThat(afterSecond).isEqualTo(108.0);
    }

    @Test
    @DisplayName("average price of nothing is zero, not a division by zero")
    void handlesZeroQuantity() {
        assertThat(Prices.averagePrice(0, 0.0, 0, 50.0)).isEqualTo(0.0);
    }
}
