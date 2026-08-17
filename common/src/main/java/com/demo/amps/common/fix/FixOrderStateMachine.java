package com.demo.amps.common.fix;

import com.demo.amps.market.v1.FixOrdStatus;
import com.demo.amps.market.v1.OrderEvent;
import com.demo.amps.market.v1.OrderState;
import com.demo.amps.market.v1.PendingRequest;
import com.demo.amps.market.v1.Side;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The FIX 4.2 order-state machine that has to live outside AMPS -- and a
 * demonstration of how thin it actually is.
 *
 * <p>The heavy lifting is already done before a message reaches this class: a
 * FIX 4.2 execution report (35=8) is a <b>cumulative state snapshot</b>, not an
 * increment. OrdStatus (39), CumQty (14), LeavesQty (151), AvgPx (6), OrderQty
 * (38) and Price (44) are all on every report, maintained by the venue's own
 * matching engine. This class therefore never computes a quantity; it copies
 * them from the latest authoritative report. What it adds is exactly the three
 * things neither the venue's reports nor an AMPS SOW can provide:
 *
 * <ol>
 *   <li><b>Chain identity.</b> A cancel/replace (35=G) moves the order to a new
 *       ClOrdID (11), linked by OrigClOrdID (41). A SOW keyed on /clOrdId would
 *       fragment one order across records; this class maps every ClOrdID ever
 *       seen back to the chain's first one, which becomes the stable SOW key.</li>
 *   <li><b>Pending-request bookkeeping.</b> "We asked for 800 but 500 is still
 *       working" requires holding the outstanding 35=G/F until the venue answers
 *       with an ack (39=5 / 39=4) or a reject (35=9), and reverting on reject.
 *       Requests and responses are different messages; correlating them is
 *       sequential, conditional logic.</li>
 *   <li><b>Arbitration.</b> Duplicate delivery (PossDup resends, same ExecID)
 *       and out-of-order reports (recovery, resends) must be ignored. CumQty is
 *       monotonic per chain in 4.2, which gives a cheap staleness test.</li>
 * </ol>
 *
 * <p>Deliberate boundaries, documented rather than hidden: single-threaded;
 * in-memory (a restart re-derives state by replaying {@code fix.events} through
 * a fresh instance -- which is the point of journalling that topic); trusts the
 * venue's cumulative fields including after execution corrections and busts
 * (ExecTransType 20=1/2), which is why corrections need no special handling
 * here; does not cover multi-leg orders, allocations, or GTC day-boundary
 * resets.
 */
public final class FixOrderStateMachine {

    /** What {@link #apply} did with an event. */
    public sealed interface Outcome {
        /** The event advanced a chain; {@code state} is the new record to publish. */
        record Applied(OrderState state) implements Outcome {
        }

        /** The event was deliberately dropped; nothing should be published. */
        record Ignored(String reason) implements Outcome {
        }
    }

    private final Map<String, OrderState> stateByChain = new HashMap<>();
    private final Map<String, String> chainByClOrdId = new HashMap<>();
    private final Map<String, Set<String>> seenExecIdsByChain = new HashMap<>();

    /** Applies one normalized FIX message and returns the resulting state, if any. */
    public Outcome apply(OrderEvent event) {
        return switch (event.getMsgType()) {
            case "D" -> onNewOrderRequest(event);
            case "G" -> onReplaceRequest(event);
            case "F" -> onCancelRequest(event);
            case "8" -> onExecutionReport(event);
            case "9" -> onCancelReject(event);
            default -> new Outcome.Ignored("unhandled MsgType " + event.getMsgType());
        };
    }

    /** Current state of a chain, by chain id or by any ClOrdID it has used. */
    public Optional<OrderState> state(String id) {
        String chainId = chainByClOrdId.getOrDefault(id, id);
        return Optional.ofNullable(stateByChain.get(chainId));
    }

    // ---- 35=D --------------------------------------------------------------

    private Outcome onNewOrderRequest(OrderEvent event) {
        String chainId = event.getClOrdId();
        if (stateByChain.containsKey(chainId)) {
            return new Outcome.Ignored("duplicate 35=D for ClOrdID " + chainId);
        }
        chainByClOrdId.put(chainId, chainId);

        OrderState state = OrderState.newBuilder()
                .setChainId(chainId)
                .setCurrentClOrdId(chainId)
                .setSymbol(event.getSymbol())
                .setSide(event.getSide())
                .setOrdStatus(FixOrdStatus.FIX_ORD_STATUS_PENDING_NEW)
                .setPendingRequest(PendingRequest.PENDING_NEW)
                .setPendingClOrdId(chainId)
                .setPendingOrderQty(event.getOrderQty())
                .setPendingPrice(event.getPrice())
                .setRevision(1)
                .setLastEventSeq(event.getSeq())
                .build();
        return store(state);
    }

