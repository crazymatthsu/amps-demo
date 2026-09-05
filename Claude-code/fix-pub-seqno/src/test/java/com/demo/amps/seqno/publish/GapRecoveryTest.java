package com.demo.amps.seqno.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * The failure matrix ({@code docs/04}) as executable assertions. Every row of
 * the reconciliation table is one test, with no server: {@link GapRecovery} is
 * a pure function of three numbers and a scan.
 */
class GapRecoveryTest {

    private static JournalScanResult scanOf(long... sequences) {
        JournalScanResult.Builder builder = JournalScanResult.builder();
        for (long sequence : sequences) {
            builder.observe(sequence);
        }
        return builder.build();
    }

    private static long[] range(long fromInclusive, long toInclusive) {
        long[] out = new long[(int) (toInclusive - fromInclusive + 1)];
        for (int i = 0; i < out.length; i++) {
            out[i] = fromInclusive + i;
        }
        return out;
    }

    @Test
    void inSyncWhenAmpsAndOutboxAgree() {
        RecoveryDecision decision = GapRecovery.reconcile(OptionalLong.of(10), scanOf(range(1, 10)), 10);
        assertEquals(RecoveryDecision.Action.REPUBLISH, decision.action());
        assertEquals(10, decision.resolvedL());
        assertTrue(decision.isInSync(), "nothing to republish");
        assertFalse(decision.hasAlarms());
    }

    @Test
    void republishesTheGapWhenTheOutboxIsAhead() {
        // The crash case: AMPS has 1..8, the outbox has 1..12.
        RecoveryDecision decision = GapRecovery.reconcile(OptionalLong.of(8), scanOf(range(1, 8)), 12);
        assertEquals(RecoveryDecision.Action.REPUBLISH, decision.action());
        assertEquals(8, decision.resolvedL());
        assertEquals(8, decision.republishFrom());
        assertEquals(12, decision.republishTo());
        assertEquals(4, decision.gapSize());
        assertFalse(decision.hasAlarms());
    }

    @Test
    void firstRunFromEmptyJournalRepublishesEverything() {
        RecoveryDecision decision = GapRecovery.reconcile(OptionalLong.empty(), scanOf(), 5);
        assertEquals(RecoveryDecision.Action.REPUBLISH, decision.action());
        assertEquals(0, decision.resolvedL());
        assertEquals(5, decision.gapSize(), "the whole outbox is the gap on a fresh journal");
        assertFalse(decision.hasAlarms());
    }

    @Test
    void haltsWhenAmpsIsAheadOfTheOutbox() {
        // The outbox was lost or truncated: AMPS has 12, the outbox only 8.
        RecoveryDecision decision = GapRecovery.reconcile(OptionalLong.of(12), scanOf(range(1, 12)), 8);
        assertEquals(RecoveryDecision.Action.HALT, decision.action());
        assertTrue(decision.haltReason().contains("reuse sequence numbers"));
    }

    @Test
    void alarmsButProceedsWhenTheSowIsBehindTheJournal() {
        // A publisher resent an older message: the SOW regressed to 6 while the
        // journal still holds 1..10. Trust the journal; raise the alarm.
        RecoveryDecision decision = GapRecovery.reconcile(OptionalLong.of(6), scanOf(range(1, 10)), 10);
        assertEquals(RecoveryDecision.Action.REPUBLISH, decision.action());
        assertEquals(10, decision.resolvedL(), "the journal wins over a regressed SOW");
        assertTrue(decision.isInSync());
        assertTrue(decision.alarms().stream().anyMatch(a -> a.contains("behind the journal")));
    }

    @Test
    void alarmsOnPartialVerificationWhenTheScanMissedOlderMessages() {
        // The lookback was too short: the scan only reached 5..10, but the SOW
        // knows 12. Trust the SOW, flag the verification as partial.
        RecoveryDecision decision = GapRecovery.reconcile(OptionalLong.of(12), scanOf(range(5, 10)), 12);
        assertEquals(RecoveryDecision.Action.REPUBLISH, decision.action());
        assertEquals(12, decision.resolvedL());
        assertTrue(decision.isInSync());
        assertTrue(decision.alarms().stream().anyMatch(a -> a.contains("partial")));
    }

    @Test
    void alarmsWhenTheSowWasResetButTheJournalSurvived() {
        RecoveryDecision decision = GapRecovery.reconcile(OptionalLong.empty(), scanOf(range(1, 9)), 9);
        assertEquals(RecoveryDecision.Action.REPUBLISH, decision.action());
        assertEquals(9, decision.resolvedL());
        assertTrue(decision.alarms().stream().anyMatch(a -> a.contains("SOW may have been reset")));
    }

    @Test
    void alarmsWhenTheJournalHoldsDuplicates() {
        // The naive publisher's aftermath: 9 appears twice in the journal.
        JournalScanResult scan = scanOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 9);
        RecoveryDecision decision = GapRecovery.reconcile(OptionalLong.of(9), scan, 9);
        assertTrue(decision.alarms().stream().anyMatch(a -> a.contains("DUPLICATE")));
    }

    @Test
    void alarmsWhenTheJournalHasAGap() {
        // 4 is missing: the prefix invariant is broken.
        JournalScanResult scan = scanOf(1, 2, 3, 5, 6);
        RecoveryDecision decision = GapRecovery.reconcile(OptionalLong.of(6), scan, 6);
        assertTrue(decision.alarms().stream().anyMatch(a -> a.contains("GAP")));
    }

    @Test
    void journalScanResultReportsContiguityGapsAndDuplicates() {
        assertTrue(scanOf(range(3, 7)).isContiguous());
        assertEquals(java.util.List.of(5L), scanOf(3, 4, 6, 7).gaps());
        assertEquals(java.util.List.of(7L), scanOf(6, 7, 7, 8).duplicates());
        assertTrue(scanOf().isEmpty());
        assertEquals(7, scanOf(range(3, 7)).max().getAsLong());
    }
}
