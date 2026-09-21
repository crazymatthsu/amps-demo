package com.demo.amps.connectors.encode;

import java.util.Map;

/**
 * Writes the field map back out as the payload AMPS receives.
 *
 * <p>The mirror of {@link com.demo.amps.connectors.decode.RecordDecoder}, and the reason the
 * decoders keep types and structure: an encoder writes what it is handed, so a number that
 * arrived as a number leaves as one. Which encoder runs is decided by the connector's
 * {@code amps.message-type} -- or, for a set {@code amps.payload-type}, by the codec
 * registered for it -- and not by the source format: reading FIX and publishing {@code json}
 * is exactly the translation this framework exists to do.
 *
 * <p>The three text encoders return a {@code String}; a codec's encoder returns whatever wire
 * form its topic takes, {@code byte[]} for a binary one. A codec's encoder also accepts the
 * {@link com.demo.amps.connectors.codec.FieldView} its own decoder produced and writes the
 * object behind it directly, so a typed record is never rebuilt from a map unless a step
 * actually produced a plain map.
 *
 * <p>The encoder is skipped entirely when the pipeline passes the original payload through
 * ({@code passthrough}), which is the cheapest path and the one a format-preserving connector
 * takes by default.
 */
@FunctionalInterface
public interface PayloadEncoder {

    /**
     * @param fields the fields to publish, in the order they should appear
     * @return the payload to send: a {@code String} or a {@code byte[]}
     * @throws IllegalArgumentException if a field cannot be written in this format -- the
     *     pipeline counts that as a rejection
     */
    Object encode(Map<String, Object> fields);
}
