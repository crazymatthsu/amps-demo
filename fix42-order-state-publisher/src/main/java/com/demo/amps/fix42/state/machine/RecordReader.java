package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import java.util.ArrayList;

/**
 * The other half of {@link RecordWriter}: an {@code order_state} record back
 * into a cache entry. The SOW is the checkpoint (docs/04 §1); this is how a
 * restart, a cache miss and an evicted order's return read it.
 *
 * <p>What is not recovered, by design: the ExecID ring behind the last ExecID
 * (17), so duplicate detection is depth one for the first reports after a
 * rebuild, and OrderIDs before the latest (37) -- earlier ones are on
 * {@code execs_audit} under this 13011.
 */
public final class RecordReader {

    /**
     * One record, read.
     *
     * @param state       the rebuilt entry
     * @param defaulted   a field of the current schema was missing and took
     *                    its default ({@code recoveredWithDefaults})
     * @param unrecovered 9015, 9021 or 9031 did not parse; the entry was
     *                    loaded with an empty set and {@code 9018=UNRECOVERED}
     */
    public record Result(OrderState state, boolean defaulted, boolean unrecovered) {
    }

    private RecordReader() {
    }

    /**
     * @throws IllegalArgumentException when the record has no 13011 (the
     *         publisher is pointed at the wrong topic) or a schema version
     *         newer than {@code knownSchema}: either refuses the start (R17)
     */
    public static Result read(FixMessage record, int knownSchema) {
        String key = record.value(FixTags.Record.ROOT_CL_ORD_ID);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("a record without tag 13011: " + record.printable());
        }
        boolean defaulted = false;
        boolean unrecovered = false;
        OrderState s = new OrderState();
        s.orderKey = key;

        String schema = record.value(FixTags.Record.SCHEMA_VERSION);
        if (schema.isEmpty()) {
            defaulted = true;
            s.schemaVersion = 1;
        } else {
            s.schemaVersion = Integer.parseInt(schema);
            if (s.schemaVersion > knownSchema) {
                throw new IllegalArgumentException("record " + key + " has schema " + schema
                        + "; this publisher knows " + knownSchema);
            }
        }
        String keyKind = record.value(FixTags.Record.KEY_KIND);
        if (keyKind.isEmpty()) {
            defaulted = true;
        } else {
            s.keyKind = keyKind.charAt(0);
        }

        try {
            s.clOrdIds = RecordCodec.decodeIds(record.value(FixTags.Record.CL_ORD_ID_CHAIN));
        } catch (IllegalArgumentException e) {
            s.clOrdIds = new ArrayList<>();
            unrecovered = true;
        }
        try {
            String pending = record.value(FixTags.Record.PENDING_REQUESTS);
            if (pending.isEmpty()) {
                defaulted = true;
            } else {
                s.pending = RecordCodec.decodePending(pending);
            }
        } catch (IllegalArgumentException e) {
            s.pending = new ArrayList<>();
            unrecovered = true;
        }
        try {
            s.bustedExecIds = RecordCodec.decodeIds(record.value(FixTags.Record.BUSTED_EXEC_IDS));
        } catch (IllegalArgumentException e) {
            s.bustedExecIds = new ArrayList<>();
            unrecovered = true;
        }

        s.workingClOrdId = orNull(record, FixTags.Record.WORKING_CL_ORD_ID);
        String orderId = orNull(record, FixTags.ORDER_ID);
        if (orderId != null) {
            s.orderIds.add(orderId);
        }
        s.parentOrderId = orNull(record, FixTags.PARENT_ORDER_ID);
        s.linkedClOrdId = orNull(record, FixTags.Record.LINKED_CL_ORD_ID);
        s.ackedQty = orNull(record, FixTags.ORDER_QTY);
        s.ackedPx = orNull(record, FixTags.PRICE);
        s.timeInForce = orNull(record, FixTags.TIME_IN_FORCE);
        s.venueStatus = orNull(record, FixTags.ORD_STATUS);
        s.lastExecType = orNull(record, FixTags.EXEC_TYPE);
        s.cumQty = orNull(record, FixTags.CUM_QTY);
        s.leavesQty = orNull(record, FixTags.LEAVES_QTY);
        s.avgPx = orNull(record, FixTags.AVG_PX);
        s.lastExecId = orNull(record, FixTags.EXEC_ID);
        s.lastFillExecId = orNull(record, FixTags.Record.LAST_FILL_EXEC_ID);
        s.lastReportTime = orNull(record, FixTags.Record.LAST_REPORT_TIME);
        s.lastClOrdId = orNull(record, FixTags.CL_ORD_ID);
        s.lastOrigClOrdId = orNull(record, FixTags.ORIG_CL_ORD_ID);
        s.lastTransactTime = orNull(record, FixTags.TRANSACT_TIME);
        s.lastMsgType = orNull(record, FixTags.Record.LAST_MSG_TYPE);

        long[] counts = new long[3];
        int[] tags = {FixTags.Record.MSG_COUNT, FixTags.Record.IGNORED_COUNT, FixTags.Record.LATE_FILLS};
        for (int i = 0; i < tags.length; i++) {
            String value = record.value(tags[i]);
            if (value.isEmpty()) {
                defaulted = true;
            } else {
                counts[i] = Long.parseLong(value);
            }
        }
        s.msgCount = counts[0];
        s.ignoredCount = counts[1];
        s.lateFills = counts[2];

        String origin = record.value(FixTags.Record.CHAIN_ORIGIN);
        if (origin.isEmpty()) {
            defaulted = true;
        } else {
            try {
                s.origin = Origin.valueOf(origin);
            } catch (IllegalArgumentException e) {
                unrecovered = true;
            }
        }
        if (unrecovered) {
            s.origin = Origin.UNRECOVERED;
            s.pending.clear();
        }
        return new Result(s, defaulted, unrecovered);
    }

    private static String orNull(FixMessage record, int tag) {
        String value = record.value(tag);
        return value.isEmpty() ? null : value;
    }
}
