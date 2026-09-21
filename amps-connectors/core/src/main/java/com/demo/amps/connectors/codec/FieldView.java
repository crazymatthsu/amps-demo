package com.demo.amps.connectors.codec;

import java.util.Map;

/**
 * The field map a typed codec's decoder hands the pipeline: a {@link Map} <em>over</em> the
 * object rather than a copy of it.
 *
 * <p>The pipeline's contract is {@code Map<String, Object>} -- the filter, the transforms, the
 * key extractor and SpEL's {@code #f[...]} all read and write through it -- and this is how a
 * protobuf message or a Thrift struct honours that contract without being flattened into one.
 * A view reads a field only when asked ({@code get(name)} is a descriptor lookup and a getter,
 * not a walk over every field), writes go straight to the builder ({@code put} is
 * {@code setField}, {@code remove} is {@code clearField}), and the object behind it is never
 * materialised into a plain map unless a step builds a genuinely new one ({@code keep}).
 *
 * <p>The Map contract a view keeps:
 *
 * <ul>
 *   <li>{@code keySet()} is the set of fields that are <em>set</em> -- protobuf's
 *       {@code getAllFields} semantics -- so {@code containsKey} means "present", which is
 *       what a filter's {@code present} rule and a proto3 scalar at its default value both
 *       mean by it</li>
 *   <li>{@code get(name)} reads on demand; a nested message is a nested {@code FieldView}, a
 *       repeated field a {@link java.util.List}, an enum its name</li>
 *   <li>{@code put(name, value)} sets the field, coercing a {@code String} or a
 *       {@link Number} the way the codec's type needs; {@code remove(name)} clears it</li>
 *   <li>occurrence keys ({@code 55#2}) are a FIX idea and absent: a typed repeated field is a
 *       list, not a numbered set of keys</li>
 * </ul>
 *
 * <p>{@link #copy()} is the copy-on-write half of the design. The pipeline gives every step
 * its own copy of the fields, and it takes that copy through
 * {@link com.demo.amps.connectors.decode.Fields#copy(Map)}, which for a view clones the
 * builder ({@code Message.Builder.clone()}, {@code TBase.deepCopy()}) and returns a view over
 * the clone. The original object -- which is also the record's {@code data()} -- is untouched
 * by anything downstream. A view shares its builder with the record only until the first
 * copy, so a code transform must not keep a view past its {@code apply}.
 */
public interface FieldView extends Map<String, Object> {

    /** The payload type this view is a view of. */
    PayloadType type();

    /**
     * The object the view reads and writes: a protobuf {@code Message.Builder}, a Thrift
     * {@code TBase}, whatever the codec's type is. The typed escape hatch for a code
     * transform that would rather use the generated accessors than the map.
     */
    Object target();

    /**
     * A view over a copy of the target. The original is untouched by anything done to the
     * copy, which is what lets a step edit "its own" fields without editing the record's.
     */
    FieldView copy();
}
