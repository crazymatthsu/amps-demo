package com.demo.amps.quickfixj;

import com.demo.amps.quickfixj.QuickFixDictionary.FieldDef;
import com.demo.amps.quickfixj.QuickFixDictionary.GroupDef;
import com.demo.amps.quickfixj.QuickFixDictionary.MessageDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Dictionary-driven conversion between AMPS native {@code fix} and {@code nvfix}
 * payloads: SOH-separated {@code tag=value} or {@code Name=value} pairs.
 *
 * <p>Matches the wire shape used in this repo ({@code FixWire},
 * {@code docs/src/native-fix-and-nvfix.md}): every field ends with SOH
 * ({@code \u0001}), including the last. Session-level tags are converted if
 * present; they are never synthesised.
 *
 * <p>Unlike {@code FixWire.nameOf}, this class decodes enumerated values from
 * the QuickFIX/J XML ({@code 39=2} → {@code OrdStatus=Filled}) and encodes
 * them back. That is intentional: the dictionary is the source of meanings.
 * AMPS will then filter on the decoded names ({@code /OrdStatus = 'Filled'})
 * when this encoding is what you publish.
 *
 * <p><b>Partial / delta:</b> {@link #partialFixToNvfix(String)} and
 * {@link #partialNvfixToFix(String)} convert only the fields that are present.
 * Missing required fields are not filled in. That is the shape AMPS SOW
 * delta publish expects — "these tags changed".
 *
 * <p>{@link #fixToNvfix(String)} / {@link #nvfixToFix(String)} are the same
 * conversion for a full application message: still no invented fields, but
 * {@code MsgType} (35) is used when present so repeating groups follow that
 * message's layout.
 */
public final class FixNvfixConverter {

    /** Standard FIX / AMPS field separator. */
    public static final char SOH = '\u0001';

    private final QuickFixDictionary dictionary;
    private final UnknownFieldPolicy unknownFieldPolicy;

    public FixNvfixConverter(QuickFixDictionary dictionary) {
        this(dictionary, UnknownFieldPolicy.PASSTHROUGH);
    }

    public FixNvfixConverter(QuickFixDictionary dictionary, UnknownFieldPolicy unknownFieldPolicy) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.unknownFieldPolicy = Objects.requireNonNull(unknownFieldPolicy, "unknownFieldPolicy");
        if (unknownFieldPolicy != UnknownFieldPolicy.PASSTHROUGH) {
            throw new IllegalArgumentException("unsupported policy: " + unknownFieldPolicy);
        }
    }

    public UnknownFieldPolicy unknownFieldPolicy() {
        return unknownFieldPolicy;
    }

    /** Full FIX message → NVFIX (names + enum meanings). Does not fill missing fields. */
    public String fixToNvfix(String fixMessage) {
        return convert(fixMessage, true);
    }

    /** Full NVFIX message → FIX (tag numbers + enum codes). Does not fill missing fields. */
    public String nvfixToFix(String nvfixMessage) {
        return convert(nvfixMessage, false);
    }

    /**
     * Partial FIX fragment → partial NVFIX. Only the tags present are emitted.
     * Intended for AMPS SOW delta publish.
     */
    public String partialFixToNvfix(String fixFragment) {
        return convert(fixFragment, true);
    }

    /**
     * Partial NVFIX fragment → partial FIX. Only the names present are emitted.
     * Intended for AMPS SOW delta publish.
     */
    public String partialNvfixToFix(String nvfixFragment) {
        return convert(nvfixFragment, false);
    }

    /** Renders SOH as {@code |} for logs and tests. */
    public static String printable(String payload) {
        return payload == null ? "" : payload.replace(SOH, '|');
    }

    private String convert(String payload, boolean fromFix) {
        Objects.requireNonNull(payload, fromFix ? "fix" : "nvfix");
        List<RawField> raw = split(payload);
        if (raw.isEmpty()) {
            return "";
        }
        MessageDef message = messageOf(raw, fromFix);
        List<Node> nodes = parseNodes(raw, fromFix, message);
        StringBuilder out = new StringBuilder();
        for (Node node : nodes) {
            node.append(out, fromFix, dictionary);
        }
        return out.toString();
    }

    private MessageDef messageOf(List<RawField> raw, boolean fromFix) {
        for (RawField field : raw) {
            if (fromFix) {
                if ("35".equals(field.key())) {
                    return dictionary.messageByType(field.value()).orElse(null);
                }
            } else if ("MsgType".equalsIgnoreCase(field.key()) || "35".equals(field.key())) {
                FieldDef msgType = dictionary.fieldByName("MsgType").orElse(null);
                String code = field.value();
                if (msgType != null) {
                    code = msgType.codeOf(field.value()).orElse(field.value());
                }
                return dictionary.messageByType(code).orElse(null);
            }
        }
        return null;
    }

    private List<Node> parseNodes(List<RawField> raw, boolean fromFix, MessageDef message) {
        List<Node> nodes = new ArrayList<>();
        int i = 0;
        while (i < raw.size()) {
            RawField field = raw.get(i);
            Optional<GroupDef> group = resolveGroup(field, fromFix, message, null);
            if (group.isPresent() && looksLikeCount(field.value())) {
                ParseResult parsed = parseGroup(raw, i, fromFix, group.get());
                nodes.add(parsed.node());
                i = parsed.nextIndex();
            } else {
                nodes.add(new Scalar(field.key(), field.value(), fromFix));
                i++;
            }
        }
        return nodes;
    }

    private ParseResult parseGroup(List<RawField> raw, int countIndex, boolean fromFix, GroupDef group) {
        RawField countField = raw.get(countIndex);
        int declared = parseCount(countField.value());
        int i = countIndex + 1;
        List<List<Node>> instances = new ArrayList<>();
        while (i < raw.size() && (declared < 0 || instances.size() < declared)) {
            RawField next = raw.get(i);
            String nextName = nameOf(next.key(), fromFix);
            if (!isDelimiter(nextName, group, fromFix)) {
                break;
            }
            List<Node> instance = new ArrayList<>();
            instance.add(new Scalar(next.key(), next.value(), fromFix));
            i++;
            while (i < raw.size()) {
                RawField member = raw.get(i);
                Optional<GroupDef> nested = resolveGroup(member, fromFix, null, group);
                if (nested.isPresent() && looksLikeCount(member.value())) {
                    ParseResult nestedParsed = parseGroup(raw, i, fromFix, nested.get());
                    instance.add(nestedParsed.node());
                    i = nestedParsed.nextIndex();
                    continue;
                }
                String memberName = nameOf(member.key(), fromFix);
                if (isDelimiter(memberName, group, fromFix)) {
                    break;
                }
                if (!belongsToGroup(memberName, group, fromFix)) {
                    break;
                }
                instance.add(new Scalar(member.key(), member.value(), fromFix));
                i++;
            }
            instances.add(instance);
        }
        Group node = new Group(countField.key(), countField.value(), fromFix, instances);
        return new ParseResult(node, i);
    }

    private Optional<GroupDef> resolveGroup(
            RawField field, boolean fromFix, MessageDef message, GroupDef parent) {
        String name = nameOf(field.key(), fromFix);
        if (parent != null) {
            Optional<GroupDef> nested = parent.nestedGroup(name);
            if (nested.isPresent()) {
                return nested;
            }
            if (fromFix && isInteger(field.key())) {
                return parent.nested().stream()
                        .filter(g -> g.countTag() == Integer.parseInt(field.key()))
                        .findFirst();
            }
            return Optional.empty();
        }
        if (message != null) {
            Optional<GroupDef> fromMessage = message.group(name);
            if (fromMessage.isPresent()) {
                return fromMessage;
            }
        }
        if (fromFix && isInteger(field.key())) {
            return dictionary.groupByCountTag(Integer.parseInt(field.key()));
        }
        return dictionary.groupByCountName(name);
    }

    private boolean belongsToGroup(String fieldName, GroupDef group, boolean fromFix) {
        if (group.containsMember(fieldName)) {
            return true;
        }
        if (fromFix && isInteger(fieldName)) {
            FieldDef def = dictionary.fieldByTag(Integer.parseInt(fieldName)).orElse(null);
            return def != null && group.containsMember(def.name());
        }
        FieldDef def = dictionary.fieldByName(fieldName).orElse(null);
        return def != null && group.containsMember(def.name());
    }

    private boolean isDelimiter(String fieldName, GroupDef group, boolean fromFix) {
        if (group.delimiterName().equals(fieldName)) {
            return true;
        }
        FieldDef def = fromFix && isInteger(fieldName)
                ? dictionary.fieldByTag(Integer.parseInt(fieldName)).orElse(null)
                : dictionary.fieldByName(fieldName).orElse(null);
        return def != null && group.delimiterName().equals(def.name());
    }

    private String nameOf(String key, boolean fromFix) {
        if (!fromFix) {
            return key;
        }
        if (!isInteger(key)) {
            return key;
        }
        FieldDef def = dictionary.fieldByTag(Integer.parseInt(key)).orElse(null);
        return def != null ? def.name() : key;
    }

    private static boolean looksLikeCount(String value) {
        return parseCount(value) >= 0;
    }

    private static int parseCount(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        int n = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return n;
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
        List<RawField> fields = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= payload.length(); i++) {
            if (i == payload.length() || payload.charAt(i) == SOH) {
                if (i > start) {
                    String token = payload.substring(start, i);
                    int eq = token.indexOf('=');
                    if (eq > 0) {
                        fields.add(new RawField(token.substring(0, eq), token.substring(eq + 1)));
                    }
                }
                start = i + 1;
            }
        }
        return fields;
    }

    private record RawField(String key, String value) {}

    private record ParseResult(Node node, int nextIndex) {}

    private sealed interface Node permits Scalar, Group {
        void append(StringBuilder out, boolean fromFix, QuickFixDictionary dictionary);
    }

    private static final class Scalar implements Node {
        private final String key;
        private final String value;
        private final boolean fromFix;

        Scalar(String key, String value, boolean fromFix) {
            this.key = key;
            this.value = value;
            this.fromFix = fromFix;
        }

        @Override
        public void append(StringBuilder out, boolean convertFromFix, QuickFixDictionary dictionary) {
            FieldDef def = resolve(dictionary);
            String outKey;
            String outValue;
            if (convertFromFix) {
                outKey = def != null ? def.name() : key;
                outValue = decodeEnum(def, value);
            } else {
                if (def != null) {
                    outKey = Integer.toString(def.number());
                } else if (isInteger(key)) {
                    outKey = key;
                } else {
                    // Unknown NVFIX name: AMPS-style passthrough keeps the name as the
                    // FIX "tag" so a round-trip can restore it. Documented policy.
                    outKey = key;
                }
                outValue = encodeEnum(def, value);
            }
            out.append(outKey).append('=').append(outValue).append(SOH);
        }

        private FieldDef resolve(QuickFixDictionary dictionary) {
            if (fromFix && isInteger(key)) {
                return dictionary.fieldByTag(Integer.parseInt(key)).orElse(null);
            }
            FieldDef byName = dictionary.fieldByName(key).orElse(null);
            if (byName != null) {
                return byName;
            }
            if (isInteger(key)) {
                return dictionary.fieldByTag(Integer.parseInt(key)).orElse(null);
            }
            return null;
        }

        private static String decodeEnum(FieldDef def, String value) {
            if (def == null || !def.isEnumerated()) {
                return value;
            }
            return def.meaningOf(value).orElse(value);
        }

        private static String encodeEnum(FieldDef def, String value) {
            if (def == null || !def.isEnumerated()) {
                return value;
            }
            return def.codeOf(value).orElse(value);
        }
    }

    private static final class Group implements Node {
        private final String countKey;
        private final String countValue;
        private final boolean fromFix;
        private final List<List<Node>> instances;

        Group(String countKey, String countValue, boolean fromFix, List<List<Node>> instances) {
            this.countKey = countKey;
            this.countValue = countValue;
            this.fromFix = fromFix;
            this.instances = instances;
        }

        @Override
        public void append(StringBuilder out, boolean convertFromFix, QuickFixDictionary dictionary) {
            // Prefer the observed instance count over a stale count field so a
            // round-trip does not drop group members the parser actually kept.
            String count = instances.isEmpty() ? countValue : Integer.toString(instances.size());
            new Scalar(countKey, count, fromFix)
                    .append(out, convertFromFix, dictionary);
            for (List<Node> instance : instances) {
                for (Node node : instance) {
                    node.append(out, convertFromFix, dictionary);
                }
            }
        }
    }
}
