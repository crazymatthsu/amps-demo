package com.demo.amps.seqno.fix;

import com.crankuptheamps.client.FIXBuilder;
import com.crankuptheamps.client.FIXShredder;
import com.crankuptheamps.client.exception.CommandException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SequencedMap;

/**
 * An immutable FIX message as an ordered tag-to-value map.
 *
 * <p>Encoding and decoding go through the AMPS client's own {@code FIXBuilder}
 * and {@code FIXShredder}, the same choice the rest of this repository makes:
 * AMPS parses these payloads natively, so the bytes on the wire are the
 * server's input format and worth producing with the vendor's helper. The
 * class is deliberately small; it carries tags, it does not interpret them.
 */
public final class FixMessage {

    /** The standard FIX field separator. */
    public static final byte SOH = 0x01;

    private final SequencedMap<Integer, String> tags;

    private FixMessage(SequencedMap<Integer, String> tags) {
        this.tags = tags;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Starts a message of the given type; tag 35 is set first, as FIX orders it. */
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
        return printable(render());
    }

    /** Any raw payload with its separators rendered as {@code |}. */
    public static String printable(String payload) {
        return payload.replace((char) SOH, '|');
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

    /** A numeric tag, when present and well-formed. */
    public OptionalLong optionalLong(int tag) {
        String value = tags.get(tag);
        if (value == null || value.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** A numeric tag that must be present: the sequence number, typically. */
    public long longValue(int tag) {
        return optionalLong(tag).orElseThrow(() -> new IllegalArgumentException(
                "tag " + tag + " is absent or not numeric on " + printable()));
    }

    public int size() {
        return tags.size();
    }

    /** An unmodifiable view of every field, in wire order. */
    public SequencedMap<Integer, String> asMap() {
        return Collections.unmodifiableSequencedMap(tags);
    }

    /** A builder pre-loaded with this message's fields, for stamping extra tags. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.tags.putAll(tags);
        return builder;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FixMessage that && tags.equals(that.tags);
    }

    @Override
    public int hashCode() {
        return tags.hashCode();
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

        public FixMessage build() {
            return new FixMessage(new LinkedHashMap<>(tags));
        }
    }
}
