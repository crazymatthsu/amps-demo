package com.demo.amps.quickfixj;

import com.demo.amps.quickfixj.QuickFixDictionary.FieldDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a partial FIX or NVFIX payload for AMPS SOW <b>delta</b> publish:
 * only the fields that were {@link #set set}, in insertion order, with a
 * trailing SOH. Missing session / header / body fields are never invented.
 *
 * <p>Keys may be FIX tags ({@code 39} or {@code "39"}) or NVFIX names
 * ({@code "OrdStatus"}) and may be mixed on the same builder. Values may be
 * enum codes ({@code "2"}) or dictionary meanings ({@code "Filled"}).
 *
 * <p>Repeating groups: set the count field then each instance's members in
 * AMPS order (count, then delimiter + members, then the next delimiter).
 * {@link #group(String, int)} is a readable wrapper around that sequence.
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
}
