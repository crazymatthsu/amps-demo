package com.demo.amps.fix42.state.spi;

import com.demo.amps.fix42.state.machine.Origin;
import com.demo.amps.fix42.state.machine.PendingRequest;
import java.util.List;

/**
 * A read-only view of one order's cache entry, for hooks (docs/07 §2).
 *
 * <p>Quantities are the venue's text, as on the record. A hook is a pure
 * function of the message and this view: replay converges only because the
 * machine is deterministic, and a hook with memory of its own breaks that.
 */
public interface OrderStateView {

    String orderKey();

    List<String> clOrdIds();

    String workingClOrdId();

    List<String> orderIds();

    String parentOrderId();

    String ackedQty();

    String ackedPx();

    /** Tag 39 as the venue last stated it; {@code A} for an unanswered D, null for a chain opened by a request. */
    String venueStatus();

    String lastExecType();

    String cumQty();

    String leavesQty();

    String avgPx();

    List<PendingRequest> pending();

    boolean terminal();

    long msgCount();

    long ignoredCount();

    long lateFills();

    Origin origin();
}
