package com.demo.amps.fix42.state.config;

/**
 * A non-fill report with a non-terminal 39 on a terminal order: a venue
 * saying the order is working again (docs/03 §4, §7.16).
 */
public enum NonFillAfterTerminalPolicy {

    /**
     * A venue-asserted reopen when the report answers a pending request, or
     * is newer than 9029 -- by a whole second when either timestamp lacks
     * milliseconds; never for an IOC/FOK that answers nothing. The default.
     */
    ADOPT_VENUE_IF_LATER,

    /** Reopen without the time test; IOC/FOK still excluded. */
    ADOPT_VENUE,

    /** Never reopen: a tap merging several clocks (docs/07 §1). */
    IGNORE
}