    // ---- 35=G --------------------------------------------------------------

    private Outcome onReplaceRequest(OrderEvent event) {
        OrderState current = resolve(event);
        if (current == null) {
            return new Outcome.Ignored("35=G references unknown order (41="
                    + event.getOrigClOrdId() + ")");
        }
        if (isTerminal(current.getOrdStatus())) {
            return new Outcome.Ignored("35=G on terminal order " + current.getChainId());
        }
        // The new ClOrdID joins the chain now, so the venue's responses -- which
        // will carry it as 11 -- resolve to the same SOW record.
        chainByClOrdId.put(event.getClOrdId(), current.getChainId());

        OrderState state = current.toBuilder()
                .setPendingRequest(PendingRequest.PENDING_REPLACE)
                .setPendingClOrdId(event.getClOrdId())
                .setPendingOrderQty(event.getOrderQty())
                .setPendingPrice(event.getPrice())
                .setRevision(current.getRevision() + 1)
                .setLastEventSeq(event.getSeq())
                .build();
        return store(state);
    }

    // ---- 35=F --------------------------------------------------------------

    private Outcome onCancelRequest(OrderEvent event) {
        OrderState current = resolve(event);
        if (current == null) {
            return new Outcome.Ignored("35=F references unknown order (41="
                    + event.getOrigClOrdId() + ")");
        }
        if (isTerminal(current.getOrdStatus())) {
            return new Outcome.Ignored("35=F on terminal order " + current.getChainId());
        }
        chainByClOrdId.put(event.getClOrdId(), current.getChainId());

        OrderState state = current.toBuilder()
                .setPendingRequest(PendingRequest.PENDING_CANCEL)
                .setPendingClOrdId(event.getClOrdId())
                .setPendingOrderQty(0)
                .setPendingPrice(0)
                .setRevision(current.getRevision() + 1)
                .setLastEventSeq(event.getSeq())
                .build();
        return store(state);
    }

    // ---- 35=8 --------------------------------------------------------------

    private Outcome onExecutionReport(OrderEvent event) {
        OrderState current = resolve(event);
        if (current == null) {
            // Drop-copy style feed: the first thing we see for an order may be a
            // report. The report is a complete snapshot, so a chain can be
            // created from it alone.
            String chainId = !event.getOrigClOrdId().isEmpty()
                    ? event.getOrigClOrdId() : event.getClOrdId();
            chainByClOrdId.put(chainId, chainId);
            chainByClOrdId.put(event.getClOrdId(), chainId);
            current = OrderState.newBuilder()
                    .setChainId(chainId)
                    .setCurrentClOrdId(event.getClOrdId())
                    .setSymbol(event.getSymbol())
                    .setSide(event.getSide())
                    .build();
        }

        // Arbitration, the third job: duplicates first, then out-of-order.
        if (!event.getExecId().isEmpty()) {
            Set<String> seen = seenExecIdsByChain
                    .computeIfAbsent(current.getChainId(), key -> new HashSet<>());
            if (!seen.add(event.getExecId())) {
                return new Outcome.Ignored("duplicate ExecID " + event.getExecId()
                        + " (PossDup resend)");
            }
        }
        if (event.getCumQty() < current.getCumQty()) {
            return new Outcome.Ignored("stale report: CumQty " + event.getCumQty()
                    + " < " + current.getCumQty() + " already seen");
        }

        FixOrdStatus status = ordStatusOf(event.getOrdStatus());
        OrderState.Builder next = current.toBuilder()
                .setOrdStatus(status)
                .setCumQty(event.getCumQty())
                .setLeavesQty(event.getLeavesQty())
                .setAvgPx(event.getAvgPx())
                .setLastExecType(event.getExecType())
                .setText(event.getText())
                .setRevision(current.getRevision() + 1)
                .setLastEventSeq(event.getSeq());

        if (!event.getOrderId().isEmpty()) {
            next.setOrderId(event.getOrderId());
        }
        if (current.getSymbol().isEmpty() && !event.getSymbol().isEmpty()) {
            next.setSymbol(event.getSymbol());
        }
        if (current.getSide() == Side.SIDE_UNSPECIFIED && event.getSide() != Side.SIDE_UNSPECIFIED) {
            next.setSide(event.getSide());
        }

        // Acks are where "acknowledged terms" change and pendings resolve. Note
        // what does NOT clear a pending: a fill (39=1) arriving while a replace
        // is in flight leaves the pending request outstanding, which is exactly
        // the situation the pending fields exist to describe.
        switch (status) {
            case FIX_ORD_STATUS_NEW -> {
                next.setAckedOrderQty(event.getOrderQty())
                        .setAckedPrice(event.getPrice())
                        .setCurrentClOrdId(event.getClOrdId());
                clearPending(next);
            }
            case FIX_ORD_STATUS_REPLACED -> {
                // On a 4.2 replace ack, 11 is the NEW ClOrdID and 41 the old.
                next.setAckedOrderQty(event.getOrderQty())
                        .setAckedPrice(event.getPrice())
                        .setCurrentClOrdId(event.getClOrdId());
                clearPending(next);
            }
            case FIX_ORD_STATUS_CANCELED, FIX_ORD_STATUS_REJECTED,
                 FIX_ORD_STATUS_EXPIRED, FIX_ORD_STATUS_DONE_FOR_DAY -> clearPending(next);
            default -> {
                // Fills, pending-new, pending-cancel, pending-replace: cumulative
                // fields were already copied above; pendings stay as they are.
            }
        }

        return store(next.build());
    }

