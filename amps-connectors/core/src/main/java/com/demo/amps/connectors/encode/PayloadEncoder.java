package com.demo.amps.connectors.encode;

import java.util.Map;

/**
 * Writes the field map back out as the payload AMPS receives.
 *
 * <p>The mirror of {@link com.demo.amps.connectors.decode.RecordDecoder}, and the reason the
 * decoders keep types and structure: an encoder writes what it is handed, so a number that
 * arrived as a number leaves as one. Which encoder runs is decided by the connector's
 * {@code amps.message-type}, not by the source format -- reading FIX and publishing
 * {@code json} is exactly the translation this framework exists to do.
 *
 * <p>The encoder is skipped entirely when the pipeline passes the original payload through
 * ({@code passthrough}), which is the cheapest path and the one a format-preserving connector
 * takes by default.
 */
@FunctionalInterface
public interface PayloadEncoder {

    /**
     * @param fields the fields to publish, in the order they should appear
     * @return the payload to send
     * @throws IllegalArgumentException if a field cannot be written in this format -- the
     *     pipeline counts that as a rejection
     */
    String encode(Map<String, Object> fields);
}
