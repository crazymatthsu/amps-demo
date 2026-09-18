package com.demo.amps.qfj2.engine;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.OrigSendingTime;
import quickfix.field.PossDupFlag;
import quickfix.field.PossResend;
import quickfix.field.SendingTime;

/** Small helpers over {@code quickfix.Message}. */
public final class FixMessages {

    /** The FIX field separator. */
    public static final char SOH = 1;

    private FixMessages() {
    }

    /** Tag 35, or {@code ?} if the header lacks it. */
    public static String msgType(Message message) {
        try {
            return message.getHeader().getString(MsgType.FIELD);
        } catch (FieldNotFound e) {
            return "?";
        }
    }

    /** The wire form with SOH shown as {@code |}, for logs. */
    public static String printable(Message message) {
        return message.toString().replace(SOH, '|');
    }

    /**
     * A copy fit to send on another session: the body as is, the header
     * stripped of everything the target session will set for itself
     * (sequence number, sending time, the possible-duplicate markers).
     * {@code Session.sendToTarget(message, sessionID)} then overwrites the
     * BeginString and comp ids with the target's own before sending.
     */
    public static Message forSession(Message source) {
        Message copy = (Message) source.clone();
        Message.Header header = copy.getHeader();
        header.removeField(MsgSeqNum.FIELD);
        header.removeField(SendingTime.FIELD);
        header.removeField(OrigSendingTime.FIELD);
        header.removeField(PossDupFlag.FIELD);
        header.removeField(PossResend.FIELD);
        return copy;
    }
}
