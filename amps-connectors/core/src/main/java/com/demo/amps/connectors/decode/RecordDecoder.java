package com.demo.amps.connectors.decode;

import java.util.Map;

/**
 * Turns a source's payload into the field map the rest of the pipeline works on.
 *
 * <p>Every format decodes to the same shape -- a {@code Map<String, Object>} whose iteration
 * order is wire order -- so the filter, the transforms, the key extractor and the encoders
 * are written once and know nothing about FIX, JSON, NVFIX or a codec's generated class. The
 * text decoders produce a {@link java.util.LinkedHashMap}; a typed codec's decoder produces a
 * {@link com.demo.amps.connectors.codec.FieldView} over the object's builder, which reads on
 * demand and is a Map all the same. What a <em>value</em> is does depend on the format: JSON
 * carries types ({@code Long}, {@code java.math.BigDecimal}, {@code Boolean}, nested
 * {@code Map}, {@code List}, {@code null}), the delimited formats carry nothing but
 * {@code String}, and a codec carries whatever its type declares.
 *
 * <p>A key present in the map means the payload carried that field, which is how a filter's
 * {@code present: false} can mean anything at all: absent key versus a value that is null.
 */
@FunctionalInterface
public interface RecordDecoder {

    /**
     * @param payload the raw payload as the source delivered it: a {@code String} or a
     *     {@code byte[]} for a text decoder (see
     *     {@link com.demo.amps.connectors.codec.Payloads#text}), the typed object or its
     *     bytes for a codec's
     * @return the fields it carried, in wire order; empty when it carried none
     * @throws IllegalArgumentException if the payload is malformed for this format -- the
     *     pipeline counts that as a rejection
     */
    Map<String, Object> decode(Object payload);
}
