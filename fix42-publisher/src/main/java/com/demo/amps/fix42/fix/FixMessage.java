package com.demo.amps.fix42.fix;

import com.crankuptheamps.client.FIXBuilder;
import com.crankuptheamps.client.FIXShredder;
import com.crankuptheamps.client.exception.CommandException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * An immutable FIX 4.2 application message as an ordered tag-to-value map.
 *
 * <p>Encoding and decoding go through the AMPS client's own {@code FIXBuilder}
 * and {@code FIXShredder} rather than string splicing, the same choice
 * {@code common}'s {@code FixWire} makes: AMPS parses these payloads natively,
 * so the bytes on the wire are the server's input format and worth building
 * with the vendor's helper.
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

    private final SequencedMap<Integer, String> tags;

    private FixMessage(SequencedMap<Integer, String> tags) {
        this.tags = tags;
    }

    /** Starts building a message of the given type; {@code 35} is set first. */
    public static Builder ofType(String msgType) {
        return new Builder().set(FixTags.MSG_TYPE, msgType);
    }

    /** Parses a raw SOH-separated payload. */
    public static FixMessage parse(String payload) {
        SequencedMap<Integer, String> parsed = new LinkedHashMap<>();
        new FIXShredder(SOH).toMap(payload)
                .forEach((tag, value) -> parsed.put(tag, value.toString()));
        return new FixMessage(parsed);
    }

    /** The raw payload, SOH-separated, ready to publish. */
    public String render() {
        try {
            FIXBuilder builder = new FIXBuilder(512, SOH);
            for (Map.Entry<Integer, String> field : tags.entrySet()) {
                builder.append(field.getKey(), field.getValue());
            }
            return new String(builder.getBytes(), 0, builder.getSize(), StandardCharsets.UTF_8);
        } catch (CommandException e) {
            throw new IllegalStateException("cannot encode FIX message " + tags, e);
        }
    }

    /** The payload with separators rendered as {@code |}, for logs and assertions. */
    public String printable() {
        return render().replace((char) SOH, '|');
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

        public FixMessage build() {
            return new FixMessage(new LinkedHashMap<>(tags));
        }
    }
}
