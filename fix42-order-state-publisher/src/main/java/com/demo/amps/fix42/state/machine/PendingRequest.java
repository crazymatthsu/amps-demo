package com.demo.amps.fix42.state.machine;

/**
 * One request the venue has not answered (docs/02 §4.1).
 *
 * @param clOrdId   the request's own tag 11
 * @param action    NEW, REPLACE or CANCEL
 * @param requested {@code true} when the request itself was seen; {@code false}
 *                  when only the venue's pending acknowledgement of it was
 *                  (an <i>inferred</i> entry)
 * @param qty       a REPLACE's proposed OrderQty as sent, or {@code null} when
 *                  nothing was proposed
 * @param px        a REPLACE's proposed Price as sent, or {@code null} -- a
 *                  replace to market proposes none, and "no proposal" must not
 *                  read as a price of zero (S17)
 */
public record PendingRequest(String clOrdId, PendingAction action, boolean requested, String qty,
                             String px) {

    static PendingRequest requested(String clOrdId, PendingAction action, String qty, String px) {
        return new PendingRequest(clOrdId, action, true, qty, px);
    }

    static PendingRequest inferred(String clOrdId, PendingAction action) {
        return new PendingRequest(clOrdId, action, false, null, null);
    }

    /** This entry, now seen as a request under {@code id} with these terms. */
    PendingRequest asRequested(String id, String proposedQty, String proposedPx) {
        return new PendingRequest(id, action, true, proposedQty, proposedPx);
    }
}
