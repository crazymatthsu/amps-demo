package com.demo.amps.fix42.state.hooks;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.machine.Outcome;
import com.demo.amps.fix42.state.spi.OrderStateHook;
import com.demo.amps.fix42.state.spi.OrderStateView;
import com.demo.amps.fix42.state.spi.RecordFields;

/**
 * The shipped example hook (docs/07 §2): a desk-level status in tag 9100,
 * derived from the venue's 39 and the pending set, for a blotter screen that
 * wants one word per order.
 *
 * <pre>
 *   NEW       not yet accepted by the venue (39=A)
 *   WORKING   accepted, nothing in flight
 *   PENDING   accepted, with a request in flight
 *   CLOSED    terminal, nothing in flight
 *   STUCK     terminal with a request still unanswered -- the venue never
 *             rejected it (the /9016 > 0 AND /9017 = 'Y' case, docs/04 §7)
 * </pre>
 *
 * <p>Stateless and non-blocking, as every hook must be: a pure function of
 * the view the machine hands it. It writes only in the hooks' range; the
 * machine's own fields are out of its reach by construction.
 */
public final class DeskStatusHook implements OrderStateHook {

    /** The tag this hook writes. */
    public static final int DESK_STATUS = 9100;

    @Override
    public void afterApply(FixFrame message, OrderStateView before, OrderStateView after, Outcome outcome,
                           RecordFields extra) {
        extra.put(DESK_STATUS, status(after));
    }

    @Override
    public void onCreate(FixFrame message, OrderStateView state, RecordFields extra) {
        extra.put(DESK_STATUS, status(state));
    }

    static String status(OrderStateView state) {
        boolean pending = !state.pending().isEmpty();
        if (state.terminal()) {
            return pending ? "STUCK" : "CLOSED";
        }
        if (state.venueStatus() == null || "A".equals(state.venueStatus())) {
            return "NEW";
        }
        return pending ? "PENDING" : "WORKING";
    }
}
