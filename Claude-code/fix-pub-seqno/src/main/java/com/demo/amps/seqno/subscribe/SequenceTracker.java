package com.demo.amps.seqno.subscribe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A subscriber's high-water mark of tag 8888 per sender, and the judgement it
 * makes about each arriving message.
 *
 * <p>Bookmarks answer "where was I?"; they cannot answer "did I get everything
 * in between?", because that is a statement about the <em>publisher's</em>
 * sequence and only a per-sender counter can check it. This tracker is that
 * counter. It is deliberately free of any AMPS type -- {@link #classify} is a
 * pure comparison -- so the decision table is unit-tested directly.
 *
 * <p>The mark is advanced <em>after</em> a message is processed and before its
 * bookmark is discarded, which is what makes redelivery safe: a redelivered
 * message (the subscriber crashed after processing, before discarding) arrives
 * again with a sequence at or below the mark and is classified
 * {@link Verdict#DUPLICATE}.
 */
public final class SequenceTracker {

    /** What an arriving 8888 is, relative to the last one processed for its sender. */
    public enum Verdict {
        /** First message seen from this sender: accept as the starting point. */
        FIRST,
        /** Exactly one past the mark: the expected next message. */
        IN_SEQUENCE,
        /** At or below the mark: a resend or a redelivery. Skip it. */
        DUPLICATE,
        /** More than one past the mark: something is missing. Alarm. */
        GAP
    }

    private final Map<String, Long> highWaterMark = new LinkedHashMap<>();

    /** Judges {@code sequence} from {@code sender} without changing any state. */
    public Verdict classify(String sender, long sequence) {
        Long mark = highWaterMark.get(sender);
        if (mark == null) {
            return Verdict.FIRST;
        }
        if (sequence <= mark) {
            return Verdict.DUPLICATE;
        }
        if (sequence == mark + 1) {
            return Verdict.IN_SEQUENCE;
        }
        return Verdict.GAP;
    }

    /**
     * Records that {@code sequence} from {@code sender} has been processed. The
     * mark only ever moves forward, so a call with an older sequence (a
     * duplicate that was inspected but not processed) leaves it unchanged.
     */
    public void advance(String sender, long sequence) {
        highWaterMark.merge(sender, sequence, Math::max);
    }

    /** The last processed 8888 for {@code sender}, or 0 if none. */
    public long mark(String sender) {
        return highWaterMark.getOrDefault(sender, 0L);
    }

    public Map<String, Long> marks() {
        return Map.copyOf(highWaterMark);
    }

    /** Loads marks from a properties file, or an empty tracker if it is absent. */
    public static SequenceTracker load(Path file) throws IOException {
        SequenceTracker tracker = new SequenceTracker();
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
            for (String sender : properties.stringPropertyNames()) {
                tracker.highWaterMark.put(sender, Long.parseLong(properties.getProperty(sender)));
            }
        }
        return tracker;
    }

    /** Writes the marks to {@code file}, creating parent directories. */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Properties properties = new Properties();
        highWaterMark.forEach((sender, mark) -> properties.setProperty(sender, Long.toString(mark)));
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "fix-pub-seqno subscriber high-water marks (tag 8888 per sender)");
        }
    }
}
