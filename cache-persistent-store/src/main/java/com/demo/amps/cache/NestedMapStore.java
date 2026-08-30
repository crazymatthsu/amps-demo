package com.demo.amps.cache;

import java.util.Map;

/**
 * Persistence SPI for the two-level cache, {@code Map<String, Map<String, V>>}.
 *
 * <p>This is deliberately NOT {@code MapStore<Map<String, V>>}. A store that
 * only sees whole inner maps can only ever rewrite whole inner maps, and the
 * entire point of the flattened AMPS representation (one SOW record per
 * {@code (outerKey, innerKey)} pair -- see {@link AmpsNestedMapStore}) is that
 * updating one inner entry costs one small record, and two processes updating
 * different inner entries of the same outer key cannot overwrite each other.
 * The SPI keeps that granularity visible: entry-level operations for the
 * common path, outer-level operations for hydration and removal.
 *
 * @param <V> the inner value type; must survive a JSON round trip
 */
public interface NestedMapStore<V> {

    /**
     * The stored inner map for {@code outerKey}; empty when the store holds no
     * entries for it. An outer key with no inner entries does not exist --
     * there is no record to represent it -- so absent and empty are the same
     * thing, and this never returns {@code null}.
     */
    Map<String, V> loadOuter(String outerKey);

    /** Every stored entry, grouped by outer key, for hydration at startup. */
    Map<String, Map<String, V>> loadAll();

    /** Persists one inner entry, replacing any previous value for the pair. */
    void storeEntry(String outerKey, String innerKey, V value);

    /** Persists every inner entry of {@code entries} under {@code outerKey}. */
    void storeEntries(String outerKey, Map<String, V> entries);

    /** Removes one inner entry; an absent pair is a no-op. */
    void deleteEntry(String outerKey, String innerKey);

    /** Removes every inner entry stored under {@code outerKey}. */
    void deleteOuter(String outerKey);

    /** Removes every entry in the store. */
    void deleteAll();
}
