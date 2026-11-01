package com.demo.amps.fix42.state.fix;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * An immutable FIX 4.2 application message as an ordered tag-to-value map.
 *
 * <p>This is the readable representation: the mock flow builds messages with
 * the {@link Builder}, tests assert on {@link #printable()}, and the planner's
 * {@code plan(FixMessage)} reports what it would send. The hot path never
 * builds one -- it works on a {@link FixFrame} over the raw bytes and writes
 * payloads with a {@link FixWriter} -- and this class encodes and decodes
 * through those same two types, so there is one parser and one encoder.
 * Rendering allocates only the String it returns; parsing allocates the map
 * and its values. AMPS parses these payloads natively, so the bytes on the
 * wire are the server's input format.
 *
 * <p>Only application-level tags are carried. BeginString (8), BodyLength (9),
 * CheckSum (10), MsgSeqNum (34) and the comp IDs belong to a FIX <i>session</i>
 * between two engines; AMPS is not a session endpoint, and durability comes
 * from its transaction log instead. Field order is preserved because a FIX
 * payload is ordered on the wire and stable ordering keeps fixtures readable
 * and diffs meaningful.
 */
public final class FixMessage {

    /** The standard FIX field separator. */
    public static final byte SOH = 0x01;

    /**
     * One writer per thread for {@link #render()} and {@link #printable()}.
     * Both return a fresh String, but neither needs a fresh buffer to build it.
     */
    private static final ThreadLocal<FixWriter> SCRATCH =
            ThreadLocal.withInitial(() -> new FixWriter(512));

    /** One frame per thread for {@link #parse}; the frame itself is never exposed. */
    private static final ThreadLocal<FixFrame> PARSER = ThreadLocal.withInitial(FixFrame::new);

    private final SequencedMap<Integer, String> tags;

    private FixMessage(SequencedMap<Integer, String> tags) {
        this.tags = tags;
    }

    /** Starts building a message of the given type; {@code 35} is set first. */
    public static Builder ofType(String msgType) {
        return new Builder().set(FixTags.MSG_TYPE, msgType);
    }

    /** Parses a raw SOH-separated payload; see {@link #parse(byte[], int, int)}. */
    public static FixMessage parse(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.ISO_8859_1);
        return parse(bytes, 0, bytes.length);
    }

    /**
     * Parses {@code length} bytes of a raw SOH-separated payload from
     * {@code offset}. Fields keep their wire order; a repeated tag keeps its
     * first position and its last value.
     *
     * @throws IllegalArgumentException on a malformed field, as {@link FixFrame#wrap}
     */
    public static FixMessage parse(byte[] payload, int offset, int length) {
        return of(PARSER.get().wrap(payload, offset, length));
    }

    /** The fields of {@code frame} as a message, in wire order. */
    public static FixMessage of(FixFrame frame) {
        int count = frame.count();
        SequencedMap<Integer, String> fields = LinkedHashMap.newLinkedHashMap(count);
        for (int i = 0; i < count; i++) {
            fields.put(frame.tagAt(i), frame.valueAt(i));
        }
        return new FixMessage(fields);
    }

    /** The raw payload, SOH-separated, ready to publish. */
    public String render() {
        return writeTo(scratch()).asString();
    }

    /** The payload with separators rendered as {@code |}, for logs and assertions. */
    public String printable() {
        return writeTo(scratch()).printable();
    }

    /**
     * Appends every field to {@code writer}, in wire order, and returns the
     * writer. The only allocation is the map iterator; the values are written
     * char by char into the writer's buffer.
     */
    public FixWriter writeTo(FixWriter writer) {
        for (Map.Entry<Integer, String> field : tags.entrySet()) {
            writer.field(field.getKey(), field.getValue());
        }
        return writer;
    }

    public String msgType() {
        return tags.getOrDefault(FixTags.MSG_TYPE, "");
    }

    public Optional<String> get(int tag) {
        return Optional.ofNullable(tags.get(tag));
    }

    /** The value of {@code tag}, or {@code ""} when absent. */
    public String value(int tag) {
        return tags.getOrDefault(tag, "");
    }

    public boolean has(int tag) {
        return tags.containsKey(tag);
    }

    /** The tags present, in wire order. */
    public Collection<Integer> tags() {
        return tags.keySet();
    }

    public int size() {
        return tags.size();
    }

    /** An unmodifiable view of every field, in wire order. */
    public SequencedMap<Integer, String> asMap() {
        return java.util.Collections.unmodifiableSequencedMap(tags);
    }

    /**
     * The subset of this message carrying only {@code wanted}, in the order
     * {@code wanted} lists them.
     *
     * <p>This is the whole of "publish only the changed fields": a delta
     * publish is just the original message with most of its tags left out, so
     * AMPS merges the few that remain into the stored record. Tags absent from
     * this message are silently skipped -- a selection rule names the fields a
     * message type <i>may</i> carry, and an optional field that is not set is
     * not an error.
     */
    public FixMessage select(Collection<Integer> wanted) {
        SequencedMap<Integer, String> subset = new LinkedHashMap<>();
        for (Integer tag : wanted) {
            String value = tags.get(tag);
            if (value != null) {
                subset.put(tag, value);
            }
        }
        return new FixMessage(subset);
    }

    @Override
    public String toString() {
        return printable();
    }

    private static FixWriter scratch() {
        FixWriter writer = SCRATCH.get();
        writer.clear();
        return writer;
    }

    /** Mutable builder; values are rendered as text exactly as FIX requires. */
    public static final class Builder {

        private final SequencedMap<Integer, String> tags = new LinkedHashMap<>();

        /** Sets a tag, or removes it when {@code value} is null or empty. */
        public Builder set(int tag, String value) {
            if (value == null || value.isEmpty()) {
                tags.remove(tag);
            } else {
                tags.put(tag, value);
            }
            return this;
        }

        public Builder set(int tag, long value) {
            return set(tag, Long.toString(value));
        }

        /**
         * Sets a price-like tag. FIX carries decimals as plain text, never in
         * scientific notation, and trailing zeros are noise -- so 50.2 and
         * 50.20 must not produce two different payloads for the same price.
         */
        public Builder setDecimal(int tag, double value) {
            return set(tag, Prices.plain(value));
        }

        /** Sets a tag only when the condition holds; keeps fixtures declarative. */
        public Builder setIf(boolean condition, int tag, String value) {
            return condition ? set(tag, value) : this;
        }

        /**
         * Sets every field of {@code message}, in its order. A tag already set
         * keeps its position and takes the new value, as {@link #set} does.
         */
        public Builder setAll(FixMessage message) {
            tags.putAll(message.tags);
            return this;
        }

        public FixMessage build() {
            return new FixMessage(new LinkedHashMap<>(tags));
        }
    }
}