    // ---- 35=9 --------------------------------------------------------------

    private Outcome onCancelReject(OrderEvent event) {
        OrderState current = resolve(event);
        if (current == null) {
            return new Outcome.Ignored("35=9 references unknown order (41="
                    + event.getOrigClOrdId() + ")");
        }

        // OrderCancelReject carries OrdStatus (39) as a required field: the venue
        // states what the order still is, so there is nothing to remember and
        // revert -- one more piece of state machinery 4.2 hands you for free.
        OrderState.Builder next = current.toBuilder()
                .setOrdStatus(ordStatusOf(event.getOrdStatus()))
                .setText(event.getText())
                .setRevision(current.getRevision() + 1)
                .setLastEventSeq(event.getSeq());
        clearPending(next);
        return store(next.build());
    }

    // ---- plumbing ----------------------------------------------------------

    private OrderState resolve(OrderEvent event) {
        String chainId = chainByClOrdId.get(event.getClOrdId());
        if (chainId == null && !event.getOrigClOrdId().isEmpty()) {
            chainId = chainByClOrdId.get(event.getOrigClOrdId());
        }
        return chainId == null ? null : stateByChain.get(chainId);
    }

    private Outcome store(OrderState state) {
        stateByChain.put(state.getChainId(), state);
        return new Outcome.Applied(state);
    }

    private static void clearPending(OrderState.Builder builder) {
        builder.setPendingRequest(PendingRequest.PENDING_NONE)
                .setPendingClOrdId("")
                .setPendingOrderQty(0)
                .setPendingPrice(0);
    }

    private static boolean isTerminal(FixOrdStatus status) {
        return switch (status) {
            case FIX_ORD_STATUS_FILLED, FIX_ORD_STATUS_CANCELED,
                 FIX_ORD_STATUS_REJECTED, FIX_ORD_STATUS_EXPIRED -> true;
            default -> false;
        };
    }

    /** OrdStatus (39) character to the readable enum consumers see. */
    static FixOrdStatus ordStatusOf(String raw) {
        return switch (raw) {
            case "A" -> FixOrdStatus.FIX_ORD_STATUS_PENDING_NEW;
            case "0" -> FixOrdStatus.FIX_ORD_STATUS_NEW;
            case "1" -> FixOrdStatus.FIX_ORD_STATUS_PARTIALLY_FILLED;
            case "2" -> FixOrdStatus.FIX_ORD_STATUS_FILLED;
            case "3" -> FixOrdStatus.FIX_ORD_STATUS_DONE_FOR_DAY;
            case "4" -> FixOrdStatus.FIX_ORD_STATUS_CANCELED;
            case "5" -> FixOrdStatus.FIX_ORD_STATUS_REPLACED;
            case "6" -> FixOrdStatus.FIX_ORD_STATUS_PENDING_CANCEL;
            case "8" -> FixOrdStatus.FIX_ORD_STATUS_REJECTED;
            case "C" -> FixOrdStatus.FIX_ORD_STATUS_EXPIRED;
            case "E" -> FixOrdStatus.FIX_ORD_STATUS_PENDING_REPLACE;
            default -> FixOrdStatus.FIX_ORD_STATUS_UNSPECIFIED;
        };
    }
}
