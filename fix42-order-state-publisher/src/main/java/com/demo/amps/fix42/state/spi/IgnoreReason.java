package com.demo.amps.fix42.state.spi;

/** Why the machine left the record alone (docs/03 §2). */
public enum IgnoreReason {

    /** An ExecID already applied, or a bust/correct whose 19 is already in 12031. */
    DUPLICATE,
    /** A non-reopening report whose CumQty went backwards. */
    STALE,
    /** Overtaken: an older TransactTime than 12029, or a non-fill after a terminal report that does not reopen. */
    LATE,
    /** A pending acknowledgement on an order the venue has since closed. */
    ABSORBED,
    /** A late execution under {@code late-fill-after-terminal: IGNORE}. */
    LATE_FILL,
    /** A request already in the set as requested, or a D the chain already has. */
    DUPLICATE_REQUEST,
    /** A request the venue has already answered. */
    LATE_REQUEST,
    /** A {@code 35=9} naming nothing the index knows; it never creates a chain. */
    UNMATCHED,
    /** Every key candidate is already another chain's key. */
    KEY_CONFLICT,
    /** A message type no rule handles. */
    UNROUTED,
    /** A message without the id its type needs (a D or a request without 11). */
    NO_ID,
    /** A hook vetoed it. */
    HOOK
}
