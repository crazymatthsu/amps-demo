package com.demo.amps.cache;

import java.util.Objects;

/**
 * Builders for the few AMPS content-filter expressions the stores need.
 *
 * <p>Keys travel two ways: inside JSON payloads (where Gson escapes anything)
 * and inside filter expressions like {@code /key = 'o'brien'} (where nothing
 * does). AMPS string literals take either single or double quotes, and the
 * expression language has no escape sequence for embedding the delimiter --
 * so the quote style is chosen per value, and a value containing BOTH quote
 * characters cannot be written as a filter literal at all. Such keys are
 * rejected when the entry is stored, rather than corrupting a filter later
 * when the entry is loaded or deleted.
 */
final class AmpsFilters {

    /** The conventional match-everything filter, as used by the truncate demo. */
    static final String MATCH_EVERYTHING = "1=1";

    private AmpsFilters() {
    }

    /** {@code /field = 'value'}, quoting the literal to suit the value. */
    static String equalTo(String field, String value) {
        return "/" + field + " = " + literal(value);
    }

    /**
     * Rejects a key that could never be named in a filter; returns it
     * otherwise. Stores call this before writing, so every stored key is
     * guaranteed loadable and deletable.
     */
    static String checkKey(String key) {
        Objects.requireNonNull(key, "cache keys must not be null");
        literal(key);
        return key;
    }

    private static String literal(String value) {
        boolean hasSingle = value.indexOf('\'') >= 0;
        boolean hasDouble = value.indexOf('"') >= 0;
        if (hasSingle && hasDouble) {
            throw new IllegalArgumentException("cache key contains both quote characters and "
                    + "cannot be expressed as an AMPS filter literal: " + value);
        }
        return hasSingle ? '"' + value + '"' : "'" + value + "'";
    }
}
