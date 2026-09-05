package com.demo.amps.seqno.fix;

/**
 * The FIX 4.2 tags this module uses by name.
 *
 * <p>Only application-level tags, plus the two the design turns on. Session
 * tags (8, 9, 10, 34) belong to a FIX session between two engines; AMPS is
 * not a session endpoint, and the sender sequence number this module cares
 * about is carried in its own tag precisely because it is an application
 * fact, not a session one.
 */
public final class FixTags {

    private FixTags() {
    }

    /**
     * SenderSeqNum, user-defined tag 8888: the publisher's own contiguous
     * sequence number, assigned before the message leaves its outbox. The
     * whole of this module is about this tag.
     */
    public static final int SENDER_SEQ_NUM = 8888;

    /**
     * SenderCompID: the publisher's identity, and the SOW key of the topic.
     * A transaction-logged instance tracks publishers by client name; the SOW
     * tracks their last message by this tag; both derive from the same value.
     */
    public static final int SENDER_COMP_ID = 49;
    public static final int TARGET_COMP_ID = 56;
    public static final int SENDING_TIME = 52;

    public static final int MSG_TYPE = 35;
    public static final int ACCOUNT = 1;
    public static final int CL_ORD_ID = 11;
    public static final int CURRENCY = 15;
    public static final int HANDL_INST = 21;
    public static final int ORDER_QTY = 38;
    public static final int ORD_TYPE = 40;
    public static final int PRICE = 44;
    public static final int SIDE = 54;
    public static final int SYMBOL = 55;
    public static final int TIME_IN_FORCE = 59;
    public static final int TRANSACT_TIME = 60;

    /** The one message type the mock feed produces. */
    public static final String NEW_ORDER_SINGLE = "D";
}
