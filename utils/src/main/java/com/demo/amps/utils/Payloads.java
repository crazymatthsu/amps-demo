package com.demo.amps.utils;

import com.crankuptheamps.client.Message;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The on-disk format shared by every tool here, in two flavours.
 *
 * <p>The point of having exactly one format module is round-tripping: whatever
 * {@code ampsToFileSOW} writes, {@code fileToAMPS} must be able to publish back.
 *
 * <dl>
 *   <dt>{@link #LINES}</dt>
 *   <dd>One payload per line, exactly as it went over the wire. What you want
 *       for eyeballing, grepping and re-publishing. JSON, FIX and NVFIX
 *       payloads never contain a newline, so this is lossless for them -- but
 *       it cannot be lossless in general, and the dump tools count any payload
 *       that does contain one so the operator is told rather than left with a
 *       file that silently reads back as more messages than it holds.</dd>
 *
 *   <dt>{@link #JSONL}</dt>
 *   <dd>One JSON object per line: {@code {"topic":…,"bookmark":…,"sowKey":…,
 *       "data":…}}. The payload is a JSON string, so it survives any bytes,
 *       and the envelope keeps the metadata that matters when dumping a
 *       transaction log. Lossless; use it when the data is not known to be
 *       newline-free, or when you want the bookmarks.</dd>
 * </dl>
 */
public final class Payloads {

    public static final String LINES = "lines";
    public static final String JSONL = "jsonl";

    private Payloads() {
    }

    public static String requireKnownFormat(String format) {
        if (!LINES.equals(format) && !JSONL.equals(format)) {
            throw new Args.UsageException(
                    "--format must be '" + LINES + "' or '" + JSONL + "', got '" + format + "'");
        }
        return format;
    }

    /** Writes messages out in the chosen format and counts what it saw. */
    public static final class Writer implements AutoCloseable {

        private final BufferedWriter out;
        private final String format;
        private long written;
        private long withEmbeddedNewline;

        public Writer(Path file, String format) throws IOException {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            this.format = requireKnownFormat(format);
        }

        public void write(Message message) throws IOException {
            String data = message.isDataNull() ? "" : message.getData();
            if (JSONL.equals(format)) {
                JsonObject envelope = new JsonObject();
                envelope.addProperty("topic", safe(message::getTopic));
                String bookmark = safe(message::getBookmark);
                if (bookmark != null) {
                    envelope.addProperty("bookmark", bookmark);
                }
                if (!message.isSowKeyNull()) {
                    envelope.addProperty("sowKey", safe(message::getSowKey));
                }
                envelope.addProperty("data", data);
                out.write(envelope.toString());
            } else {
                if (data.indexOf('\n') >= 0 || data.indexOf('\r') >= 0) {
                    withEmbeddedNewline++;
                }
                out.write(data);
            }
            out.newLine();
            written++;
        }

        public long written() {
            return written;
        }

        /** Payloads that break the one-per-line assumption. Zero for JSON/FIX/NVFIX. */
        public long withEmbeddedNewline() {
            return withEmbeddedNewline;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /** Turns one line of a dump file back into a payload to publish. */
    public static String toPayload(String line, String format) {
        if (JSONL.equals(format)) {
            JsonObject envelope = JsonParser.parseString(line).getAsJsonObject();
            if (!envelope.has("data")) {
                throw new IllegalArgumentException("jsonl record has no 'data' member: " + line);
            }
            return envelope.get("data").getAsString();
        }
        return line;
    }

    /** The topic recorded in a jsonl envelope, or null in either other case. */
    public static String topicOf(String line, String format) {
        if (!JSONL.equals(format)) {
            return null;
        }
        JsonObject envelope = JsonParser.parseString(line).getAsJsonObject();
        return envelope.has("topic") ? envelope.get("topic").getAsString() : null;
    }

    /**
     * Some accessors throw rather than return null when a field is absent,
     * and which ones varies by message kind; a dump should not die over a
     * missing bookmark.
     */
    private static String safe(ThrowingSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    private interface ThrowingSupplier {
        String get() throws Exception;
    }
}
