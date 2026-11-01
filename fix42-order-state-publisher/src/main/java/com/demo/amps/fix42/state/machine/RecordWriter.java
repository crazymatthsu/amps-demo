package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.FixWriter;
import java.util.Set;

/**
 * Encodes an {@link Outcome} as the blotter payload: tag 13011 first, the
 * message's own tags the outcome passes through, then the machine's fields
 * by the dirty mask (docs/02 §4-5).
 *
 * <p>Two rules every payload keeps. <b>13011 is always there</b>: the topic is
 * keyed on it, and a delta without its key is refused by AMPS. <b>No tag value
 * is ever empty</b>: an absent field is left out, and the cleared pending set
 * is the literal {@code NONE}, because a delta can overwrite a field but never
 * remove one.
 *
 * <p>The pass-through never copies the session header and trailer
 * ({@code strip-tags}), any tag in the machine's own ranges (9001-9099,
 * 13000-13999, 15000-15999 -- 9000 is kept, as a field), or a tag the machine
 * writes itself, so a venue or OMS custom tag cannot overwrite state
 * (R20e, S18).
 */
public final class RecordWriter {

    /** Written by the machine from its own state, never copied from a message. */
    private static final Set<Integer> MANAGED = Set.of(FixTags.MSG_TYPE, FixTags.CL_ORD_ID,
            FixTags.ORIG_CL_ORD_ID, FixTags.ORDER_ID, FixTags.ORDER_QTY, FixTags.PRICE,
            FixTags.TRANSACT_TIME, FixTags.PARENT_ORDER_ID);

    /** What a report says about one execution, not about the order's terms. */
    private static final Set<Integer> REPORT_ONLY = Set.of(FixTags.EXEC_ID, FixTags.EXEC_REF_ID,
            FixTags.EXEC_TRANS_TYPE, FixTags.EXEC_TYPE, FixTags.ORD_STATUS, FixTags.CUM_QTY,
            FixTags.LEAVES_QTY, FixTags.AVG_PX, FixTags.LAST_SHARES, FixTags.LAST_PX, FixTags.LAST_MKT,
            FixTags.TEXT, FixTags.ORD_REJ_REASON, FixTags.EXEC_RESTATEMENT_REASON,
            FixTags.CXL_REJ_RESPONSE_TO, FixTags.CXL_REJ_REASON);

    private final MachineOptions options;

    public RecordWriter(MachineOptions options) {
        this.options = options;
    }

    /**
     * The blotter payload for {@code outcome} into {@code out}.
     *
     * @param message the message the outcome was planned from: the source of
     *                the pass-through tags and the fill detail
     * @return whether anything was written; {@code false} for {@link Outcome.Publish#NONE}
     */
    public boolean write(Outcome outcome, FixFrame message, FixWriter out) {
        if (outcome.publish == Outcome.Publish.NONE) {
            return false;
        }
        out.field(FixTags.Record.ROOT_CL_ORD_ID, outcome.key);
        passThrough(outcome.passThrough, message, out);
        fields(outcome.staged, outcome.dirty & ~RecordField.KEY.bit(), out);
        if (outcome.copyFillDetail) {
            copyNonEmpty(message, FixTags.LAST_SHARES, out);
            copyNonEmpty(message, FixTags.LAST_PX, out);
            copyNonEmpty(message, FixTags.LAST_MKT, out);
        } else if (outcome.zeroFillDetail) {
            out.field(FixTags.LAST_SHARES, "0");
            out.field(FixTags.LAST_PX, "0");
        }
        return true;
    }

    /**
     * Every cached field: the repair after a rejected delta (a {@code
     * delta_publish} of this, never a {@code publish}, S2), and the round trip.
     */
    public void writeAll(OrderState state, FixWriter out) {
        out.field(FixTags.Record.ROOT_CL_ORD_ID, state.orderKey);
        fields(state, RecordField.ALL & ~RecordField.KEY.bit(), out);
    }

