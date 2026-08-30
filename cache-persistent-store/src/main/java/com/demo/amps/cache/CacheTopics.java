package com.demo.amps.cache;

/**
 * The SOW topics the `cache` server flow declares
 * ({@code server/config/flows/cache/amps-config.xml}). Topic names and their
 * {@code <Key>} fields are a contract between this module and that file --
 * change one side and the other must follow.
 */
public final class CacheTopics {

    /** Flat cache entries: {@code {"key", "value"}}, keyed on {@code /key}. */
    public static final String ENTRIES = "cache.entries";

    /**
     * Flattened map-of-maps entries: {@code {"outerKey", "innerKey", "value"}},
     * composite-keyed on {@code /outerKey} + {@code /innerKey}.
     */
    public static final String NESTED_ENTRIES = "cache.nested.entries";

    private CacheTopics() {
    }
}
