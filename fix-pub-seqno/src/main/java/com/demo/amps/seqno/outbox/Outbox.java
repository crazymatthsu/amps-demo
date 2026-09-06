package com.demo.amps.seqno.outbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The publisher's durable record of everything it decided to send, in
 * sender-sequence order: the analogue of a FIX engine's outgoing message
 * store.
 *
 * <p>An append-only file, one line per message: the sequence number (tag
 * 8888), a tab, and the payload <em>exactly as it went on the wire</em>, SOH
 * separators included -- so a republish is byte-identical to the original.
 * ({@code cat -v} shows the separators as {@code ^A}.)
 *
 * <p>Two rules make it the system of record:
 *
 * <ul>
 *   <li><b>Sequence numbers are contiguous from 1.</b> {@link #append} refuses
 *       anything but {@code lastSequence() + 1}, and {@link #open} refuses a
 *       file that is not an unbroken series. A number can therefore never be
 *       skipped or reused, whatever the process did before it died.</li>
 *   <li><b>The append is forced to disk before it returns.</b> The publisher
 *       sends only after {@code append} has returned, so a message that is
 *       ever on the wire is always in the outbox, and a crash between the
 *       two leaves an outbox entry with nothing sent -- the normal gap the
 *       recovery closes.</li>
 * </ul>
 *
 * <p>Every entry is kept in memory as well. Fine for a demo; a real engine
 * would index the file and keep a window.
 */
public final class Outbox {

    /** One line of the outbox. */
    public record Entry(long sequence, String payload) {
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    private Outbox(Path file) {
        this.file = file;
    }

    /** Opens (creating if needed) the outbox at {@code file}, validating what is there. */
    public static Outbox open(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Outbox outbox = new Outbox(file);
        if (Files.exists(file)) {
            long expected = 1;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    throw new IOException("outbox " + file + " is corrupt: no tab on line " + expected);
                }
                long sequence = Long.parseLong(line.substring(0, tab));
                if (sequence != expected) {
                    throw new IOException("outbox " + file + " is corrupt: expected sequence "
                            + expected + " but found " + sequence);
                }
                outbox.entries.add(new Entry(sequence, line.substring(tab + 1)));
                expected++;
            }
        }
        return outbox;
    }

    public Path file() {
        return file;
    }

    /** The highest sequence number recorded, or 0 when the outbox is empty. */
    public synchronized long lastSequence() {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).sequence();
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * Records {@code payload} under {@code sequence}, which must be exactly the
     * next number. Returns once the bytes are on disk.
     */
    public synchronized void append(long sequence, String payload) {
        long expected = lastSequence() + 1;
        if (sequence != expected) {
            throw new IllegalArgumentException("outbox sequence must be contiguous: expected "
                    + expected + ", got " + sequence);
        }
        if (payload.indexOf('\n') >= 0 || payload.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("payload may not contain a newline or a tab");
        }
        byte[] line = (sequence + "\t" + payload + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to outbox " + file, e);
        }
        entries.add(new Entry(sequence, payload));
    }

    public synchronized Optional<Entry> get(long sequence) {
        if (sequence < 1 || sequence > entries.size()) {
            return Optional.empty();
        }
        return Optional.of(entries.get((int) (sequence - 1)));
    }

    /** Every entry with a sequence number strictly greater than {@code sequence}, in order. */
    public synchronized List<Entry> after(long sequence) {
        if (sequence >= entries.size()) {
            return List.of();
        }
        int from = (int) Math.max(0, sequence);
        return Collections.unmodifiableList(new ArrayList<>(entries.subList(from, entries.size())));
    }

    /** Every entry, in order. */
    public synchronized List<Entry> all() {
        return after(0);
    }
}
