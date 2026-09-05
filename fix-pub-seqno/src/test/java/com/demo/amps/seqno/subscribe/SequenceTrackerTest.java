package com.demo.amps.seqno.subscribe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.demo.amps.seqno.subscribe.SequenceTracker.Verdict;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SequenceTrackerTest {

    @Test
    void firstMessageFromASenderIsTheStartingPoint() {
        SequenceTracker tracker = new SequenceTracker();
        assertEquals(Verdict.FIRST, tracker.classify("PUB-A", 5));
    }

    @Test
    void classifiesInSequenceDuplicateAndGap() {
        SequenceTracker tracker = new SequenceTracker();
        tracker.advance("PUB-A", 5);

        assertEquals(Verdict.IN_SEQUENCE, tracker.classify("PUB-A", 6));
        assertEquals(Verdict.DUPLICATE, tracker.classify("PUB-A", 5), "at the mark is a duplicate");
        assertEquals(Verdict.DUPLICATE, tracker.classify("PUB-A", 3), "below the mark is a duplicate");
        assertEquals(Verdict.GAP, tracker.classify("PUB-A", 8), "more than one past the mark is a gap");
    }

    @Test
    void marksAreIndependentPerSender() {
        SequenceTracker tracker = new SequenceTracker();
        tracker.advance("PUB-A", 10);
        assertEquals(Verdict.FIRST, tracker.classify("PUB-B", 1),
                "another sender's history says nothing about this one");
        assertEquals(Verdict.IN_SEQUENCE, tracker.classify("PUB-A", 11));
    }

    @Test
    void theMarkOnlyMovesForward() {
        SequenceTracker tracker = new SequenceTracker();
        tracker.advance("PUB-A", 10);
        tracker.advance("PUB-A", 4);   // a duplicate that was inspected, not processed
        assertEquals(10, tracker.mark("PUB-A"));
    }

    @Test
    void survivesReloadFromDisk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("hwm.properties");
        SequenceTracker tracker = new SequenceTracker();
        tracker.advance("PUB-A", 12);
        tracker.advance("PUB-B", 3);
        tracker.save(file);

        SequenceTracker reloaded = SequenceTracker.load(file);
        assertEquals(12, reloaded.mark("PUB-A"));
        assertEquals(3, reloaded.mark("PUB-B"));
        // A message redelivered after a restart is still recognised as a duplicate.
        assertEquals(Verdict.DUPLICATE, reloaded.classify("PUB-A", 12));
    }

    @Test
    void loadOfAMissingFileIsEmpty(@TempDir Path dir) throws IOException {
        SequenceTracker tracker = SequenceTracker.load(dir.resolve("absent.properties"));
        assertEquals(0, tracker.mark("PUB-A"));
        assertEquals(Verdict.FIRST, tracker.classify("PUB-A", 1));
    }
}
