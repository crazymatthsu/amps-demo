package com.demo.amps.fix42.state.machine;

/**
 * Numeric comparison of FIX quantity and price text.
 *
 * <p>The cache keeps the venue's text, so the record shows exactly what was
 * sent; every comparison the rules make goes through here, so {@code 300},
 * {@code 300.0} and {@code 0300} compare equal (docs/02 §3.1). A value that
 * does not parse compares as absent: a guard that cannot read a number does
 * not drop the report.
 */
final class Quantities {

    private Quantities() {
    }

    /** The value, or {@code NaN} when absent or not a number. */
    static double parse(String text) {
        if (text == null || text.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static boolean present(String text) {
        return !Double.isNaN(parse(text));
    }

    /** {@code a < b}; false when either is absent. */
    static boolean lower(String a, String b) {
        double x = parse(a);
        double y = parse(b);
        return !Double.isNaN(x) && !Double.isNaN(y) && x < y;
    }

    /** {@code a > b}; false when either is absent. */
    static boolean higher(String a, String b) {
        double x = parse(a);
        double y = parse(b);
        return !Double.isNaN(x) && !Double.isNaN(y) && x > y;
    }

    /** {@code a == b} numerically; false when either is absent. */
    static boolean equal(String a, String b) {
        double x = parse(a);
        double y = parse(b);
        return !Double.isNaN(x) && !Double.isNaN(y) && x == y;
    }
}
