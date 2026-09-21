package com.demo.amps.connectors.codec;

import com.demo.amps.connectors.decode.RecordDecoder;
import com.demo.amps.connectors.encode.PayloadEncoder;

/**
 * The bridge between one typed payload and the pipeline's field map, contributed by the
 * application as a bean -- one per set {@link PayloadType}.
 *
 * <p>The decoder accepts the typed object itself or its serialized {@code byte[]} and returns
 * a {@link FieldView} over a builder, never an eager copy: that is what keeps a large message
 * cheap through a pipeline that reads two fields of it. The encoder accepts either a view of
 * its own type -- and builds the wire form straight from {@link FieldView#target()} -- or a
 * plain map, which it rebuilds the object from first; the plain map is what a {@code keep} or
 * a code transform that returns a fresh map produces, and it is the one path that
 * materialises anything. What the encoder returns is the wire form the codec is configured
 * for: {@code byte[]} for a binary AMPS topic, {@code String} for a JSON one; that is the
 * codec bean's own setting, not the framework's.
 *
 * <p>A codec that only reads, or only writes, answers {@code null} for the other side, and the
 * registry reports it as unable to encode or decode that type.
 */
public interface PayloadCodec {

    /** The type this codec reads and writes; never {@link PayloadType#UNSET}. */
    PayloadType type();

    /** From the object or its bytes to a {@link FieldView}; {@code null} if the codec only writes. */
    RecordDecoder decoder();

    /** From a view or a plain map to the wire form; {@code null} if the codec only reads. */
    PayloadEncoder encoder();
}
