package com.demo.amps.fix42.state.spi;

import com.demo.amps.fix42.state.fix.FixFrame;

/**
 * What a {@code beforeApply} hook decides about a message.
 *
 * <p>A normalised frame feeds the machine only: <b>the audit topics always
 * receive the original</b> (R20c).
 */
public final class Decision {

    private static final Decision PROCEED = new Decision(null, null);

    private final String ignoreReason;
    private final FixFrame replacement;

    private Decision(String ignoreReason, FixFrame replacement) {
        this.ignoreReason = ignoreReason;
        this.replacement = replacement;
    }

    public static Decision proceed() {
        return PROCEED;
    }

    /** Leave the record alone; the machine counts it as {@link IgnoreReason#HOOK}. */
    public static Decision ignore(String reason) {
        return new Decision(reason, null);
    }

    /** Apply {@code normalised} instead of the message received. */
    public static Decision proceedWith(FixFrame normalised) {
        return new Decision(null, normalised);
    }

    public boolean ignored() {
        return ignoreReason != null;
    }

    public String ignoreReason() {
        return ignoreReason;
    }

    /** The frame to apply instead, or null. */
    public FixFrame replacement() {
        return replacement;
    }
}
