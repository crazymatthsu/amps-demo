package com.demo.amps.connectors.decode;

import java.util.Map;

/**
 * Turns a source's payload string into the field map the rest of the pipeline works on.
 *
 * <p>Every format decodes to the same shape -- a {@link java.util.LinkedHashMap} whose
 * iteration order is wire order -- so the filter, the transforms, the key extractor and the
 * encoders are written once and know nothing about FIX, JSON or NVFIX. What a <em>value</em>
 * is does depend on the format: JSON carries types ({@code Long}, {@code java.math.BigDecimal},
 * {@code Boolean}, nested {@code Map}, {@code List}, {@code null}), while the delimited formats
 * carry nothing but {@code String}.
 *
 * <p>A key present in the map means the payload carried that field, which is how a filter's
 * {@code present: false} can mean anything at all: absent key versus a value that is null.
 */
@FunctionalInterface
public interface RecordDecoder {

    /**
     * @param payload the raw payload as the source delivered it
     * @return the fields it carried, in wire order; empty when it carried none
     * @throws IllegalArgumentException if the payload is malformed for this format -- the
     *     pipeline counts that as a rejection
     */
    Map<String, Object> decode(String payload);
}
