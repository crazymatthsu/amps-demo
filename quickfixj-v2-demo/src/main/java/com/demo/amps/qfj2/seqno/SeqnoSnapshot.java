package com.demo.amps.qfj2.seqno;

import java.time.Instant;
import java.util.Objects;

/**
 * One FIX session's sequence-number checkpoint: the NEXT MsgSeqNum the engine
 * will send and the NEXT one it expects to receive -- exactly the two numbers
 * QuickFIX/J keeps in its {@code .seqnums} file, named the way QuickFIX/J
 * names them so nobody has to remember whether a value is "last" or "next".
 *
 * <p>This is what the write-behind thread publishes to AMPS after every
 * change, and what a starting engine reads back. The rest of the fields say
 * where it came from: {@code source} is the instance that wrote it (a host
 * name, or {@code admin} for a manual resequence), {@code revision} counts
 * checkpoints for the session across instances so the journal shows the
 * order, and {@code creationTime} is the file store's own creation time,
 * which QuickFIX/J uses to decide whether a scheduled session has rolled
 * over.
 *
 * @param sessionId           QuickFIX/J's {@code SessionID.toString()}, e.g.
 *                            {@code FIX.4.2:DROPCOPY->VENUE}; the SOW key
 * @param nextSenderMsgSeqNum the next MsgSeqNum this side will send
 * @param nextTargetMsgSeqNum the next MsgSeqNum this side expects to receive
 * @param creationTime        when the file store was created
 * @param updatedAt           when this checkpoint was taken
 * @param source              which instance (or tool) wrote it
 * @param revision            monotonic per session, across instances
 */
public record SeqnoSnapshot(
        String sessionId,
        int nextSenderMsgSeqNum,
        int nextTargetMsgSeqNum,
        Instant creationTime,
        Instant updatedAt,
        String source,
        long revision) {

    public SeqnoSnapshot {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (nextSenderMsgSeqNum < 1 || nextTargetMsgSeqNum < 1) {
            throw new IllegalArgumentException("FIX sequence numbers start at 1; got next sender/target "
                    + nextSenderMsgSeqNum + "/" + nextTargetMsgSeqNum + " for " + sessionId);
        }
        Objects.requireNonNull(creationTime, "creationTime");
        Objects.requireNonNull(updatedAt, "updatedAt");
        source = source == null ? "" : source;
    }

    /** True when the two carry the same next sender and next target numbers. */
    public boolean sameNumbersAs(SeqnoSnapshot other) {
        return other != null
                && nextSenderMsgSeqNum == other.nextSenderMsgSeqNum
                && nextTargetMsgSeqNum == other.nextTargetMsgSeqNum;
    }

    /** {@code sender/target}, the way the operator tools print a pair. */
    public String numbers() {
        return nextSenderMsgSeqNum + "/" + nextTargetMsgSeqNum;
    }

    /** One line for a log: session, numbers, and where they came from. */
    public String describe() {
        return sessionId + " next sender/target " + numbers()
                + " (rev " + revision + ", " + source + ", " + updatedAt + ")";
    }
}
