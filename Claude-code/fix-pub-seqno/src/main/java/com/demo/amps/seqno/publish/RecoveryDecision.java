package com.demo.amps.seqno.publish;

import java.util.List;

/**
 * The outcome of reconciling what AMPS has against what the outbox has:
 * either "republish this range" or "stop, a human is needed".
 *
 * <p>A pure value, produced by {@link GapRecovery} from three numbers and a
 * scan result, so the decision can be unit-tested with no server. The
 * orchestration in {@link PublisherRecovery} does what the decision says and
 * nothing else.
 *
 * @param action        republish the gap, or halt
 * @param resolvedL     the last 8888 AMPS is trusted to hold, after
 *                      reconciling the SOW and journal answers
 * @param republishFrom exclusive lower bound of the gap to republish
 * @param republishTo   inclusive upper bound (the outbox's last sequence)
 * @param haltReason    why publishing must not resume, when {@code action} is
 *                      {@link Action#HALT}; otherwise empty
 * @param alarms        invariant violations that do not stop recovery but must
 *                      be surfaced -- a regressed SOW, a gap or duplicate in
 *                      the journal, a verification that could only be partial
 */
public record RecoveryDecision(Action action, long resolvedL, long republishFrom,
                               long republishTo, String haltReason, List<String> alarms) {

    public enum Action {
        /** Republish {@code (republishFrom, republishTo]} from the outbox. */
        REPUBLISH,
        /** Do not publish; {@code haltReason} says why. */
        HALT
    }

    public RecoveryDecision {
        alarms = List.copyOf(alarms);
    }

    /** How many messages the republish covers; 0 when already in sync. */
    public long gapSize() {
        return action == Action.REPUBLISH ? Math.max(0, republishTo - republishFrom) : 0;
    }

    public boolean isInSync() {
        return action == Action.REPUBLISH && gapSize() == 0;
    }

    public boolean hasAlarms() {
        return !alarms.isEmpty();
    }
}
