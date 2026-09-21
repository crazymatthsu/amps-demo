package com.demo.amps.connectors.codec;

import java.nio.charset.StandardCharsets;

/**
 * The two representations a wire payload has, and how to get from one to the other.
 *
 * <p>A record's {@code data} is an {@code Object}: a {@code String} from a socket or a JSON
 * feed, a {@code byte[]} from a Kafka topic of serialized messages, or the typed object
 * itself. The text decoders and the AMPS client want one or the other, and these two methods
 * are the whole of the conversion, so that "what does a {@code byte[]} mean as text" is
 * answered once (UTF-8) rather than per caller.
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * A payload as text.
     *
     * @param payload a {@code String}, a {@code byte[]}, {@code null} or any object
     * @return the string as it is; the bytes decoded as UTF-8; {@code ""} for {@code null};
     *     {@link String#valueOf(Object)} for anything else
     */
    public static String text(Object payload) {
        if (payload == null) {
            return "";
        }
        if (payload instanceof String text) {
            return text;
        }
        if (payload instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(payload);
    }

    /**
     * A payload as bytes.
     *
     * @param payload a {@code String}, a {@code byte[]}, {@code null} or any object
     * @return the bytes as they are; the string encoded as UTF-8; an empty array for
     *     {@code null}; the UTF-8 of {@link String#valueOf(Object)} for anything else
     */
    public static byte[] bytes(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        return text(payload).getBytes(StandardCharsets.UTF_8);
    }
}
