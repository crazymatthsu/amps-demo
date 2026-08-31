package com.demo.amps.hazelcast;

import com.demo.amps.cache.JsonValues;
import com.google.gson.JsonElement;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * The default codec: Gson, sharing cache-persistent-store's configured
 * instance so both modules put byte-compatible JSON on the wire -- integral
 * numbers hydrate as {@code Long}, never {@code 42.0}.
 *
 * <p>{@link #untyped()} covers the JSON-native value shapes (String, Long,
 * Double, Boolean, List, Map); a typed instance maps a POJO class. Either
 * way the values must be Java-serializable too, because Hazelcast moves them
 * between members -- Gson's decoded maps are.
 *
 * @param <V> the value type
 */
public final class GsonValueCodec<V> implements ValueCodec<V> {

    private final Type valueType;

    public GsonValueCodec(Type valueType) {
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    /** Untyped JSON values -- the default the factory hands every map. */
    public static GsonValueCodec<Object> untyped() {
        return new GsonValueCodec<>(Object.class);
    }

    @Override
    public JsonElement encode(V value) {
        return JsonValues.GSON.toJsonTree(value);
    }

    @Override
    public V decode(JsonElement json) {
        return JsonValues.GSON.fromJson(json, valueType);
    }
}
