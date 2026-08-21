package com.demo.amps.fix42.fix;

/**
 * The FIX 4.2 tags this publisher knows by name.
 *
 * <p>Only the tags the mock flow actually emits are listed. The vocabulary
 * follows the project contract in {@code docs/fix42/01-fix42-messages-and-state-machine.md};
 * the one addition is {@link #PARENT_ORDER_ID}, a user-defined tag.
 */
public final class FixTags {

    private FixTags() {
    }

    // ---- session / envelope -------------------------------------------------
    /** MsgType: D, G, F, 8, 9. */
    public static final int MSG_TYPE = 35;
    /** SendingTime. */
    public static final int SENDING_TIME = 52;
    /** TransactTime -- the business timestamp, sent on every message here. */
    public static final int TRANSACT_TIME = 60;

    // ---- identity (the chaining tags) --------------------------------------
    /** ClOrdID: a NEW value on every D/G/F. Primary key of the chaining module. */
    public static final int CL_ORD_ID = 11;
    /** OrigClOrdID: the ClOrdID being acted on. Secondary key of the chaining module. */
    public static final int ORIG_CL_ORD_ID = 41;
    /** OrderID: the venue id, stable per chain, first seen on the first 35=8. */
    public static final int ORDER_ID = 37;
    /** ExecID: unique per execution report; the dedupe key and the execs_audit key. */
    public static final int EXEC_ID = 17;
    /** ExecRefID: on a bust/correct, the ExecID being cancelled or corrected. */
    public static final int EXEC_REF_ID = 19;

    /**
     * ParentOrderID -- user-defined tag 9000.
     *
     * <p>FIX 4.2 has no standard parent/child order field, so this is a custom
     * tag in the user-defined range (5000+), exactly as tag 9001 carries an
     * event id elsewhere in this repository. Present on a 35=D means "this is a
     * child slice of that parent order"; absent means the order is a parent.
     * It is carried through the chain as an ordinary field, so the hierarchy is
     * an ordinary content filter: {@code /9000 = 'PARENT-CLORDID'}.
     */
    public static final int PARENT_ORDER_ID = 9000;

    /**
     * The pending-state family -- user-defined tags 9010-9013.
     *
     * <p>These exist because FIX 4.2 stages an amend's terms until the venue
     * confirms, and a delta merge has no notion of staging. Writing a 35=G's
     * proposed OrderQty into tag 38 overwrites the quantity the venue actually
     * acked, and if the amend is then rejected the record is simply wrong --
     * with no way to put it back, because a merge can overwrite but never
     * remove.
     *
     * <p>Carrying the proposal in its own tags fixes that by never creating
     * the conflict: 38/44 stay the acked terms, 9010/9011 hold what was asked
     * for, and a reject clears the proposal without touching the acked values.
     * The record then answers both questions at once -- "working at what?" and
     * "asked to change to what?" -- which one tag 38 never can.
     *
     * <p>Cleared by writing {@link #PENDING_NONE} and zeros rather than by
     * removing the fields, since a delta publish cannot remove a field.
     */
    public static final int PENDING_ORDER_QTY = 9010;
    public static final int PENDING_PRICE = 9011;
    public static final int PENDING_CL_ORD_ID = 9012;
    public static final int PENDING_ACTION = 9013;

    /**
     * WorkingClOrdID -- tag 9014: the ClOrdID the venue currently recognises.
     *
     * <p>Needed because tag 11 on the blotter cannot mean this. Tag 11 and
     * tag 41 are the chaining key generator's OWN inputs: it binds a new
     * ClOrdID into an existing chain by seeing 11=&lt;new&gt; alongside
     * 41=&lt;known&gt;. Rewriting tag 11 to "the working id" hides that linkage
     * and the module opens a SEPARATE chain for the new id -- which shows up
     * as an order splitting into two records. So 11/41 stay exactly as FIX
     * sent them, and the working id gets its own field.
     *
     * <p>Maintained purely by omission: a request never publishes it, so a
     * delta merge leaves the previous value in place, and only a confirming
     * report moves it. That is why a rejected amend needs no revert here
     * either.
     */
    public static final int WORKING_CL_ORD_ID = 9014;

