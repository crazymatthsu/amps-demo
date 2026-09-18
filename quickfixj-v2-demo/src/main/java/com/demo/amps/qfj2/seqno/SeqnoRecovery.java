package com.demo.amps.qfj2.seqno;

import java.util.Optional;

/**
 * The startup decision, as a pure function: given the policy, what the file
 * holds and what AMPS holds, which numbers does the session start with?
 *
 * <p>Pure so every case is a unit test with no server and no QuickFIX/J. The
 * reason string is the operator-facing line the factory logs, written to say
 * what happened and what to expect -- the case that matters most being a
 * file <em>ahead</em> of AMPS under {@code amps-wins}, which means the last
 * instance's final increments never replicated and the counterparty will
 * notice on logon.
 */
public final class SeqnoRecovery {

    private SeqnoRecovery() {
    }

    /** Whose numbers were applied. */
    public enum Applied {
        FILE, AMPS, MERGED
    }

    /**
     * @param applied             whose numbers won
     * @param nextSenderMsgSeqNum the number to start sending with
     * @param nextTargetMsgSeqNum the number to expect next
     * @param reason              one line for the log
     */
    public record Decision(Applied applied, int nextSenderMsgSeqNum, int nextTargetMsgSeqNum, String reason) {

        public String numbers() {
            return nextSenderMsgSeqNum + "/" + nextTargetMsgSeqNum;
        }

        /** True when the file has to be rewritten to hold the decision. */
        public boolean rewritesFile(int fileSender, int fileTarget) {
            return nextSenderMsgSeqNum != fileSender || nextTargetMsgSeqNum != fileTarget;
        }
    }

    public static Decision decide(RecoveryPolicy policy, int fileSender, int fileTarget,
                                  Optional<SeqnoSnapshot> amps) {
        String file = fileSender + "/" + fileTarget;
        if (amps.isEmpty()) {
            return new Decision(Applied.FILE, fileSender, fileTarget,
                    "no checkpoint in AMPS: the file's " + file + " stands and becomes the first checkpoint");
        }
        SeqnoSnapshot checkpoint = amps.get();
        String ampsDesc = "AMPS checkpoint " + checkpoint.numbers() + " (rev " + checkpoint.revision() + ", "
                + checkpoint.source() + ", " + checkpoint.updatedAt() + ")";
        boolean same = checkpoint.nextSenderMsgSeqNum() == fileSender
                && checkpoint.nextTargetMsgSeqNum() == fileTarget;

        return switch (policy) {
            case FILE_WINS -> new Decision(Applied.FILE, fileSender, fileTarget,
                    "policy file-wins: the file's " + file + " stands; " + ampsDesc
                            + (same ? " agrees" : " is overwritten"));
            case AMPS_WINS -> {
                if (same) {
                    yield new Decision(Applied.AMPS, fileSender, fileTarget,
                            ampsDesc + " agrees with the file's " + file);
                }
                boolean fileAhead = fileSender > checkpoint.nextSenderMsgSeqNum()
                        || fileTarget > checkpoint.nextTargetMsgSeqNum();
                String warning = fileAhead
                        ? "; NOTE the file is AHEAD of AMPS, so the previous instance's last increments were "
                        + "not replicated before it stopped -- expect a resend request or a 'MsgSeqNum too "
                        + "low' logout from the counterparty, and resequence with the seqno-admin profile "
                        + "if it is the latter"
                        : "";
                yield new Decision(Applied.AMPS, checkpoint.nextSenderMsgSeqNum(),
                        checkpoint.nextTargetMsgSeqNum(),
                        "policy amps-wins: " + ampsDesc + " replaces the file's " + file + warning);
            }
            case HIGHEST -> {
                int sender = Math.max(fileSender, checkpoint.nextSenderMsgSeqNum());
                int target = Math.max(fileTarget, checkpoint.nextTargetMsgSeqNum());
                Applied applied;
                if (sender == checkpoint.nextSenderMsgSeqNum() && target == checkpoint.nextTargetMsgSeqNum()) {
                    applied = Applied.AMPS;
                } else if (sender == fileSender && target == fileTarget) {
                    applied = Applied.FILE;
                } else {
                    applied = Applied.MERGED;
                }
                yield new Decision(applied, sender, target,
                        "policy highest: " + sender + "/" + target + " from the file's " + file + " and " + ampsDesc);
            }
        };
    }
}
