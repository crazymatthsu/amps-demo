package com.demo.amps.quickfixj;

import com.demo.amps.quickfixj.QuickFixDictionary.FieldDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds a partial FIX or NVFIX payload for AMPS SOW <b>delta</b> publish:
 * only the fields that were {@link #set set} (or parsed), in insertion order,
 * with a trailing SOH. Missing session / header / body fields are never invented.
 *
 * <p>Keys may be FIX tags ({@code 39} or {@code "39"}) or NVFIX names
 * ({@code "OrdStatus"}) and may be mixed on the same builder. Values may be
 * enum codes ({@code "2"}) or dictionary meanings ({@code "Filled"}).
 * {@link #get(int)} / {@link #get(String)} look up by either identifier
 * regardless of how the field was set or whether the source payload was FIX
 * or NVFIX.
 *
 * <p>Repeating groups: set the count field then each instance's members in
 * AMPS order (count, then delimiter + members, then the next delimiter).
 * {@link #group(String, int)} is a readable wrapper around that sequence.
 * {@link #get} returns the last matching value; {@link #getAll} returns every
 * occurrence in order.
 *
 * <pre>{@code
 * String nvfix = new DeltaPublishBuilder(dict)
 *     .set(11, "PARENT-AAPL-2")
 *     .set("OrdStatus", "Filled")
 *     .set("LeavesQty", "0")
 *     .buildNvfix();
 * String fix = new DeltaPublishBuilder(dict)
 *     .set("ClOrdID", "PARENT-AAPL-2")
 *     .set(39, "2")
 *     .buildFix();
 * Optional<String> status = DeltaPublishBuilder.fromFix(dict, fix).get("OrdStatus");
 * }</pre>
 */
public final class DeltaPublishBuilder {

    private final QuickFixDictionary dictionary;
    private final FixNvfixConverter converter;
    private final List<Entry> fields = new ArrayList<>();

    public DeltaPublishBuilder(QuickFixDictionary dictionary) {
        this(dictionary, new FixNvfixConverter(dictionary));
    }

    public DeltaPublishBuilder(QuickFixDictionary dictionary, FixNvfixConverter converter) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.converter = Objects.requireNonNull(converter, "converter");
    }

    /** Parse an existing SOH-terminated FIX fragment; {@link #get} works by tag or name. */
    public static DeltaPublishBuilder fromFix(QuickFixDictionary dictionary, String fixPayload) {
        return fromFix(dictionary, new FixNvfixConverter(dictionary), fixPayload);
    }

    public static DeltaPublishBuilder fromFix(
            QuickFixDictionary dictionary, FixNvfixConverter converter, String fixPayload) {
        return new DeltaPublishBuilder(dictionary, converter).addFields(fixPayload);
    }

    /** Parse an existing SOH-terminated NVFIX fragment; {@link #get} works by tag or name. */
    public static DeltaPublishBuilder fromNvfix(QuickFixDictionary dictionary, String nvfixPayload) {
        return fromNvfix(dictionary, new FixNvfixConverter(dictionary), nvfixPayload);
    }

    public static DeltaPublishBuilder fromNvfix(
            QuickFixDictionary dictionary, FixNvfixConverter converter, String nvfixPayload) {
        return new DeltaPublishBuilder(dictionary, converter).addFields(nvfixPayload);
    }

    /**
     * Append a field identified by FIX tag number. The value may be a code or
     * an enum meaning; encoding happens at {@link #buildFix()} /
     * {@link #buildNvfix()}.
     */
    public DeltaPublishBuilder set(int tag, String value) {
        return set(Integer.toString(tag), value);
    }

    /**
     * Append a field identified by a FIX tag ({@code "39"}) or NVFIX name
     * ({@code "OrdStatus"}). Duplicate keys are kept in call order (needed
     * for repeating-group members).
     */
    public DeltaPublishBuilder set(String tagOrName, String value) {
        Objects.requireNonNull(tagOrName, "tagOrName");
        if (tagOrName.isEmpty()) {
            throw new IllegalArgumentException("tagOrName must not be empty");
        }
        Objects.requireNonNull(value, "value");
        fields.add(new Entry(tagOrName, value));
        return this;
    }

    /**
     * Last value for this FIX tag, whether it was set/parsed as a number or
     * as the dictionary name. Empty if the field was never set.
     */
    public Optional<String> get(int tag) {
        return lastMatch(Integer.toString(tag));
    }

    /**
     * Last value for this FIX tag ({@code "39"}) or NVFIX name
     * ({@code "OrdStatus"}), whether the stored message is FIX or NVFIX.
     * Empty if the field was never set.
     */
    public Optional<String> get(String tagOrName) {
        Objects.requireNonNull(tagOrName, "tagOrName");
        if (tagOrName.isEmpty()) {
            return Optional.empty();
        }
        return lastMatch(tagOrName);
    }

    /** Every value for this tag, in insertion order (repeating groups). */
    public List<String> getAll(int tag) {
        return allMatches(Integer.toString(tag));
    }

    /** Every value for this tag or name, in insertion order (repeating groups). */
    public List<String> getAll(String tagOrName) {
        Objects.requireNonNull(tagOrName, "tagOrName");
        if (tagOrName.isEmpty()) {
            return List.of();
        }
        return allMatches(tagOrName);
    }

    /**
     * Start a repeating group: emits the count field, then returns a nested
     * builder for that instance's members. Call {@link GroupInstance#end()}
     * (or keep using {@link #set} on this builder) after the members.
     */
    public GroupInstance group(int countTag, int count) {
        set(countTag, Integer.toString(count));
        return new GroupInstance();
    }

    /** @see #group(int, int) */
    public GroupInstance group(String countTagOrName, int count) {
        set(countTagOrName, Integer.toString(count));
        return new GroupInstance();
    }

    /**
     * FIX {@code tag=value} fragment, trailing SOH. Enum values are codes.
     * Unknown tags/names pass through (same policy as
     * {@link UnknownFieldPolicy#PASSTHROUGH}).
     */
    public String buildFix() {
        StringBuilder out = new StringBuilder();
        for (Entry field : fields) {
            appendFix(out, field);
        }
        return out.toString();
    }

    /**
     * NVFIX {@code Name=value} fragment, trailing SOH. Enum values are
     * dictionary meanings. Built by encoding to FIX then converting, so
     * repeating-group layout matches {@link FixNvfixConverter}.
     */
    public String buildNvfix() {
        return converter.partialFixToNvfix(buildFix());
    }

    public QuickFixDictionary dictionary() {
        return dictionary;
    }

    public FixNvfixConverter converter() {
        return converter;
    }

    private DeltaPublishBuilder addFields(String payload) {
        Objects.requireNonNull(payload, "payload");
        for (RawField raw : split(payload)) {
            fields.add(new Entry(raw.key(), raw.value()));
        }
        return this;
    }

    private Optional<String> lastMatch(String query) {
        Entry found = null;
        for (Entry field : fields) {
            if (sameField(field.key, query)) {
                found = field;
            }
        }
        return found == null ? Optional.empty() : Optional.of(found.value);
    }

    private List<String> allMatches(String query) {
        List<String> out = new ArrayList<>();
        for (Entry field : fields) {
            if (sameField(field.key, query)) {
                out.add(field.value);
            }
        }
        return List.copyOf(out);
    }

    private boolean sameField(String storedKey, String query) {
        if (storedKey.equals(query)) {
            return true;
        }
        if (isInteger(storedKey) && isInteger(query)) {
            return Integer.parseInt(storedKey) == Integer.parseInt(query);
        }
        FieldDef stored = resolve(storedKey);
        FieldDef want = resolve(query);
        if (stored != null && want != null) {
            return stored.number() == want.number();
        }
        if (stored != null) {
            if (isInteger(query) && stored.number() == Integer.parseInt(query)) {
                return true;
            }
            return stored.name().equalsIgnoreCase(query);
        }
        if (want != null) {
            if (isInteger(storedKey) && want.number() == Integer.parseInt(storedKey)) {
                return true;
            }
            return want.name().equalsIgnoreCase(storedKey);
        }
        return storedKey.equalsIgnoreCase(query);
    }

    private void appendFix(StringBuilder out, Entry field) {
        FieldDef def = resolve(field.key);
        String outKey;
        String outValue;
        if (def != null) {
            outKey = Integer.toString(def.number());
            outValue = encodeEnum(def, field.value);
        } else {
            outKey = field.key;
            outValue = field.value;
        }
        out.append(outKey).append('=').append(outValue).append(FixNvfixConverter.SOH);
    }

    private FieldDef resolve(String key) {
        if (isInteger(key)) {
            FieldDef byTag = dictionary.fieldByTag(Integer.parseInt(key)).orElse(null);
            if (byTag != null) {
                return byTag;
            }
        }
        return dictionary.fieldByName(key).orElse(null);
    }

    private static String encodeEnum(FieldDef def, String value) {
        if (def == null || !def.isEnumerated()) {
            return value;
        }
        return def.codeOf(value).orElse(value);
    }

    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static List<RawField> split(String payload) {
        List<RawField> parsed = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= payload.length(); i++) {
            if (i == payload.length() || payload.charAt(i) == FixNvfixConverter.SOH) {
                if (i > start) {
                    String token = payload.substring(start, i);
                    int eq = token.indexOf('=');
                    if (eq > 0) {
                        parsed.add(new RawField(token.substring(0, eq), token.substring(eq + 1)));
                    }
                }
                start = i + 1;
            }
        }
        return parsed;
    }

    /**
     * Fluent member-setter for one repeating-group instance. Sets still land
     * on the parent builder in call order.
     */
    public final class GroupInstance {
        private GroupInstance() {}

        public GroupInstance set(int tag, String value) {
            DeltaPublishBuilder.this.set(tag, value);
            return this;
        }

        public GroupInstance set(String tagOrName, String value) {
            DeltaPublishBuilder.this.set(tagOrName, value);
            return this;
        }

        public Optional<String> get(int tag) {
            return DeltaPublishBuilder.this.get(tag);
        }

        public Optional<String> get(String tagOrName) {
            return DeltaPublishBuilder.this.get(tagOrName);
        }

        public DeltaPublishBuilder end() {
            return DeltaPublishBuilder.this;
        }

        public String buildFix() {
            return DeltaPublishBuilder.this.buildFix();
        }

        public String buildNvfix() {
            return DeltaPublishBuilder.this.buildNvfix();
        }
    }

    private record Entry(String key, String value) {}

    private record RawField(String key, String value) {}
}
