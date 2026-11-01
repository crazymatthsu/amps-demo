package com.demo.amps.fix42.state.config;

/**
 * What a {@code 35=9}'s tag 39 may do to the record: the one policy decision
 * about status (docs/03 §4). The same rule handles a {@code 150=8} that turns
 * out to reject a request rather than the order.
 *
 * <p>Under every policy a terminal 39 adopted from a reject carries a report's
 * consequences: {@code 151=0}, {@code 12017=Y}, and {@code terminalFromReject}.
 */
public enum CancelRejectStatusPolicy {

    /**
     * Adopted unless the order is terminal, the value is {@code 8}, or the
     * value is a pending status ({@code A}, {@code 6}, {@code E}) with no
     * request of that kind left in the set. The default.
     */
    WHEN_NOT_TERMINAL,

    /** Ignored: a venue whose 9s always carry the status at request time. */
    NEVER,

    /** Adopted as sent: a venue known to send the spec's meaning precisely. */
    ALWAYS
}