    /** Whether the machine never copies {@code tag} from a message onto the record. */
    static boolean stripped(int tag, MachineOptions options) {
        return options.stripTags().contains(tag) || inMachineRange(tag);
    }

    /** 9001-9099, 13000-13999, 15000-15999: the machine's own (9000 is a field, kept). */
    public static boolean inMachineRange(int tag) {
        return (tag > FixTags.PARENT_ORDER_ID && tag <= 9099)
                || (tag >= 13000 && tag <= 13999)
                || (tag >= 15000 && tag <= 15999);
    }

    private void passThrough(Outcome.PassThrough mode, FixFrame message, FixWriter out) {
        if (mode == Outcome.PassThrough.NONE || message == null) {
            return;
        }
        for (int i = 0, n = message.count(); i < n; i++) {
            int tag = message.tagAt(i);
            if (message.valueLengthAt(i) == 0 || MANAGED.contains(tag) || stripped(tag, options)) {
                continue;
            }
            boolean term = options.orderTermTags().contains(tag);
            boolean copy = switch (mode) {
                case DESCRIPTIVE -> !REPORT_ONLY.contains(tag);
                case DESCRIPTIVE_NO_TERMS -> !REPORT_ONLY.contains(tag) && !term;
                case ORDER_TERMS -> term;
                case NONE -> false;
            };
            // A tag repeated on the wire is written once: lookups see its last
            // occurrence, and so does the record.
            if (copy && !out.contains(tag)) {
                message.copyFieldAt(message.indexOf(tag), out);
            }
        }
    }

    private void fields(OrderState s, long mask, FixWriter out) {
        if (RecordField.KEY_KIND.in(mask)) {
            out.field(FixTags.Record.KEY_KIND, String.valueOf(s.keyKind));
        }
        if (RecordField.CHAIN.in(mask)) {
            put(out, FixTags.Record.CL_ORD_ID_CHAIN, RecordCodec.encodeIds(s.clOrdIds, options.chainIdsPublished()));
            out.field(FixTags.Record.CL_ORD_ID_COUNT, Integer.toString(s.clOrdIds.size()));
        }
        if (RecordField.WORKING.in(mask)) {
            put(out, FixTags.Record.WORKING_CL_ORD_ID, s.workingClOrdId);
        }
        if (RecordField.CL_ORD_ID.in(mask)) {
            put(out, FixTags.CL_ORD_ID, s.lastClOrdId);
        }
        if (RecordField.ORIG_CL_ORD_ID.in(mask)) {
            put(out, FixTags.ORIG_CL_ORD_ID, s.lastOrigClOrdId);
        }
        if (RecordField.ORDER_ID.in(mask)) {
            put(out, FixTags.ORDER_ID, s.latestOrderId());
        }
        if (RecordField.LINKED.in(mask)) {
            put(out, FixTags.Record.LINKED_CL_ORD_ID, s.linkedClOrdId);
        }
        if (RecordField.TRANSACT_TIME.in(mask)) {
            put(out, FixTags.TRANSACT_TIME, s.lastTransactTime);
        }
        if (RecordField.LAST_REPORT_TIME.in(mask)) {
            put(out, FixTags.Record.LAST_REPORT_TIME, s.lastReportTime);
        }
        if (RecordField.LAST_MSG_TYPE.in(mask)) {
            put(out, FixTags.Record.LAST_MSG_TYPE, s.lastMsgType);
        }
        if (RecordField.MSG_COUNT.in(mask)) {
            out.field(FixTags.Record.MSG_COUNT, Long.toString(s.msgCount));
        }
        if (RecordField.IGNORED_COUNT.in(mask)) {
            out.field(FixTags.Record.IGNORED_COUNT, Long.toString(s.ignoredCount));
        }
        if (RecordField.LATE_FILLS.in(mask)) {
            out.field(FixTags.Record.LATE_FILLS, Long.toString(s.lateFills));
        }
        if (RecordField.SCHEMA_VERSION.in(mask)) {
            out.field(FixTags.Record.SCHEMA_VERSION, Integer.toString(s.schemaVersion));
        }
        if (RecordField.ORDER_QTY.in(mask)) {
            put(out, FixTags.ORDER_QTY, s.ackedQty);
        }
        if (RecordField.PRICE.in(mask)) {
            put(out, FixTags.PRICE, s.ackedPx);
        }
        if (RecordField.ORD_STATUS.in(mask)) {
            put(out, FixTags.ORD_STATUS, s.venueStatus);
        }
        if (RecordField.EXEC_TYPE.in(mask)) {
            put(out, FixTags.EXEC_TYPE, s.lastExecType);
        }
        if (RecordField.EXEC_ID.in(mask)) {
            put(out, FixTags.EXEC_ID, s.lastExecId);
        }
        if (RecordField.CUM_QTY.in(mask)) {
            put(out, FixTags.CUM_QTY, s.cumQty);
        }
        if (RecordField.LEAVES_QTY.in(mask)) {
            put(out, FixTags.LEAVES_QTY, s.leavesQty);
        }
        if (RecordField.AVG_PX.in(mask)) {
            put(out, FixTags.AVG_PX, s.avgPx);
        }
        if (RecordField.LAST_FILL_EXEC_ID.in(mask)) {
            put(out, FixTags.Record.LAST_FILL_EXEC_ID, s.lastFillRecorded());
        }
        if (RecordField.BUSTED.in(mask)) {
            put(out, FixTags.Record.BUSTED_EXEC_IDS, RecordCodec.encodeIds(s.bustedExecIds, Integer.MAX_VALUE));
        }
        if (RecordField.PENDING.in(mask)) {
            pending(s, out);
        }
        if (RecordField.TERMINAL.in(mask)) {
            out.field(FixTags.Record.TERMINAL, s.terminal() ? "Y" : "N");
        }
        if (RecordField.ORIGIN.in(mask)) {
            out.field(FixTags.Record.CHAIN_ORIGIN, s.origin.name());
        }
        if (RecordField.PARENT.in(mask)) {
            put(out, FixTags.PARENT_ORDER_ID, s.parentOrderId);
        }
    }

