package com.demo.amps.connectors.decode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading and writing the decoded field map, including the two spellings that make it more
 * than a flat {@code Map}: dotted paths into nested objects, and {@code #n} suffixes for a
 * repeated field.
 *
 * <p>Every decoder produces a {@link LinkedHashMap} whose iteration order is wire order, and
 * everything downstream -- filters, key extraction, renames, encoders -- addresses it through
 * here rather than through {@code Map.get}, so all of them understand the same two
 * conventions:
 *
 * <ul>
 *   <li><strong>Dotted paths.</strong> {@code get(fields, "order.price")} walks nested maps,
 *       and {@code put} creates the nesting on the way down. A literal key containing dots
 *       wins over the walk, because a FIX-shaped feed is allowed to call a field
 *       {@code 1.2}.</li>
 *   <li><strong>Occurrences.</strong> A repeated field -- a FIX repeating group -- keeps its
 *       first occurrence under the bare name and the later ones under {@code name#2},
 *       {@code name#3}… That is what lets a group survive a decode/encode round trip, and it
 *       is why {@link #baseName} and {@link #occurrenceKeys} exist: an encoder strips the
 *       suffix, and a {@code keep} or {@code drop} that names {@code 55} means every
 *       {@code 55} the message carried, not just the first.</li>
 * </ul>
 */
public final class Fields {

    /** Separates a field name from the occurrence number of a repeated field. */
    public static final char OCCURRENCE = '#';

    private Fields() {
    }

    /**
     * Read a field.
     *
     * @param fields the decoded field map
     * @param path a field name, or a dotted path into nested objects
     * @return the value, or {@code null} when the path is absent (or explicitly null)
     */
    public static Object get(Map<String, Object> fields, String path) {
        if (fields == null || path == null) {
            return null;
        }
        // A literal key wins: a FIX feed may legitimately name a field "1.2", and it would be
        // surprising for a configuration that spells the key exactly to miss it.
        if (fields.containsKey(path)) {
            return fields.get(path);
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            return null;
        }
        Object nested = fields.get(path.substring(0, dot));
        if (nested instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return get(typed, path.substring(dot + 1));
        }
        return null;
    }

    /**
     * Whether a field is present, which is not the same question as whether it has a value:
     * an explicit JSON {@code null} is present and null.
     *
     * @param fields the decoded field map
     * @param path a field name, or a dotted path into nested objects
     * @return {@code true} if the payload carried the field
     */
    public static boolean contains(Map<String, Object> fields, String path) {
        if (fields == null || path == null) {
            return false;
        }
        if (fields.containsKey(path)) {
            return true;
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            return false;
        }
        Object nested = fields.get(path.substring(0, dot));
        if (nested instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return contains(typed, path.substring(dot + 1));
        }
        return false;
    }

    /**
     * Write a field, creating any nesting a dotted path names.
     *
     * <p>An existing literal key is overwritten in place, so the field keeps its position in
     * wire order; a new one is appended, which is where a derived or {@code set} field lands.
     *
     * @param fields the map to write into
     * @param path a field name, or a dotted path into nested objects
     * @param value the value; may be {@code null} to record a cleared field
     */
    public static void put(Map<String, Object> fields, String path, Object value) {
        if (fields.containsKey(path) || path.indexOf('.') < 0) {
            fields.put(path, value);
            return;
        }
        int dot = path.indexOf('.');
        String head = path.substring(0, dot);
        Object nested = fields.get(head);
        Map<String, Object> child;
        if (nested instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            child = typed;
        } else {
            // Either absent or a scalar standing where an object has to go. Creating the
            // object is the only way to honour the path the configuration wrote.
            child = new LinkedHashMap<>();
            fields.put(head, child);
        }
        put(child, path.substring(dot + 1), value);
    }

    /**
     * Remove a field.
     *
     * @param fields the map to write into
     * @param path a field name, or a dotted path into nested objects
     * @return the removed value, or {@code null} when the path was absent
     */
    public static Object remove(Map<String, Object> fields, String path) {
        if (fields.containsKey(path)) {
            return fields.remove(path);
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            return null;
        }
        Object nested = fields.get(path.substring(0, dot));
        if (nested instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return remove(typed, path.substring(dot + 1));
        }
        return null;
    }

    /**
     * The field name behind a decoded key, with any {@code #n} occurrence suffix removed.
     *
     * @param key a decoded key, e.g. {@code 55} or {@code 55#3}
     * @return the field name, e.g. {@code 55}
     */
    public static String baseName(String key) {
        int hash = key.indexOf(OCCURRENCE);
        return hash < 0 ? key : key.substring(0, hash);
    }

    /**
     * Every key that is one occurrence of {@code name} -- the bare name plus {@code name#2},
     * {@code name#3}…, in map order.
     *
     * <p>A dotted path has no occurrences: repetition is a delimited-format idea, and a nested
     * JSON object repeats by being an array instead.
     *
     * @param fields the decoded field map
     * @param name the field name
     * @return the matching keys, empty when the field is absent
     */
    public static List<String> occurrenceKeys(Map<String, Object> fields, String name) {
        List<String> keys = new ArrayList<>(1);
        if (name.indexOf('.') >= 0) {
            if (contains(fields, name)) {
                keys.add(name);
            }
            return keys;
        }
        String prefix = name + OCCURRENCE;
        for (String key : fields.keySet()) {
            if (key.equals(name) || key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * A value as the text a filter, a key or a delimited encoder needs.
     *
     * @param value a decoded value
     * @return its string form, or {@code null} when the value is null
     */
    public static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
