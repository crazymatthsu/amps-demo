package com.demo.amps.fix42.state.machine;

/**
 * FIX UTCTimestamp comparison, aware of second-only resolution.
 *
 * <p>FIX 4.2 allows tag 60 as {@code yyyyMMdd-HH:mm:ss} or with
 * {@code .sss}. A venue that sends whole seconds makes two reports within one
 * second indistinguishable, so whenever either side lacks milliseconds the
 * comparison is made on whole seconds: "earlier" and "later" then mean by a
 * whole second (decision 1, S4). A value that does not parse is treated as
 * absent, and a guard with nothing to compare is skipped.
 */
final class Timestamps {

    private Timestamps() {
    }

    /** Epoch milliseconds, or {@code Long.MIN_VALUE} when absent or malformed. */
    static long millis(String text) {
        if (text == null || text.length() < 17 || text.charAt(8) != '-'
                || text.charAt(11) != ':' || text.charAt(14) != ':') {
            return Long.MIN_VALUE;
        }
        try {
            int year = Integer.parseInt(text, 0, 4, 10);
            int month = Integer.parseInt(text, 4, 6, 10);
            int day = Integer.parseInt(text, 6, 8, 10);
            int hour = Integer.parseInt(text, 9, 11, 10);
            int minute = Integer.parseInt(text, 12, 14, 10);
            int second = Integer.parseInt(text, 15, 17, 10);
            int millis = 0;
            if (text.length() > 17) {
                if (text.charAt(17) != '.' || text.length() < 19) {
                    return Long.MIN_VALUE;
                }
                String fraction = (text.substring(18) + "000").substring(0, 3);
                millis = Integer.parseInt(fraction);
            }
            long days = java.time.LocalDate.of(year, month, day).toEpochDay();
            return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1_000L + millis;
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    static boolean present(String text) {
        return millis(text) != Long.MIN_VALUE;
    }

    private static boolean hasMillis(String text) {
        return text.length() > 17;
    }

    /** {@code a} is strictly earlier than {@code b}; false when either is absent. */
    static boolean earlier(String a, String b) {
        long x = millis(a);
        long y = millis(b);
        if (x == Long.MIN_VALUE || y == Long.MIN_VALUE) {
            return false;
        }
        if (!hasMillis(a) || !hasMillis(b)) {
            return Math.floorDiv(x, 1_000L) < Math.floorDiv(y, 1_000L);
        }
        return x < y;
    }

    /** {@code a} is strictly later than {@code b}; false when either is absent. */
    static boolean later(String a, String b) {
        return earlier(b, a);
    }

    /** The later of the two, keeping {@code current} on a tie; an absent value never wins. */
    static String max(String current, String candidate) {
        long x = millis(current);
        long y = millis(candidate);
        if (y == Long.MIN_VALUE) {
            return current;
        }
        return x == Long.MIN_VALUE || y > x ? candidate : current;
    }
}
