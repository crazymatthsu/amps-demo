package com.demo.amps.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SequenceLedgerTest {

    @Test
    void completeInOrderRunHasNoGapsDuplicatesOrDisorder() {
        SequenceLedger ledger = new SequenceLedger();
        for (long seq = 1; seq <= 100; seq++) {
            assertEquals(SequenceLedger.Outcome.NEW, ledger.record(seq));
        }
        assertTrue(ledger.hasAll(100));
        assertFalse(ledger.hasAll(101));
        assertEquals(List.of(), ledger.gaps(100));
        assertEquals(100, ledger.unique());
        assertEquals(0, ledger.duplicates());
        assertEquals(0, ledger.outOfOrder());
        assertEquals(100, ledger.highest());
        assertEquals("received 100 of 100 (highest 100), 0 duplicates, 0 out of order, no gaps", ledger.report(100));
    }

    @Test
    void gapsAreReportedAsInclusiveRanges() {
        SequenceLedger ledger = new SequenceLedger();
        for (long seq : new long[] {1, 2, 5, 6, 7, 10}) {
            ledger.record(seq);
        }
        assertEquals(List.of(new SequenceLedger.Gap(3, 4), new SequenceLedger.Gap(8, 9), new SequenceLedger.Gap(11, 12)),
                ledger.gaps(12));
        assertEquals(List.of(new SequenceLedger.Gap(3, 4)), ledger.gaps(7));
        assertFalse(ledger.hasAll(12));
        assertTrue(ledger.report(12).contains("6 missing in 3 gap(s): [3-4, 8-9, 11-12]"));
    }

    @Test
    void redeliveryCountsAsDuplicateNotAsNew() {
        SequenceLedger ledger = new SequenceLedger();
        ledger.record(1);
        ledger.record(2);
        assertEquals(SequenceLedger.Outcome.DUPLICATE, ledger.record(2));
        assertEquals(SequenceLedger.Outcome.DUPLICATE, ledger.record(1));
        assertEquals(2, ledger.unique());
        assertEquals(2, ledger.duplicates());
        assertTrue(ledger.hasAll(2));
    }

    @Test
    void lateFirstSightingCountsAsOutOfOrder() {
        SequenceLedger ledger = new SequenceLedger();
        ledger.record(1);
        ledger.record(3);
        ledger.record(2);
        assertEquals(1, ledger.outOfOrder());
        assertTrue(ledger.hasAll(3));
        // A duplicate below the highest is a duplicate, not disorder.
        ledger.record(1);
        assertEquals(1, ledger.outOfOrder());
        assertEquals(1, ledger.duplicates());
    }

    @Test
    void emptyLedgerHasEverythingUpToZeroAndNothingBeyond() {
        SequenceLedger ledger = new SequenceLedger();
        assertTrue(ledger.hasAll(0));
        assertFalse(ledger.hasAll(1));
        assertEquals(List.of(new SequenceLedger.Gap(1, 5)), ledger.gaps(5));
        assertEquals(0, ledger.highest());
    }

    @Test
    void sequenceNumbersStartAtOne() {
        SequenceLedger ledger = new SequenceLedger();
        assertThrows(IllegalArgumentException.class, () -> ledger.record(0));
        assertThrows(IllegalArgumentException.class, () -> ledger.record(-7));
        assertThrows(IllegalArgumentException.class, () -> ledger.record(1L + Integer.MAX_VALUE));
    }
}
