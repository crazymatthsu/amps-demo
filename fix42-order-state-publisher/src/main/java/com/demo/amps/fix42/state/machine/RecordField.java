package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.fix.FixTags;

/**
 * The record fields the machine owns, one bit each in a dirty mask.
 *
 * <p>"Dirty" means <i>touched</i> by an apply, not changed: a fill re-sends 38
 * even when it did not move, which keeps the writer simple and the record
 * checkable against itself (docs/02 §5). {@link RecordWriter} walks the mask.
 * A field may write more than one tag ({@link #PENDING} is the whole pending
 * family); {@link #tags()} lists them.
 */
public enum RecordField {
    KEY(FixTags.Record.ROOT_CL_ORD_ID),
    KEY_KIND(FixTags.Record.KEY_KIND),
    CHAIN(FixTags.Record.CL_ORD_ID_CHAIN, FixTags.Record.CL_ORD_ID_COUNT),
    WORKING(FixTags.Record.WORKING_CL_ORD_ID),
    CL_ORD_ID(FixTags.CL_ORD_ID),
    ORIG_CL_ORD_ID(FixTags.ORIG_CL_ORD_ID),
    ORDER_ID(FixTags.ORDER_ID),
    LINKED(FixTags.Record.LINKED_CL_ORD_ID),
    TRANSACT_TIME(FixTags.TRANSACT_TIME),
    LAST_REPORT_TIME(FixTags.Record.LAST_REPORT_TIME),
    LAST_MSG_TYPE(FixTags.Record.LAST_MSG_TYPE),
    MSG_COUNT(FixTags.Record.MSG_COUNT),
    IGNORED_COUNT(FixTags.Record.IGNORED_COUNT),
    LATE_FILLS(FixTags.Record.LATE_FILLS),
    SCHEMA_VERSION(FixTags.Record.SCHEMA_VERSION),
    ORDER_QTY(FixTags.ORDER_QTY),
    PRICE(FixTags.PRICE),
    ORD_STATUS(FixTags.ORD_STATUS),
    EXEC_TYPE(FixTags.EXEC_TYPE),
    EXEC_ID(FixTags.EXEC_ID),
    CUM_QTY(FixTags.CUM_QTY),
    LEAVES_QTY(FixTags.LEAVES_QTY),
    AVG_PX(FixTags.AVG_PX),
    LAST_FILL_EXEC_ID(FixTags.Record.LAST_FILL_EXEC_ID),
    BUSTED(FixTags.Record.BUSTED_EXEC_IDS),
    PENDING(FixTags.Record.PENDING_ORDER_QTY, FixTags.Record.PENDING_PRICE,
            FixTags.Record.PENDING_CL_ORD_ID, FixTags.Record.PENDING_ACTION,
            FixTags.Record.PENDING_REQUESTS, FixTags.Record.PENDING_COUNT),
    TERMINAL(FixTags.Record.TERMINAL),
    ORIGIN(FixTags.Record.CHAIN_ORIGIN),
    PARENT(FixTags.PARENT_ORDER_ID);

    /** Every field: what a FULL publish, a MIDSTREAM creation and a repair write. */
    public static final long ALL = (1L << values().length) - 1;

    /** What every applied message carries: 11, 41, 60, 12022, 12023 (docs/03 §6.6). */
    public static final long BOOKKEEPING = CL_ORD_ID.bit() | ORIG_CL_ORD_ID.bit()
            | TRANSACT_TIME.bit() | LAST_MSG_TYPE.bit() | MSG_COUNT.bit();

    private final int[] tags;

    RecordField(int... tags) {
        this.tags = tags;
    }

    public long bit() {
        return 1L << ordinal();
    }

    public boolean in(long mask) {
        return (mask & bit()) != 0;
    }

    public int[] tags() {
        return tags.clone();
    }

    /** Every tag the machine writes, for the strip list and the hooks' guard. */
    public static boolean isMachineTag(int tag) {
        for (RecordField field : values()) {
            for (int owned : field.tags) {
                if (owned == tag) {
                    return true;
                }
            }
        }
        return false;
    }
}