    /** Values for {@link #PENDING_ACTION} (tag 9013). */
    public static final class PendingAction {
        private PendingAction() {
        }

        /** No request in flight; the record's 38/44 are the venue's truth. */
        public static final String NONE = "NONE";
        /** A 35=D has been sent and no execution report has answered it. */
        public static final String NEW = "NEW";
        /** A 35=G is in flight; 9010/9011 hold its proposed terms. */
        public static final String REPLACE = "REPLACE";
        /** A 35=F is in flight; 9012 holds its ClOrdID. */
        public static final String CANCEL = "CANCEL";
    }

    /** Convenience alias for the cleared state, used by the routing rules. */
    public static final String PENDING_NONE = PendingAction.NONE;

    // ---- order terms --------------------------------------------------------
    public static final int ACCOUNT = 1;
    public static final int AVG_PX = 6;
    public static final int CUM_QTY = 14;
    public static final int CURRENCY = 15;
    public static final int EXEC_TRANS_TYPE = 20;
    /** HandlInst: required on D and G in FIX 4.2. */
    public static final int HANDL_INST = 21;
    public static final int SECURITY_ID_SOURCE = 22;
    public static final int LAST_MKT = 30;
    public static final int LAST_PX = 31;
    public static final int LAST_SHARES = 32;
    public static final int ORDER_QTY = 38;
    public static final int ORD_STATUS = 39;
    public static final int ORD_TYPE = 40;
    public static final int PRICE = 44;
    public static final int RULE_80A = 47;
    public static final int SECURITY_ID = 48;
    public static final int SIDE = 54;
    public static final int SYMBOL = 55;
    public static final int TEXT = 58;
    public static final int TIME_IN_FORCE = 59;
    public static final int EX_DESTINATION = 100;
    public static final int CXL_REJ_REASON = 102;
    public static final int ORD_REJ_REASON = 103;
    public static final int CLIENT_ID = 109;
    public static final int MIN_QTY = 110;
    public static final int MAX_FLOOR = 111;
    public static final int EXPIRE_TIME = 126;
    public static final int EXEC_TYPE = 150;
    public static final int LEAVES_QTY = 151;
    public static final int CXL_REJ_RESPONSE_TO = 434;

    // ---- the enum values used by the routing rules -------------------------

    /** MsgType values. */
    public static final class MsgType {
        private MsgType() {
        }

        public static final String NEW_ORDER_SINGLE = "D";
        public static final String ORDER_CANCEL_REPLACE_REQUEST = "G";
        public static final String ORDER_CANCEL_REQUEST = "F";
        public static final String EXECUTION_REPORT = "8";
        public static final String ORDER_CANCEL_REJECT = "9";
    }

    /** ExecType (150) values -- what a given execution report IS. */
    public static final class ExecType {
        private ExecType() {
        }

        public static final String NEW = "0";
        public static final String PARTIAL_FILL = "1";
        public static final String FILL = "2";
        public static final String DONE_FOR_DAY = "3";
        public static final String CANCELED = "4";
        public static final String REPLACED = "5";
        public static final String PENDING_CANCEL = "6";
        public static final String REJECTED = "8";
        public static final String PENDING_NEW = "A";
        public static final String PENDING_REPLACE = "E";
    }

    /** OrdStatus (39) values. */
    public static final class OrdStatus {
        private OrdStatus() {
        }

        public static final String NEW = "0";
        public static final String PARTIALLY_FILLED = "1";
        public static final String FILLED = "2";
        public static final String DONE_FOR_DAY = "3";
        public static final String CANCELED = "4";
        public static final String REPLACED = "5";
        public static final String PENDING_CANCEL = "6";
        public static final String REJECTED = "8";
        public static final String PENDING_NEW = "A";
        public static final String PENDING_REPLACE = "E";
    }
}
