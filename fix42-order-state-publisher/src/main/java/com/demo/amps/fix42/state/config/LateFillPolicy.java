package com.demo.amps.fix42.state.config;

/**
 * A fill with a higher CumQty on an order the venue already closed out: a
 * trade it matched before the close and reports after it (docs/03 §4, §7.12).
 */
public enum LateFillPolicy {

    /**
     * Adopt 14, 6, 38, 17, 150, 32, 31, 30 and 9028; keep 39 terminal and 151
     * at 0; count it in 9026. A trade is money, and the status is the venue's
     * to reopen. The default.
     */
    APPLY_ECONOMICS,

    /** Apply the whole report, 39 and 151 included: a venue whose late fill genuinely reopens. */
    ADOPT_VENUE,

    /** Drop it: a venue known to resend fills already inside the cancel's CumQty under a new ExecID. */
    IGNORE
}
