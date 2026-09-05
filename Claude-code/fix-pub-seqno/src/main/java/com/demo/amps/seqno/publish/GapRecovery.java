package com.demo.amps.seqno.publish;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * The reconciliation rule at the heart of the design, as a pure function.
 *
 * <p>Given what the SOW says the last sequence number is, what a journal scan
 * found, and what the outbox holds, it decides what to republish -- or that
 * the situation is unsafe and needs a person. Kept free of any AMPS type so it
 * can be exhaustively unit-tested; {@link PublisherRecovery} is the part that
 * talks to a server.
 *
 * <p>The rules are the table in {@code docs/04-chosen-design-and-failure-matrix.md},
 * step 5. In one sentence: trust the higher of the two server answers for L,
 * raise an alarm whenever they disagree or the journal shows a broken
 * invariant, and refuse to publish at all if AMPS turns out to hold more than
 * the outbox knows about -- because that is the one situation where resuming
 * would reuse a sequence number.
 */
public final class GapRecovery {

    private GapRecovery() {
    }

    /**
     * @param sowLast    the SOW's last-8888 for this sender, empty when the SOW
     *                   has no record of it
     * @param scan       what the journal scan found
     * @param outboxLast the highest 8888 in the publisher's outbox
     */
    public static RecoveryDecision reconcile(OptionalLong sowLast, JournalScanResult scan,
                                             long outboxLast) {
        List<String> alarms = new ArrayList<>();
        boolean sowPresent = sowLast.isPresent();
        long lSow = sowLast.orElse(0);
        OptionalLong journalMax = scan.max();

        long resolvedL;
        if (journalMax.isEmpty() && !sowPresent) {
            // Nothing anywhere: a fresh sender, or an empty journal on first run.
            resolvedL = 0;
        } else if (journalMax.isEmpty()) {
            // The SOW knows this sender but the scan found nothing: the journal
            // aged out past the lookback, or the lookback is too short. Trust
            // the SOW, but say the verification could not be done.
            resolvedL = lSow;
            alarms.add("journal scan found no messages for the sender, but the SOW reports last="
                    + lSow + "; verification is partial (the journal may have aged out past the "
                    + "lookback, or the lookback is too short)");
        } else if (!sowPresent) {
            // The journal has messages the SOW does not summarise: the SOW file
            // was reset, or this topic has no SOW at all (scan-only mode).
            resolvedL = journalMax.getAsLong();
            alarms.add("the SOW has no record for the sender but the journal holds messages up to "
                    + resolvedL + "; the SOW may have been reset (recovery proceeds from the journal)");
        } else {
            long lJournal = journalMax.getAsLong();
            if (lJournal == lSow) {
                resolvedL = lJournal;
            } else if (lJournal > lSow) {
                resolvedL = lJournal;
                alarms.add("the SOW (last=" + lSow + ") is behind the journal (last=" + lJournal
                        + "): a publisher may have resent an older message, or the SOW was reset");
            } else {
                resolvedL = lSow;
                alarms.add("the journal scan reached only last=" + lJournal + " but the SOW reports "
                        + lSow + "; verification is partial (widen the lookback to cover the gap)");
            }
        }

        if (!scan.duplicates().isEmpty()) {
            alarms.add("the journal holds DUPLICATE sequence numbers for the sender: "
                    + scan.duplicates() + " (something was republished that AMPS already had)");
        }
        if (!scan.gaps().isEmpty()) {
            alarms.add("the journal has GAPS in the sender's sequence within the scanned window: "
                    + scan.gaps() + " (the prefix invariant is broken)");
        }

        if (resolvedL > outboxLast) {
            String reason = "AMPS holds sequence " + resolvedL + " for this sender, but the outbox "
                    + "only reaches " + outboxLast + ". The outbox was lost or truncated; resuming "
                    + "would reuse sequence numbers. Rebuild the outbox from the journal before "
                    + "publishing again.";
            return new RecoveryDecision(RecoveryDecision.Action.HALT, resolvedL,
                    resolvedL, outboxLast, reason, alarms);
        }

        return new RecoveryDecision(RecoveryDecision.Action.REPUBLISH, resolvedL,
                resolvedL, outboxLast, "", alarms);
    }
}