    /**
     * The pending family: 15038/15044 the newest pending REPLACE's proposed
     * terms (0 when it is only inferred, or for a term it did not propose),
     * even when a cancel is the newest request; 15011/15035 the newest request
     * of any kind; 9015 the whole set and 9016 its size (docs/02 §4.1).
     */
    private static void pending(OrderState s, FixWriter out) {
        PendingRequest replace = s.newestReplace();
        boolean proposed = replace != null && replace.requested();
        out.field(FixTags.Record.PENDING_ORDER_QTY, proposed && replace.qty() != null ? replace.qty() : "0");
        out.field(FixTags.Record.PENDING_PRICE, proposed && replace.px() != null ? replace.px() : "0");
        PendingRequest newest = s.newestPending();
        out.field(FixTags.Record.PENDING_CL_ORD_ID, newest == null ? RecordCodec.NONE : newest.clOrdId());
        out.field(FixTags.Record.PENDING_ACTION, newest == null ? RecordCodec.NONE : newest.action().name());
        out.field(FixTags.Record.PENDING_REQUESTS, RecordCodec.encodePending(s.pending));
        out.field(FixTags.Record.PENDING_COUNT, Integer.toString(s.pending.size()));
    }

    private static void put(FixWriter out, int tag, String value) {
        if (value != null && !value.isEmpty()) {
            out.field(tag, value);
        }
    }

    private static void copyNonEmpty(FixFrame message, int tag, FixWriter out) {
        int index = message.indexOf(tag);
        if (index >= 0 && message.valueLengthAt(index) > 0) {
            message.copyFieldAt(index, out);
        }
    }
}
