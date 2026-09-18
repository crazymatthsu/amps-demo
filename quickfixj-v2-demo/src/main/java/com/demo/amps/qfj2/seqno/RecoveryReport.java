package com.demo.amps.qfj2.seqno;

import java.util.Optional;

/**
 * What happened when a session's store was created: what the file held, what
 * AMPS held, and what was decided. Logged once per session at startup and
 * kept on the factory so a test (or an operator with a debugger) can ask.
 *
 * @param sessionId        the session
 * @param fileSenderBefore the file's next sender number before recovery
 * @param fileTargetBefore the file's next target number before recovery
 * @param ampsCheckpoint   what AMPS held, if anything
 * @param decision         the numbers applied, and why
 */
public record RecoveryReport(
        String sessionId,
        int fileSenderBefore,
        int fileTargetBefore,
        Optional<SeqnoSnapshot> ampsCheckpoint,
        SeqnoRecovery.Decision decision) {

    public SeqnoRecovery.Applied applied() {
        return decision.applied();
    }

    public String describe() {
        return "seqno recovery for " + sessionId + ": file held " + fileSenderBefore + "/" + fileTargetBefore
                + "; " + decision.reason() + "; starting with next sender/target " + decision.numbers();
    }
}
