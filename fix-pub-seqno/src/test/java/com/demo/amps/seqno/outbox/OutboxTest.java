package com.demo.amps.seqno.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxTest {

    @Test
    void assignsContiguousSequencesAndPersistsThem(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("outbox.log");
        Outbox outbox = Outbox.open(file);
        assertEquals(0, outbox.lastSequence());

        outbox.append(1, "35=D 8888=1");
        outbox.append(2, "35=D 8888=2");
        assertEquals(2, outbox.lastSequence());

        // Reopening recovers exactly what was written, in order.
        Outbox reopened = Outbox.open(file);
        assertEquals(2, reopened.lastSequence());
        assertEquals("35=D 8888=2", reopened.get(2).orElseThrow().payload());
    }

    @Test
    void rejectsNonContiguousAppend(@TempDir Path dir) throws IOException {
        Outbox outbox = Outbox.open(dir.resolve("outbox.log"));
        outbox.append(1, "a");
        assertThrows(IllegalArgumentException.class, () -> outbox.append(3, "c"),
                "a skipped sequence number must be refused");
        assertThrows(IllegalArgumentException.class, () -> outbox.append(1, "again"),
                "a reused sequence number must be refused");
    }

    @Test
    void rejectsPayloadWithControlCharacters(@TempDir Path dir) throws IOException {
        Outbox outbox = Outbox.open(dir.resolve("outbox.log"));
        // A payload may not contain the framing characters of the outbox file.
        assertThrows(IllegalArgumentException.class, () -> outbox.append(1, "has" + '\t' + "tab"));
        assertThrows(IllegalArgumentException.class, () -> outbox.append(1, "has" + '\n' + "newline"));
    }

    @Test
    void afterReturnsTheGapInOrder(@TempDir Path dir) throws IOException {
        Outbox outbox = Outbox.open(dir.resolve("outbox.log"));
        for (int i = 1; i <= 5; i++) {
            outbox.append(i, "m" + i);
        }
        List<Outbox.Entry> gap = outbox.after(2);
        assertEquals(List.of(3L, 4L, 5L), gap.stream().map(Outbox.Entry::sequence).toList());
        assertTrue(outbox.after(5).isEmpty(), "nothing after the last sequence");
    }

    @Test
    void refusesToOpenACorruptFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("outbox.log");
        // A file whose sequence numbers are not an unbroken series from 1.
        Files.writeString(file, "1" + '\t' + "m1" + '\n' + "3" + '\t' + "m3" + '\n');
        assertThrows(IOException.class, () -> Outbox.open(file),
                "a hole in the outbox is corruption, not a resumable state");
    }
}
