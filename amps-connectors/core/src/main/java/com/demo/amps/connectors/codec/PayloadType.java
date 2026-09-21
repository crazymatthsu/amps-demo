package com.demo.amps.connectors.codec;

/**
 * What a payload <em>is</em>, as a pair of integers: the serialization family and the concrete
 * type within it.
 *
 * <p>The pair is the key the framework picks a codec by. The factory id names the family --
 * a Hazelcast {@code DataSerializableFactory}, a Thrift {@code TBase} generation, a protobuf
 * descriptor set -- and the class id the type within it, which is exactly how Hazelcast's
 * {@code IdentifiedDataSerializable} spells identity and close enough to how everything else
 * does. Two ints rather than a class name so that a source can carry the identity without
 * loading the type, and so that a codec registered by a bean and a payload tagged by a driver
 * agree on a value rather than on a classloader.
 *
 * <p>{@link #UNSET}, {@code 0/0}, is not a codec: it means "a text payload whose format the
 * connector decides" -- {@code format} on the way in and {@code amps.message-type} on the way
 * out -- which is every connector that existed before payloads were typed. The four text
 * formats are that default path, not codecs, and a registry is only ever consulted for a set
 * pair. A half-set pair ({@code 0/n}, {@code n/0}) names nothing and is refused, as is a
 * negative id.
 *
 * @param factoryId the serialization family; {@code 0} only in {@link #UNSET}
 * @param classId the concrete type within the family; {@code 0} only in {@link #UNSET}
 */
public record PayloadType(int factoryId, int classId) {

    /** Text, decoded by the connector's {@code format} and encoded by its {@code message-type}. */
    public static final PayloadType UNSET = new PayloadType(0, 0);

    /**
     * Canonical constructor: both ids non-negative, and either both zero or neither.
     *
     * @throws IllegalArgumentException if an id is negative or exactly one of them is zero
     */
    public PayloadType {
        if (factoryId < 0 || classId < 0) {
            throw new IllegalArgumentException("payload type " + factoryId + "/" + classId
                    + ": ids are non-negative");
        }
        if ((factoryId == 0) != (classId == 0)) {
            throw new IllegalArgumentException("payload type " + factoryId + "/" + classId
                    + ": 0/0 is the unset type, and a half-set pair names nothing");
        }
    }

    /**
     * The type with these ids.
     *
     * @param factoryId the serialization family
     * @param classId the concrete type within it
     * @return {@link #UNSET} for {@code 0/0}, else the pair
     * @throws IllegalArgumentException if an id is negative or exactly one of them is zero
     */
    public static PayloadType of(int factoryId, int classId) {
        return factoryId == 0 && classId == 0 ? UNSET : new PayloadType(factoryId, classId);
    }

    /** Whether this names a codec, as opposed to the text default. */
    public boolean isSet() {
        return !equals(UNSET);
    }

    /** {@code factoryId/classId}, e.g. {@code 100/1}; {@code 0/0} for {@link #UNSET}. */
    @Override
    public String toString() {
        return factoryId + "/" + classId;
    }
}
