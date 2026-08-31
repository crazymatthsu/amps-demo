package com.demo.amps.hazelcast;

import com.google.gson.JsonElement;

/**
 * How an IMap value becomes the {@code /value} member of a tier-topic record
 * and comes back.
 *
 * <p>Pluggable because Hazelcast maps hold arbitrary objects while the store
 * speaks JSON: the default ({@link GsonValueCodec}) covers untyped
 * JSON-friendly values and Gson-mappable POJOs, and a map with special needs
 * supplies its own codec through
 * {@link AmpsMapStoreFactory#codecFor(String, java.util.Properties)}.
 *
 * <p>The contract that matters: {@code decode(encode(v)).equals(v)}. Every
 * recovery guarantee in this module reduces to that equality.
 *
 * @param <V> the value type
 */
public interface ValueCodec<V> {

    JsonElement encode(V value);

    V decode(JsonElement json);
}
