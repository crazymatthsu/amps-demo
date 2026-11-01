package com.demo.amps.fix42.state.hooks;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.machine.PendingAction;
import com.demo.amps.fix42.state.machine.PendingRequest;
import com.demo.amps.fix42.state.spi.Decision;
import com.demo.amps.fix42.state.spi.OrderStateHook;
import com.demo.amps.fix42.state.spi.OrderStateView;

/**
 * For a venue whose {@code 35=9} carries the order's status <b>at the time of
 * the request</b> rather than after the reject (docs/11 F2). Such a venue
 * acknowledges a cancel with {@code 150=6 39=6}, then rejects it with
 * {@code 39=6} again. The machine rightly refuses that 39 (no CANCEL is left
 * in the set, R6), but nothing else moves the {@code 6} the acknowledgement
 * wrote, so the record would read pending cancel with {@code 12016=0} until the
 * next report.
 *
 * <p>This hook rewrites the reject's 39, before the rules run, to the status
 * the cumulative state implies: {@code 0} when nothing has filled, else
 * {@code 1}. It does so only in that exact shape:
 *
 * <ul>
 *   <li>the message is a {@code 35=9} whose 39 is a pending status,
 *       {@code 6} (a cancel) or {@code E} (a replace);</li>
 *   <li>the record reads that same status, so the pending acknowledgement is
 *       what the reject has to undo;</li>
 *   <li>no request of that kind is left once the one this reject names (by
 *       its 11) is removed: with a second cancel still in flight, {@code 39=6}
 *       is the truth and is left alone;</li>
 *   <li>the order is not terminal, which the machine's terminal guard
 *       handles whatever the 39.</li>
 * </ul>
 *
 * <p>The derivation lives here, not in the machine, because the machine
 * never computes a status (docs/03 §8): this is one venue's convention,
 * switched on for the deployment that connects to it
 * ({@code fix42-order-state.hooks.reject-status-at-request-time}). It needs
 * {@code cancel-reject-status} to adopt a reject's 39 at all, so it does
 * nothing under {@code NEVER}. The audit topics keep the venue's own 39
 * (R20c).
 *
 * <p>Stateless: a pure function of the message and the view. A rewrite
 * allocates a fresh frame, which happens once per rejected request, not per
 * message.
 */
public final class RejectStatusAtRequestTimeHook implements OrderStateHook {

    @Override
    public Decision beforeApply(FixFrame message, OrderStateView state) {
        String derived = derivedStatus(message, state);
        return derived == null ? Decision.proceed() : Decision.proceedWith(withOrdStatus(message, derived));
    }

    /** The 39 to apply instead of the reject's, or null to leave the message alone. */
    static String derivedStatus(FixFrame message, OrderStateView state) {
        if (state == null || state.terminal() || !message.valueEquals(FixTags.MSG_TYPE, "9")) {
            return null;
        }
        String sent = message.value(FixTags.ORD_STATUS);
        PendingAction kind = switch (sent) {
            case FixTags.OrdStatus.PENDING_CANCEL -> PendingAction.CANCEL;
            case FixTags.OrdStatus.PENDING_REPLACE -> PendingAction.REPLACE;
            default -> null;
        };
        if (kind == null || !sent.equals(state.venueStatus())) {
            return null;
        }
        String answered = message.value(FixTags.CL_ORD_ID);
        for (PendingRequest request : state.pending()) {
            if (request.action() == kind && !request.clOrdId().equals(answered)) {
                return null;
            }
        }
        double cumQty = quantity(state.cumQty());
        if (Double.isNaN(cumQty)) {
            return null;
        }
        return cumQty > 0 ? FixTags.OrdStatus.PARTIALLY_FILLED : FixTags.OrdStatus.NEW;
    }

    /** No 14 yet reads as zero; a 14 that does not parse leaves the message alone. */
    private static double quantity(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static FixFrame withOrdStatus(FixFrame message, String ordStatus) {
        FixWriter out = new FixWriter(message.length() + 8);
        for (int i = 0, n = message.count(); i < n; i++) {
            if (message.tagAt(i) == FixTags.ORD_STATUS) {
                out.field(FixTags.ORD_STATUS, ordStatus);
            } else {
                message.copyFieldAt(i, out);
            }
        }
        return new FixFrame().wrap(out);
    }
}
