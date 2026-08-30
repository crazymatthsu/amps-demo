package com.demo.amps.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

/**
 * The one Gson instance the cache speaks JSON with, configured so a value
 * survives the round trip through AMPS structurally unchanged:
 *
 * <ul>
 *   <li>{@code LONG_OR_DOUBLE} -- untyped JSON numbers parse as {@code Long}
 *       when integral and {@code Double} otherwise. Gson's default maps every
 *       number to {@code Double}, so a cache that stored {@code 42} would
 *       hydrate {@code 42.0} after a restart: same JSON, different object,
 *       {@code equals()} false. That would make "recovered cache equals the
 *       original" -- the property this whole module exists to provide --
 *       silently untrue for any integer value.</li>
 *   <li>{@code disableHtmlEscaping} -- Gson otherwise writes {@code <} as
 *       {@code \u003c}. AMPS is indifferent, but the SOW files and admin
 *       console are things people read, and there is no HTML context here to
 *       protect.</li>
 * </ul>
 *
 * <p>Nulls need no configuration: the cache rejects {@code null} values at the
 * API (like {@link java.util.concurrent.ConcurrentHashMap}), so none reach
 * serialization.
 *
 * <p>Public because hazelcast-persistent-store must speak byte-compatible
 * JSON: two modules disagreeing on number typing would break the "recovered
 * equals original" property between them.
 */
public final class JsonValues {

    public static final Gson GSON = new GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .disableHtmlEscaping()
            .create();

    private JsonValues() {
    }
}
