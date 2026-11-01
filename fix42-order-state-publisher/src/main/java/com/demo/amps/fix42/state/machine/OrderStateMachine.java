package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.config.FillUnderPendingIdPolicy;
import com.demo.amps.fix42.state.config.NonFillAfterTerminalPolicy;
import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.spi.IgnoreReason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rules of docs/03, as a two-phase machine: {@link #plan} resolves a
 * message to its order, arbitrates it and stages the changes on a copy of the
 * cache entry; {@link #commit} applies them. The publisher calls
 * {@code commit} only after the AMPS client has accepted the message's
 * publishes into its store, so a publish that throws leaves the cache exactly
 * as it was and the message can be redelivered (docs/04 §6).
 *
 * <p>Pure: a {@link FixFrame} in, an {@link Outcome} out, no I/O and no AMPS
 * type anywhere in the package. Single-threaded, like the feed it serves
 * (docs/02 §7).
 *
 * <p>The rules, in the order docs/03 states them:
 * <ul>
 *   <li><b>identity</b> (§3): a typed index -- ClOrdIDs and OrderIDs in
 *       separate maps -- with a resolution order per message type, a key
 *       minted from the drop copy's own ids with collision detection, and ids
 *       never rebound;</li>
 *   <li><b>status</b> (§4): tag 39 is the venue's, written by reports, by a
 *       {@code 35=9} only under {@code cancel-reject-status}, never by a
 *       request; the after-terminal table decides what a report may still do
 *       to a closed order;</li>
 *   <li><b>arbitration</b> (§5): classify, duplicate, stale, late, then
 *       after-terminal, in that order and before any field is touched;</li>
 *   <li><b>transitions</b> (§6): the pending <i>set</i>, answered by ExecType
 *       and matched on the report's own tag 11.</li>
 * </ul>
 */
public final class OrderStateMachine {

    private static final Logger log = LoggerFactory.getLogger(OrderStateMachine.class);

    private final MachineOptions options;
    private final OrderKeyIndex index = new OrderKeyIndex();
    private final Map<String, OrderState> entries = new HashMap<>();
    private final MachineCounters counters = new MachineCounters();
    private long planned;
    private long committed;

    public OrderStateMachine(MachineOptions options) {
        this.options = options;
    }

    public MachineOptions options() {
        return options;
    }

    // ---- the two phases -------------------------------------------------------

    /**
     * Decides what {@code message} does, without changing anything. Planning
     * the same message twice without a commit in between returns the same
     * outcome.
     */
    public Outcome plan(FixFrame message) {
        Apply a = new Apply(message, ++planned);
        switch (a.msgType) {
            case FixTags.MsgType.NEW_ORDER_SINGLE -> planNewOrder(a);
            case FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST -> planRequest(a, PendingAction.REPLACE);
            case FixTags.MsgType.ORDER_CANCEL_REQUEST -> planRequest(a, PendingAction.CANCEL);
            case FixTags.MsgType.EXECUTION_REPORT -> planReport(a);
            case FixTags.MsgType.ORDER_CANCEL_REJECT -> planReject(a);
            default -> planUnrouted(a);
        }
        return a.outcome();
    }

    /**
     * Applies {@code outcome}, which must be the one the latest {@link #plan}
     * returned: the entry, the index bindings and the counters change here and
     * nowhere else.
     */
    public void commit(Outcome outcome) {
        if (outcome.sequence != planned || outcome.sequence == committed) {
            throw new IllegalStateException("commit of plan #" + outcome.sequence
                    + ", but the latest plan is #" + planned + " and #" + committed + " was committed");
        }
        committed = outcome.sequence;
        if (outcome.staged != null) {
            OrderState entry = outcome.staged;
            entry.carriedDirty = outcome.publish == Outcome.Publish.NONE
                    ? outcome.dirty & ~RecordField.KEY.bit() : 0;
            entries.put(outcome.key, entry);
            index.addKey(outcome.key);
        }
        for (Outcome.Binding binding : outcome.bindings) {
            if (binding.orderId()) {
                index.bindOrderId(binding.id(), binding.key());
            } else {
                index.bindClOrdId(binding.id(), binding.key());
            }
        }
        counters.add(outcome.counts);
        counters.observe(outcome.msgType, outcome.execType);
    }

    // ---- read access ------------------------------------------------------------

    /** The cached entry for {@code key}, or null. Read-only by convention: the machine owns it. */
    public OrderState state(String key) {
        return entries.get(key);
    }

    /** The key a ClOrdID is bound to, or null. */
    public String keyForClOrdId(String clOrdId) {
        return index.keyForClOrdId(clOrdId);
    }

    public OrderKeyIndex index() {
        return index;
    }

    public MachineCounters counters() {
        return counters;
    }

    public int size() {
        return entries.size();
    }

    public Collection<OrderState> states() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /** Orders that are terminal with a request still pending: a venue that does not reject late requests. */
    public long pendingOnTerminal() {
        return entries.values().stream().filter(s -> s.terminal() && !s.pending.isEmpty()).count();
    }

    /**
     * Loads an entry rebuilt from its SOW record (docs/04 §2): the ClOrdID map
     * from 9021, the OrderID map from 37. Used by the cache loader before the
     * first message.
     */
    public void load(OrderState state) {
        entries.put(state.orderKey, state);
        index.addKey(state.orderKey);
        if (state.keyKind == 'C') {
            index.bindClOrdId(state.orderKey, state.orderKey);
        }
        for (String clOrdId : state.clOrdIds) {
            index.bindClOrdId(clOrdId, state.orderKey);
        }
        for (String orderId : state.orderIds) {
            index.bindOrderId(orderId, state.orderKey);
        }
    }

    // ---- 35=D --------------------------------------------------------------------

    /** docs/03 §6.1. A D resolves by its own 11 only; its 41 is lineage, never followed. */
    private void planNewOrder(Apply a) {
        String id = a.v(FixTags.CL_ORD_ID);
        if (id.isEmpty()) {
            a.ignoreWithoutChain(IgnoreReason.NO_ID);
            return;
        }
        String key = index.keyForClOrdId(id);
        if (key == null) {
            if (index.isKey(id)) {
                a.count(Counter.KEY_COLLISIONS);
                log.warn("35=D {} collides with an existing key; ignored", id);
                a.ignoreWithoutChain(IgnoreReason.KEY_CONFLICT);
                return;
            }
            createFromNewOrder(a, id);
            return;
        }
        a.attach(key);
        if (a.s.origin == Origin.MIDSTREAM) {
            lateNewOrder(a, id);
        } else {
            // A resend, or a day replayed: a FULL publish here would erase
            // every delta since the original.
            a.ignore(IgnoreReason.DUPLICATE_REQUEST);
        }
        a.stage(FixTags.CL_ORD_ID);
    }

    private void createFromNewOrder(Apply a, String id) {
        OrderState s = new OrderState();
        s.orderKey = id;
        s.keyKind = 'C';
        s.origin = Origin.D;
        s.schemaVersion = options.schemaVersion();
        s.ackedQty = a.orNull(FixTags.ORDER_QTY);
        s.ackedPx = a.orNull(FixTags.PRICE);
        s.timeInForce = a.orNull(FixTags.TIME_IN_FORCE);
        s.venueStatus = FixTags.OrdStatus.PENDING_NEW;
        s.cumQty = "0";
        s.leavesQty = s.ackedQty;
        s.avgPx = "0";
        s.pending.add(PendingRequest.requested(id, PendingAction.NEW, null, null));
        s.workingClOrdId = id;
        s.parentOrderId = a.orNull(FixTags.PARENT_ORDER_ID);
        s.linkedClOrdId = a.orNull(FixTags.ORIG_CL_ORD_ID);
        a.create(id, s, Outcome.Publish.FULL, Outcome.PassThrough.DESCRIPTIVE);
        a.bookkeeping(false);
        a.stage(FixTags.CL_ORD_ID);
    }

    /** A D for a chain first seen mid-stream: its descriptive terms, never a NEW (S7). */
    private void lateNewOrder(Apply a, String id) {
        OrderState s = a.s;
        s.origin = Origin.D_LATE;
        a.mark(RecordField.ORIGIN);
        if (s.ackedQty == null && s.ackedPx == null && s.venueStatus == null) {
            s.ackedQty = a.orNull(FixTags.ORDER_QTY);
            s.ackedPx = a.orNull(FixTags.PRICE);
            a.mark(RecordField.ORDER_QTY);
            a.mark(RecordField.PRICE);
        }
        String tif = a.orNull(FixTags.TIME_IN_FORCE);
        if (tif != null) {
            s.timeInForce = tif;
        }
        String parent = a.orNull(FixTags.PARENT_ORDER_ID);
        if (parent != null && s.parentOrderId == null) {
            s.parentOrderId = parent;
            a.mark(RecordField.PARENT);
        }
        String linked = a.orNull(FixTags.ORIG_CL_ORD_ID);
        if (linked != null) {
            s.linkedClOrdId = linked;
            a.mark(RecordField.LINKED);
        }
        PendingRequest inferredNew = s.pendingRequest(id);
        if (inferredNew != null && inferredNew.action() == PendingAction.NEW && !inferredNew.requested()) {
            replacePending(a, inferredNew, inferredNew.asRequested(id, null, null));
        }
        a.passThrough = Outcome.PassThrough.DESCRIPTIVE;
        a.countStripped();
        a.count(Counter.LATE_DS);
        a.applied(false);
    }

    // ---- 35=G, 35=F -------------------------------------------------------------

    /** docs/03 §6.2-6.3. Resolution 41 → 37 → 11; a request never moves 38/44 or 39. */
    private void planRequest(Apply a, PendingAction action) {
        String id = a.v(FixTags.CL_ORD_ID);
        String key = resolve(a, FixTags.ORIG_CL_ORD_ID, -FixTags.ORDER_ID, FixTags.CL_ORD_ID);
        if (id.isEmpty()) {
            if (key == null) {
                a.ignoreWithoutChain(IgnoreReason.NO_ID);
            } else {
                a.attach(key);
                a.ignore(IgnoreReason.NO_ID);
            }
            return;
        }
        boolean replace = action == PendingAction.REPLACE;
        String qty = replace ? a.orNull(FixTags.ORDER_QTY) : null;
        String px = replace ? a.orNull(FixTags.PRICE) : null;
        if (key == null) {
            createFromRequest(a, action, id, qty, px);
            return;
        }
        a.attach(key);
        OrderState s = a.s;
        PendingRequest existing = s.pendingRequest(id);
        if (existing != null) {
            if (existing.requested()) {
                a.ignore(IgnoreReason.DUPLICATE_REQUEST);
            } else {
                // The venue's acknowledgement overtook this request (R9).
                replacePending(a, existing, existing.asRequested(id, qty, px));
                a.applied(true);
            }
        } else {
            String orig = a.v(FixTags.ORIG_CL_ORD_ID);
            PendingRequest underOrig = orig.isEmpty() ? null : s.pendingRequest(orig);
            if (underOrig != null && !underOrig.requested() && underOrig.action() == action) {
                // The acknowledgement named the working id, not the request's (S7).
                replacePending(a, underOrig, underOrig.asRequested(id, qty, px));
                a.applied(true);
            } else if (key.equals(index.keyForClOrdId(id))) {
                // An answered id: its answer overtook it. Adding it would leave
                // pending a request the venue has already dealt with.
                a.ignore(IgnoreReason.LATE_REQUEST);
            } else {
                s.pending.add(PendingRequest.requested(id, action, qty, px));
                a.mark(RecordField.PENDING);
                a.applied(true);
            }
        }
        a.stage(FixTags.ORIG_CL_ORD_ID, FixTags.CL_ORD_ID, FixTags.ORDER_ID);
    }

    /** A chain first seen through a request: the pending entry and nothing else (R7). */
    private void createFromRequest(Apply a, PendingAction action, String id, String qty, String px) {
        Minted minted = mint(a);
        if (minted == null) {
            return;
        }
        OrderState s = new OrderState();
        s.orderKey = minted.key;
        s.keyKind = minted.kind;
        s.origin = Origin.MIDSTREAM;
        s.schemaVersion = options.schemaVersion();
        s.timeInForce = a.orNull(FixTags.TIME_IN_FORCE);
        s.parentOrderId = a.orNull(FixTags.PARENT_ORDER_ID);
        s.pending.add(PendingRequest.requested(id, action, qty, px));
        a.create(minted.key, s, Outcome.Publish.DELTA, Outcome.PassThrough.DESCRIPTIVE_NO_TERMS);
        a.countMidstream();
        a.bookkeeping(true);
        a.stage(FixTags.ORIG_CL_ORD_ID, FixTags.CL_ORD_ID, FixTags.ORDER_ID);
    }

    // ---- 35=8 --------------------------------------------------------------------

    /** What a report is, for arbitration (docs/03 §5 step 1). */
    private enum ReportKind {
        /** A bust (20=1), a correct (20=2) or a restatement (150=D). */
        REOPENING,
        /** 150 ∈ {A, 6, E}. */
        PENDING_ACK,
        /** 20=3 or 150=I. */
        STATUS,
        /** 150 ∈ {1, 2}, or an unnamed 150 that carries 32. */
        FILL,
        /** Everything else: 0, 3, 4, 5, 8, C, or an unnamed 150 without 32. */
        OTHER
    }

    private static ReportKind classify(Apply a) {
        String transType = a.v(FixTags.EXEC_TRANS_TYPE);
        String execType = a.v(FixTags.EXEC_TYPE);
        if (FixTags.ExecTransType.CANCEL.equals(transType) || FixTags.ExecTransType.CORRECT.equals(transType)
                || FixTags.ExecType.RESTATED.equals(execType)) {
            return ReportKind.REOPENING;
        }
        // 20=3 is never a fill, whatever its 150 (R18).
        if (FixTags.ExecTransType.STATUS.equals(transType) || "I".equals(execType)) {
            return ReportKind.STATUS;
        }
        return switch (execType) {
            case FixTags.ExecType.PENDING_NEW, FixTags.ExecType.PENDING_CANCEL,
                 FixTags.ExecType.PENDING_REPLACE -> ReportKind.PENDING_ACK;
            case FixTags.ExecType.PARTIAL_FILL, FixTags.ExecType.FILL -> ReportKind.FILL;
            case FixTags.ExecType.NEW, FixTags.ExecType.DONE_FOR_DAY, FixTags.ExecType.CANCELED,
                 FixTags.ExecType.REPLACED, FixTags.ExecType.REJECTED, FixTags.ExecType.EXPIRED -> ReportKind.OTHER;
            // A 150 no rule names -- F, G, H from a FIX 4.4 venue -- is applied,
            // never unrouted (S11).
            default -> a.frame.has(FixTags.LAST_SHARES) ? ReportKind.FILL : ReportKind.OTHER;
        };
    }

    /** docs/03 §5 then §4's after-terminal table then §6.4. Resolution 37 → 11 → 41. */
    private void planReport(Apply a) {
        ReportKind kind = classify(a);
        String key = resolve(a, -FixTags.ORDER_ID, FixTags.CL_ORD_ID, FixTags.ORIG_CL_ORD_ID);
        if (key == null) {
            createFromReport(a, kind);
            return;
        }
        a.attach(key);
        arbitrateAndApply(a, kind);
        a.stage(FixTags.ORIG_CL_ORD_ID, FixTags.CL_ORD_ID, FixTags.ORDER_ID);
    }

    private void arbitrateAndApply(Apply a, ReportKind kind) {
        OrderState s = a.s;
        boolean reopening = kind == ReportKind.REOPENING;
        String execId = a.v(FixTags.EXEC_ID);
        String cumQty = a.v(FixTags.CUM_QTY);

        // 2. duplicate
        if (usableExecId(execId) && s.execIdSeen(execId)) {
            a.ignore(IgnoreReason.DUPLICATE);
            return;
        }
        String transType = a.v(FixTags.EXEC_TRANS_TYPE);
        boolean bustOrCorrect = FixTags.ExecTransType.CANCEL.equals(transType)
                || FixTags.ExecTransType.CORRECT.equals(transType);
        String execRef = a.v(FixTags.EXEC_REF_ID);
        if (bustOrCorrect && !execRef.isEmpty() && s.bustedExecIds.contains(execRef)) {
            a.ignore(IgnoreReason.DUPLICATE);
            return;
        }
        // 3. stale
        if (options.staleGuard() && !reopening && Quantities.lower(cumQty, s.cumQty)) {
            a.ignore(IgnoreReason.STALE);
            return;
        }
        // 4. late
        if (options.transactTimeGuard() && s.lastReportTime != null) {
            String time = a.time();
            if (time != null && Timestamps.earlier(time, s.lastReportTime)
                    && (reopening || !Quantities.present(cumQty) || Quantities.equal(cumQty, s.cumQty))) {
                a.ignore(IgnoreReason.LATE);
                return;
            }
        }
        // 5. after a terminal status
        if (s.terminal() && !reopening) {
            afterTerminal(a, kind);
            return;
        }
        applyReport(a, kind, false);
    }

    /** docs/03 §4: what a non-reopening report may still do to a terminal order. */
    private void afterTerminal(Apply a, ReportKind kind) {
        OrderState s = a.s;
        if (OrderState.isTerminal(a.v(FixTags.ORD_STATUS))) {
            applyReport(a, kind, false);
            return;
        }
        if (kind == ReportKind.PENDING_ACK) {
            a.ignore(IgnoreReason.ABSORBED);
            return;
        }
        String cumQty = a.v(FixTags.CUM_QTY);
        if (kind == ReportKind.FILL) {
            if (Quantities.higher(cumQty, s.cumQty)) {
                // A late execution: a trade is money and is never dropped by default.
                switch (options.lateFillAfterTerminal()) {
                    case APPLY_ECONOMICS -> applyLateExecution(a);
                    case ADOPT_VENUE -> applyReport(a, kind, false);
                    case IGNORE -> a.ignore(IgnoreReason.LATE_FILL);
                }
            } else if (!Quantities.present(cumQty) || Quantities.equal(cumQty, s.cumQty)) {
                applyFillDetail(a);
            } else {
                a.ignore(IgnoreReason.STALE);
            }
            return;
        }
        NonFillAfterTerminalPolicy policy = options.nonFillAfterTerminal();
        if (policy == NonFillAfterTerminalPolicy.IGNORE) {
            a.ignore(IgnoreReason.LATE);
            return;
        }
        boolean answers = answersPendingRequest(s, a);
        boolean immediateOrCancel = "3".equals(s.timeInForce) || "4".equals(s.timeInForce);
        if (immediateOrCancel && !answers) {
            a.count(Counter.LATE_IOC_ACK);
            a.ignore(IgnoreReason.LATE);
            return;
        }
        if (!answers && policy == NonFillAfterTerminalPolicy.ADOPT_VENUE_IF_LATER) {
            String time = a.time();
            if (time == null || s.lastReportTime == null) {
                // Indistinguishable from an overtaken ack: a wrong reopen is live
                // exposure the views never stop counting.
                a.count(Counter.REOPEN_UNDECIDABLE);
                a.ignore(IgnoreReason.LATE);
                return;
            }
            if (!Timestamps.later(time, s.lastReportTime)) {
                a.ignore(IgnoreReason.LATE);
                return;
            }
        }
        a.count(Counter.REOPENED_BY_VENUE);
        applyReport(a, kind, false);
    }

    /** Whether the report's own 11 names a pending request its ExecType answers. */
    private static boolean answersPendingRequest(OrderState s, Apply a) {
        PendingRequest named = s.pendingRequest(a.v(FixTags.CL_ORD_ID));
        if (named == null) {
            return false;
        }
        return switch (a.v(FixTags.EXEC_TYPE)) {
            case FixTags.ExecType.NEW -> named.action() != PendingAction.CANCEL;
            case FixTags.ExecType.REPLACED -> named.action() == PendingAction.REPLACE;
            case FixTags.ExecType.CANCELED -> named.action() == PendingAction.CANCEL;
            case FixTags.ExecType.REJECTED -> true;
            default -> false;
        };
    }

    private void createFromReport(Apply a, ReportKind kind) {
        Minted minted = mint(a);
        if (minted == null) {
            return;
        }
        OrderState s = new OrderState();
        s.orderKey = minted.key;
        s.keyKind = minted.kind;
        s.origin = Origin.MIDSTREAM;
        s.schemaVersion = options.schemaVersion();
        a.create(minted.key, s, Outcome.Publish.DELTA, Outcome.PassThrough.DESCRIPTIVE);
        a.countMidstream();
        applyReport(a, kind, true);
        a.passThrough = Outcome.PassThrough.DESCRIPTIVE;
        a.stage(FixTags.ORIG_CL_ORD_ID, FixTags.CL_ORD_ID, FixTags.ORDER_ID);
    }

    /** docs/03 §6.4: a report applied in full. */
    private void applyReport(Apply a, ReportKind kind, boolean creating) {
        OrderState s = a.s;
        String execType = a.v(FixTags.EXEC_TYPE);
        String ordStatus = a.v(FixTags.ORD_STATUS);
        String clOrdId = a.v(FixTags.CL_ORD_ID);
        String execId = a.v(FixTags.EXEC_ID);
        boolean pendingAck = kind == ReportKind.PENDING_ACK;
        boolean freshId = !clOrdId.isEmpty() && index.keyForClOrdId(clOrdId) == null;
        boolean forceLeavesZero = false;

        // A 150=8 rejects the ORDER only when it names a pending NEW or the
        // chain was never acked; otherwise it is a request reject in report
        // form, whatever it names (R3, S6).
        boolean rejected = FixTags.ExecType.REJECTED.equals(execType) && kind == ReportKind.OTHER;
        boolean requestReject = false;
        if (rejected) {
            PendingRequest named = s.pendingRequest(clOrdId);
            boolean neverAcked = s.venueStatus == null || FixTags.OrdStatus.PENDING_NEW.equals(s.venueStatus);
            requestReject = !(neverAcked || (named != null && named.action() == PendingAction.NEW));
            if (requestReject) {
                a.count(Counter.REQUEST_REJECTS_AS_REPORTS);
                if (named != null) {
                    removePending(a, named);
                } else {
                    a.count(Counter.UNMATCHED_REJECTS);
                }
                forceLeavesZero = adoptRejectStatus(a, ordStatus);
            }
        }

        if (!requestReject && !ordStatus.isEmpty()) {
            s.venueStatus = ordStatus;
            a.mark(RecordField.ORD_STATUS);
            forceLeavesZero = OrderState.isTerminal(ordStatus);
        }
        if (!execType.isEmpty()) {
            s.lastExecType = execType;
            a.mark(RecordField.EXEC_TYPE);
        }
        if (usableExecId(execId)) {
            s.rememberExecId(execId, options.execIdHistory());
            a.mark(RecordField.EXEC_ID);
        }
        adoptCumulative(a);
        if (forceLeavesZero) {
            s.leavesQty = "0";
            a.mark(RecordField.LEAVES_QTY);
        }
        // 38/44: never from a pending acknowledgement (it may echo proposed
        // terms) nor a 150=8 (it echoes the REJECTED request's terms).
        if (!pendingAck && !rejected) {
            adoptTerms(a);
        }
        if (!pendingAck && (FixTags.ExecType.NEW.equals(execType) || FixTags.ExecType.REPLACED.equals(execType)
                || FixTags.ExecType.RESTATED.equals(execType))) {
            a.passThrough = Outcome.PassThrough.ORDER_TERMS;
            String tif = a.orNull(FixTags.TIME_IN_FORCE);
            if (tif != null) {
                s.timeInForce = tif;
            }
            // A market order has no price, and a merge cannot remove the
            // limit it replaced (R14).
            if ("1".equals(a.v(FixTags.ORD_TYPE)) && !a.frame.has(FixTags.PRICE)) {
                s.ackedPx = "0";
                a.mark(RecordField.PRICE);
            }
        }

        String transType = a.v(FixTags.EXEC_TRANS_TYPE);
        boolean bust = FixTags.ExecTransType.CANCEL.equals(transType);
        boolean correct = FixTags.ExecTransType.CORRECT.equals(transType);
        String execRef = a.v(FixTags.EXEC_REF_ID);
        if (kind == ReportKind.FILL || correct) {
            a.copyFillDetail = true;
            if (correct) {
                s.lastFillExecId = execRef.isEmpty() ? s.lastFillExecId : execRef;
                s.lastFillCorrectionExecId = usableExecId(execId) ? execId : null;
            } else {
                s.lastFillExecId = usableExecId(execId) ? execId : s.lastFillExecId;
                s.lastFillCorrectionExecId = null;
            }
            a.mark(RecordField.LAST_FILL_EXEC_ID);
        }
        if (bust && !execRef.isEmpty()
                && (execRef.equals(s.lastFillExecId) || execRef.equals(s.lastFillCorrectionExecId))) {
            // Decision 6: 32/31 describe a trade that never happened. 9028
            // keeps the busted ExecID so a reader can see why they are zero.
            a.zeroFillDetail = true;
        }
        if (bust || correct) {
            boolean added = addBusted(s, execRef);
            if (correct && usableExecId(execId)) {
                added |= addBusted(s, execId);
            }
            if (added) {
                a.mark(RecordField.BUSTED);
            }
        }
        adoptOrderId(a);
        adoptReportTime(a);
        a.mark(RecordField.TERMINAL);
        if (!rejected && !s.terminal() && a.inconsistent()) {
            a.count(Counter.INCONSISTENT_REPORTS);
        }

        if (!requestReject) {
            updatePending(a, kind, execType, clOrdId, freshId, creating);
        }
        if (creating && (FixTags.ExecType.NEW.equals(execType) || FixTags.ExecType.REPLACED.equals(execType))
                && !clOrdId.isEmpty()) {
            s.workingClOrdId = clOrdId;
            a.mark(RecordField.WORKING);
        }
        if (creating) {
            a.bookkeeping(true);
        } else {
            a.applied(true);
        }
    }

    /** docs/03 §6.4, the pending-set rows: by the report's ExecType, matched on its own tag 11. */
    private void updatePending(Apply a, ReportKind kind, String execType, String clOrdId, boolean freshId,
                               boolean creating) {
        OrderState s = a.s;
        if (kind == ReportKind.PENDING_ACK) {
            // Never removes. An acknowledgement of a request this side has not
            // seen adds an inferred entry, so it is not lost (R9).
            if (!clOrdId.isEmpty() && s.pendingRequest(clOrdId) == null && !answeredId(a.key, s, clOrdId)) {
                PendingAction action = switch (execType) {
                    case FixTags.ExecType.PENDING_NEW -> PendingAction.NEW;
                    case FixTags.ExecType.PENDING_CANCEL -> PendingAction.CANCEL;
                    default -> PendingAction.REPLACE;
                };
                s.pending.add(PendingRequest.inferred(clOrdId, action));
                a.mark(RecordField.PENDING);
                a.count(Counter.INFERRED_REQUESTS);
            }
            return;
        }
        // Any report that is not a pending acknowledgement answers a pending
        // NEW, whatever its 11: the venue reporting on the order has accepted
        // or rejected it (N14, S12).
        for (Iterator<PendingRequest> it = s.pending.iterator(); it.hasNext(); ) {
            PendingRequest request = it.next();
            if (request.action() == PendingAction.NEW) {
                it.remove();
                s.workingClOrdId = request.clOrdId();
                a.mark(RecordField.PENDING);
                a.mark(RecordField.WORKING);
            }
        }
        if (kind == ReportKind.OTHER) {
            PendingRequest named = s.pendingRequest(clOrdId);
            switch (execType) {
                case FixTags.ExecType.REPLACED -> {
                    if (named != null && named.action() == PendingAction.REPLACE) {
                        removePending(a, named);
                        setWorking(a, clOrdId);
                    } else {
                        List<PendingRequest> replaces = pendingOf(s, PendingAction.REPLACE);
                        if (replaces.size() == 1) {
                            // The venue echoed the original id (R8).
                            removePending(a, replaces.getFirst());
                            setWorking(a, replaces.getFirst().clOrdId());
                            a.count(Counter.INFERRED_REPLACE_ANSWERS);
                        } else {
                            if (!creating) {
                                a.count(Counter.UNMATCHED_CONFIRMS);
                            }
                            if (freshId) {
                                setWorking(a, clOrdId);
                            }
                        }
                    }
                }
                case FixTags.ExecType.CANCELED -> {
                    if (named != null && named.action() == PendingAction.CANCEL) {
                        removePending(a, named);
                    } else {
                        // The order is canceled: a venue need not answer each
                        // cancel separately, and some echo the original id.
                        for (PendingRequest cancel : pendingOf(s, PendingAction.CANCEL)) {
                            removePending(a, cancel);
                        }
                    }
                }
                case FixTags.ExecType.NEW -> {
                    if (named != null && named.action() == PendingAction.REPLACE) {
                        // The cancel-then-new dialect's new leg (docs/03 §7.16).
                        removePending(a, named);
                        setWorking(a, clOrdId);
                    }
                    // No match: 9014 is untouched -- a venue that re-acks
                    // after a replace must not drag it back (S15).
                }
                default -> {
                }
            }
        } else if (kind == ReportKind.FILL
                && options.fillUnderPendingId() == FillUnderPendingIdPolicy.ANSWERS_REPLACE_IF_TERMS_MATCH) {
            PendingRequest named = s.pendingRequest(clOrdId);
            if (named != null && named.action() == PendingAction.REPLACE && named.requested()
                    && Quantities.equal(a.v(FixTags.ORDER_QTY), named.qty())
                    && (named.px() == null || Quantities.equal(a.v(FixTags.PRICE), named.px()))) {
                removePending(a, named);
                setWorking(a, clOrdId);
            }
        }
    }

    /** {@code late-fill-after-terminal: APPLY_ECONOMICS}: the trade lands, the status stays. */
    private void applyLateExecution(Apply a) {
        OrderState s = a.s;
        String execId = a.v(FixTags.EXEC_ID);
        String execType = a.v(FixTags.EXEC_TYPE);
        String cumQty = a.v(FixTags.CUM_QTY);
        String avgPx = a.v(FixTags.AVG_PX);
        String orderQty = a.v(FixTags.ORDER_QTY);
        if (!cumQty.isEmpty()) {
            s.cumQty = cumQty;
            a.mark(RecordField.CUM_QTY);
        }
        if (!avgPx.isEmpty()) {
            s.avgPx = avgPx;
            a.mark(RecordField.AVG_PX);
        }
        if (!orderQty.isEmpty()) {
            s.ackedQty = orderQty;
            a.mark(RecordField.ORDER_QTY);
        }
        if (!execType.isEmpty()) {
            s.lastExecType = execType;
            a.mark(RecordField.EXEC_TYPE);
        }
        if (usableExecId(execId)) {
            s.rememberExecId(execId, options.execIdHistory());
            s.lastFillExecId = execId;
            s.lastFillCorrectionExecId = null;
            a.mark(RecordField.EXEC_ID);
            a.mark(RecordField.LAST_FILL_EXEC_ID);
        }
        a.copyFillDetail = true;
        s.lateFills++;
        a.mark(RecordField.LATE_FILLS);
        a.count(Counter.LATE_FILLS);
        adoptOrderId(a);
        adoptReportTime(a);
        a.applied(true);
    }

    /** A fill the terminal report already counted, arriving late: its detail only (fills only, R2). */
    private void applyFillDetail(Apply a) {
        OrderState s = a.s;
        String execId = a.v(FixTags.EXEC_ID);
        String execType = a.v(FixTags.EXEC_TYPE);
        if (!execType.isEmpty()) {
            s.lastExecType = execType;
            a.mark(RecordField.EXEC_TYPE);
        }
        if (usableExecId(execId)) {
            s.rememberExecId(execId, options.execIdHistory());
            s.lastFillExecId = execId;
            s.lastFillCorrectionExecId = null;
            a.mark(RecordField.EXEC_ID);
            a.mark(RecordField.LAST_FILL_EXEC_ID);
        }
        a.copyFillDetail = true;
        a.applied(true);
    }

    // ---- 35=9 --------------------------------------------------------------------

    /** docs/03 §6.5. Resolution 11 → 41 → 37; a reject never creates a chain. */
    private void planReject(Apply a) {
        String key = resolve(a, FixTags.CL_ORD_ID, FixTags.ORIG_CL_ORD_ID, -FixTags.ORDER_ID);
        if (key == null) {
            a.count(Counter.UNMATCHED_REJECTS);
            a.ignoreWithoutChain(IgnoreReason.UNMATCHED);
            return;
        }
        a.attach(key);
        OrderState s = a.s;
        String clOrdId = a.v(FixTags.CL_ORD_ID);
        PendingRequest named = s.pendingRequest(clOrdId);
        if (named != null) {
            removePending(a, named);
            String responseTo = a.v(FixTags.CXL_REJ_RESPONSE_TO);
            PendingAction expected = "1".equals(responseTo) ? PendingAction.CANCEL
                    : "2".equals(responseTo) ? PendingAction.REPLACE : null;
            if (expected != null && expected != named.action()) {
                a.count(Counter.REJECT_TYPE_MISMATCH);
                log.warn("35=9 434={} rejects {}, a pending {}", responseTo, clOrdId, named.action());
            }
        } else {
            a.count(Counter.UNMATCHED_REJECTS);
        }
        if (adoptRejectStatus(a, a.v(FixTags.ORD_STATUS))) {
            s.leavesQty = "0";
            a.mark(RecordField.LEAVES_QTY);
        }
        // The reject is the venue's clock too: a re-ack older than it must not
        // reopen an order the venue said was canceled (S19).
        adoptReportTime(a);
        a.applied(true);
        a.stage(FixTags.ORIG_CL_ORD_ID, FixTags.CL_ORD_ID, FixTags.ORDER_ID);
    }

    /**
     * {@code cancel-reject-status}: what a reject's tag 39 may do, after the
     * rejected request has left the set.
     *
     * @return whether a terminal status was adopted, so 151 must be zeroed
     */
    private boolean adoptRejectStatus(Apply a, String ordStatus) {
        OrderState s = a.s;
        if (ordStatus.isEmpty()) {
            return false;
        }
        boolean adopt = switch (options.cancelRejectStatus()) {
            case NEVER -> false;
            case ALWAYS -> true;
            case WHEN_NOT_TERMINAL -> !s.terminal()
                    && !FixTags.OrdStatus.REJECTED.equals(ordStatus)
                    && !stalePendingStatus(s, ordStatus);
        };
        if (!adopt) {
            return false;
        }
        s.venueStatus = ordStatus;
        a.mark(RecordField.ORD_STATUS);
        a.mark(RecordField.TERMINAL);
        if (OrderState.isTerminal(ordStatus)) {
            // A record that reads canceled with live LeavesQty would be counted
            // by the exposure views for ever (R6).
            a.count(Counter.TERMINAL_FROM_REJECT);
            return true;
        }
        return false;
    }

    /** A pending status with no request of that kind left: the status when the request arrived (R6). */
    private static boolean stalePendingStatus(OrderState s, String ordStatus) {
        return switch (ordStatus) {
            case FixTags.OrdStatus.PENDING_NEW -> !s.hasPending(PendingAction.NEW);
            case FixTags.OrdStatus.PENDING_CANCEL -> !s.hasPending(PendingAction.CANCEL);
            case FixTags.OrdStatus.PENDING_REPLACE -> !s.hasPending(PendingAction.REPLACE);
            default -> false;
        };
    }

    // ---- everything else ----------------------------------------------------------

    /** A type no rule handles: resolved for the audit trailer, applied to nothing (docs/03 §9). */
    private void planUnrouted(Apply a) {
        a.key = resolve(a, FixTags.CL_ORD_ID, FixTags.ORIG_CL_ORD_ID, -FixTags.ORDER_ID);
        a.ignoreWithoutChain(IgnoreReason.UNROUTED);
    }

    // ---- identity --------------------------------------------------------------

    /**
     * The first candidate the index knows. A positive tag is looked up in the
     * ClOrdID map, a negated one ({@code -37}) in the OrderID map: never the
     * other way round (S1). {@code 37=NONE} is skipped.
     */
    private String resolve(Apply a, int... tags) {
        for (int tag : tags) {
            String id = a.v(Math.abs(tag));
            if (id.isEmpty()) {
                continue;
            }
            String key;
            if (tag < 0) {
                key = "NONE".equals(id) ? null : index.keyForOrderId(id);
            } else {
                key = index.keyForClOrdId(id);
            }
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private record Minted(String key, char kind) {
    }

    /**
     * A key for a chain first seen mid-stream: the ClOrdID closest to the
     * root the message carries (41, else 11), and only when it carries
     * neither, the OrderID. A candidate that is already another chain's key is
     * skipped and counted; when every one collides the message is ignored
     * (decision 2, R12).
     */
    private Minted mint(Apply a) {
        String[] candidates = {a.v(FixTags.ORIG_CL_ORD_ID), a.v(FixTags.CL_ORD_ID), a.v(FixTags.ORDER_ID)};
        boolean any = false;
        for (int i = 0; i < candidates.length; i++) {
            String candidate = candidates[i];
            if (candidate.isEmpty() || (i == 2 && "NONE".equals(candidate))) {
                continue;
            }
            any = true;
            if (index.isKey(candidate)) {
                a.count(Counter.KEY_COLLISIONS);
                log.warn("key candidate {} of a {} is already a key; skipped", candidate, a.msgType);
                continue;
            }
            return new Minted(candidate, i == 2 ? 'O' : 'C');
        }
        a.ignoreWithoutChain(any ? IgnoreReason.KEY_CONFLICT : IgnoreReason.NO_ID);
        return null;
    }

    /** A request id bound to this chain and no longer pending: dealt with already. */
    private boolean answeredId(String key, OrderState s, String clOrdId) {
        return key.equals(index.keyForClOrdId(clOrdId)) && s.pendingRequest(clOrdId) == null;
    }

    // ---- small field rules ---------------------------------------------------------

    private static boolean usableExecId(String execId) {
        return !execId.isEmpty() && !"0".equals(execId) && !"NONE".equals(execId);
    }

    private static void adoptCumulative(Apply a) {
        OrderState s = a.s;
        String cumQty = a.v(FixTags.CUM_QTY);
        String leavesQty = a.v(FixTags.LEAVES_QTY);
        String avgPx = a.v(FixTags.AVG_PX);
        if (!cumQty.isEmpty()) {
            s.cumQty = cumQty;
            a.mark(RecordField.CUM_QTY);
        }
        if (!leavesQty.isEmpty()) {
            s.leavesQty = leavesQty;
            a.mark(RecordField.LEAVES_QTY);
        }
        if (!avgPx.isEmpty()) {
            s.avgPx = avgPx;
            a.mark(RecordField.AVG_PX);
        }
    }

    private static void adoptTerms(Apply a) {
        OrderState s = a.s;
        String orderQty = a.v(FixTags.ORDER_QTY);
        String price = a.v(FixTags.PRICE);
        if (!orderQty.isEmpty()) {
            s.ackedQty = orderQty;
            a.mark(RecordField.ORDER_QTY);
        }
        if (!price.isEmpty()) {
            s.ackedPx = price;
            a.mark(RecordField.PRICE);
        }
    }

    /** The latest real OrderID goes to the front; the record shows it. */
    private static void adoptOrderId(Apply a) {
        String orderId = a.v(FixTags.ORDER_ID);
        if (orderId.isEmpty() || "NONE".equals(orderId)) {
            return;
        }
        a.s.orderIds.remove(orderId);
        a.s.orderIds.addFirst(orderId);
        a.mark(RecordField.ORDER_ID);
    }

    private static void adoptReportTime(Apply a) {
        String time = a.v(FixTags.TRANSACT_TIME);
        if (!time.isEmpty()) {
            a.s.lastReportTime = Timestamps.max(a.s.lastReportTime, time);
            a.mark(RecordField.LAST_REPORT_TIME);
        }
    }

    private static boolean addBusted(OrderState s, String execId) {
        if (execId.isEmpty() || s.bustedExecIds.contains(execId)) {
            return false;
        }
        s.bustedExecIds.add(execId);
        return true;
    }

    private static List<PendingRequest> pendingOf(OrderState s, PendingAction action) {
        List<PendingRequest> matching = new ArrayList<>(2);
        for (PendingRequest request : s.pending) {
            if (request.action() == action) {
                matching.add(request);
            }
        }
        return matching;
    }

    private static void removePending(Apply a, PendingRequest request) {
        a.s.pending.remove(request);
        a.mark(RecordField.PENDING);
    }

    private static void replacePending(Apply a, PendingRequest old, PendingRequest replacement) {
        a.s.pending.set(a.s.pending.indexOf(old), replacement);
        a.mark(RecordField.PENDING);
    }

    private static void setWorking(Apply a, String clOrdId) {
        if (!clOrdId.isEmpty()) {
            a.s.workingClOrdId = clOrdId;
            a.mark(RecordField.WORKING);
        }
    }

    // ---- per-message staging ----------------------------------------------------------

    /** Everything one plan stages; becomes the {@link Outcome}. */
    private final class Apply {

        final FixFrame frame;
        final long sequence;
        final String msgType;
        String key;
        OrderState s;
        long dirty;
        Outcome.Kind kind = Outcome.Kind.CHANGED;
        IgnoreReason reason;
        Outcome.Publish creationPublish;
        Outcome.PassThrough passThrough = Outcome.PassThrough.NONE;
        boolean copyFillDetail;
        boolean zeroFillDetail;
        final List<Outcome.Binding> bindings = new ArrayList<>(3);
        final int[] counts = new int[Counter.values().length];

        Apply(FixFrame frame, long sequence) {
            this.frame = frame;
            this.sequence = sequence;
            this.msgType = frame.value(FixTags.MSG_TYPE);
        }

        String v(int tag) {
            return frame.value(tag);
        }

        String orNull(int tag) {
            String value = frame.value(tag);
            return value.isEmpty() ? null : value;
        }

        /** 60, else 52, else null: what the late guard and the after-terminal tiebreak judge by. */
        String time() {
            String time = orNull(FixTags.TRANSACT_TIME);
            if (time == null) {
                time = orNull(FixTags.SENDING_TIME);
            }
            return time != null && Timestamps.present(time) ? time : null;
        }

        boolean inconsistent() {
            double orderQty = Quantities.parse(v(FixTags.ORDER_QTY));
            double cumQty = Quantities.parse(v(FixTags.CUM_QTY));
            double leavesQty = Quantities.parse(v(FixTags.LEAVES_QTY));
            return !Double.isNaN(orderQty) && !Double.isNaN(cumQty) && !Double.isNaN(leavesQty)
                    && orderQty != cumQty + leavesQty;
        }

        void mark(RecordField field) {
            dirty |= field.bit();
        }

        void count(Counter counter) {
            counts[counter.ordinal()]++;
        }

        void attach(String key) {
            this.key = key;
            OrderState entry = entries.get(key);
            if (entry == null) {
                // A key reached through an OrderID whose entry is gone: an
                // evicted order's tombstone. The MissResolver of phase 4
                // recreates the entry from its record; until then there is
                // nothing honest to apply it to.
                throw new IllegalStateException("key " + key + " has no cache entry");
            }
            s = entry.copy();
        }

        void create(String key, OrderState state, Outcome.Publish publish, Outcome.PassThrough passThrough) {
            this.key = key;
            this.s = state;
            this.kind = Outcome.Kind.CREATED;
            this.creationPublish = publish;
            this.passThrough = passThrough;
            countStripped();
            count(Counter.APPLIED);
            count(Counter.CREATED);
            state.msgCount = 1;
        }

        void countMidstream() {
            count(Counter.MIDSTREAM_CHAINS);
            String orderId = v(FixTags.ORDER_ID);
            if (!orderId.isEmpty() && !"NONE".equals(orderId)) {
                count(Counter.MIDSTREAM_WITH_KNOWN_ORDER_ID);
            }
        }

        void countStripped() {
            for (int i = 0, n = frame.count(); i < n; i++) {
                if (RecordWriter.stripped(frame.tagAt(i), options)) {
                    count(Counter.STRIPPED_TAGS);
                }
            }
        }

        /** 11, 41, 60 and 9022 become the message's; 41 only when the message's 41 is about this order. */
        void bookkeeping(boolean takeOrigClOrdId) {
            String clOrdId = orNull(FixTags.CL_ORD_ID);
            if (clOrdId != null) {
                s.lastClOrdId = clOrdId;
                mark(RecordField.CL_ORD_ID);
            }
            String origClOrdId = orNull(FixTags.ORIG_CL_ORD_ID);
            if (takeOrigClOrdId && origClOrdId != null) {
                s.lastOrigClOrdId = origClOrdId;
                mark(RecordField.ORIG_CL_ORD_ID);
            }
            String transactTime = orNull(FixTags.TRANSACT_TIME);
            if (transactTime != null) {
                s.lastTransactTime = transactTime;
                mark(RecordField.TRANSACT_TIME);
            }
            s.lastMsgType = msgType;
            mark(RecordField.LAST_MSG_TYPE);
            mark(RecordField.MSG_COUNT);
        }

        /** An applied message on an existing chain: bookkeeping, 9023 + 1. */
        void applied(boolean takeOrigClOrdId) {
            bookkeeping(takeOrigClOrdId);
            s.msgCount++;
            count(Counter.APPLIED);
            count(Counter.CHANGED);
        }

        /** Ignored on a resolved chain: 9025 + 1, carried to the next delta. */
        void ignore(IgnoreReason why) {
            kind = Outcome.Kind.IGNORED;
            reason = why;
            s.ignoredCount++;
            mark(RecordField.IGNORED_COUNT);
            count(counterFor(why));
        }

        /** Ignored with no chain to count it on. */
        void ignoreWithoutChain(IgnoreReason why) {
            kind = Outcome.Kind.IGNORED;
            reason = why;
            s = null;
            count(counterFor(why));
        }

        /**
         * Binds the message's ids to this chain (docs/03 §3.3): positive tags
         * into the ClOrdID map, {@link FixTags#ORDER_ID} into the OrderID map.
         * A new ClOrdID joins the chain list (9021, 9030), whatever the
         * outcome; an id bound elsewhere is left there and counted.
         */
        void stage(int... tags) {
            if (s == null) {
                return;
            }
            for (int tag : tags) {
                String id = v(tag);
                if (id.isEmpty()) {
                    continue;
                }
                if (tag == FixTags.ORDER_ID) {
                    if ("NONE".equals(id)) {
                        continue;
                    }
                    String bound = index.keyForOrderId(id);
                    if (bound == null) {
                        if (!s.orderIds.contains(id)) {
                            s.orderIds.addFirst(id);
                        }
                        bindings.add(new Outcome.Binding(true, id, key));
                    } else if (!bound.equals(key)) {
                        conflict("OrderID", id, bound);
                    }
                } else {
                    String bound = index.keyForClOrdId(id);
                    if (bound == null) {
                        if (!s.clOrdIds.contains(id)) {
                            s.clOrdIds.add(id);
                            mark(RecordField.CHAIN);
                        }
                        bindings.add(new Outcome.Binding(false, id, key));
                    } else if (!bound.equals(key)) {
                        conflict("ClOrdID", id, bound);
                    }
                }
            }
        }

        private void conflict(String kindOfId, String id, String bound) {
            count(Counter.ID_CONFLICTS);
            log.warn("{} {} is bound to {} and seen on {}'s {}; binding kept", kindOfId, id, bound, key, msgType);
        }

        Outcome outcome() {
            long mask;
            Outcome.Publish publish;
            if (s == null) {
                mask = 0;
                publish = Outcome.Publish.NONE;
            } else if (kind == Outcome.Kind.CREATED) {
                mask = RecordField.ALL;
                publish = creationPublish;
            } else if (kind == Outcome.Kind.CHANGED) {
                mask = s.carriedDirty | dirty | RecordField.KEY.bit();
                publish = Outcome.Publish.DELTA;
            } else {
                mask = s.carriedDirty | dirty | RecordField.KEY.bit();
                // A newly bound id is published so the index stays recoverable (R16).
                publish = RecordField.CHAIN.in(dirty) || options.publishUnchanged()
                        ? Outcome.Publish.DELTA : Outcome.Publish.NONE;
            }
            return new Outcome(sequence, kind, reason, key, s, mask, publish, passThrough, copyFillDetail,
                    zeroFillDetail, List.copyOf(bindings), counts, msgType, v(FixTags.EXEC_TYPE));
        }
    }

    private static Counter counterFor(IgnoreReason reason) {
        return switch (reason) {
            case DUPLICATE -> Counter.IGNORED_DUPLICATE;
            case STALE -> Counter.IGNORED_STALE;
            case LATE -> Counter.IGNORED_LATE;
            case ABSORBED -> Counter.IGNORED_ABSORBED;
            case LATE_FILL -> Counter.IGNORED_LATE_FILL;
            case DUPLICATE_REQUEST, LATE_REQUEST -> Counter.IGNORED_REQUEST;
            case UNMATCHED -> Counter.IGNORED_UNMATCHED;
            case KEY_CONFLICT -> Counter.IGNORED_KEY_CONFLICT;
            case UNROUTED -> Counter.IGNORED_UNROUTED;
            case NO_ID -> Counter.IGNORED_NO_ID;
            case HOOK -> Counter.IGNORED_HOOK;
        };
    }
}
