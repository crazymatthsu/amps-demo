package com.demo.amps.cli.fix;

import com.demo.amps.common.fix.FixWire;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a FIX or NVFIX payload into NVFIX with FIX 4.2 names and enumerated
 * meanings. Parsing is done by walking SOH-separated {@code tag=value} fields
 * so unit tests do not need a live AMPS instance (or even {@code FIXShredder}).
 */
public final class NvfixFormatter {

    private static final char SOH = (char) FixWire.SOH;

    private NvfixFormatter() {
    }

    /**
     * Expand a payload. Numeric tags become names; encoded values become their
     * FIX 4.2 meanings. Unknown fields are copied through. The result is still
     * SOH-separated, matching the NVFIX wire.
     */
    public static String toNvfix(String payload) {
        if (payload == null || payload.isEmpty()) {
            return payload == null ? "" : payload;
        }
        List<String> fields = splitFields(payload);
        List<String> out = new ArrayList<>(fields.size());
        for (String field : fields) {
            if (field.isEmpty()) {
                continue;
            }
            int eq = field.indexOf('=');
            if (eq <= 0) {
                out.add(field);
                continue;
            }
            String left = field.substring(0, eq);
            String value = field.substring(eq + 1);
            int tag = resolveTag(left);
            String name = tag >= 0 ? Fix42Dictionary.nameOf(tag) : left;
            String meaning = tag >= 0 ? Fix42Dictionary.meaningOf(tag, value) : value;
            out.add(name + "=" + meaning);
        }
        char sep = payload.indexOf(SOH) >= 0 ? SOH : (payload.indexOf('|') >= 0 ? '|' : SOH);
        String joined = String.join(String.valueOf(sep), out);
        boolean trailing = !payload.isEmpty() && payload.charAt(payload.length() - 1) == sep;
        return trailing ? joined + sep : joined;
    }

    static List<String> splitFields(String payload) {
        List<String> fields = new ArrayList<>();
        String normalised = payload.replace(SOH, '\n');
        // Accept '|' in tests / console copies of printable FIX.
        if (payload.indexOf(SOH) < 0 && payload.indexOf('|') >= 0) {
            normalised = payload.replace('|', '\n');
        }
        for (String part : normalised.split("\n", -1)) {
            if (!part.isEmpty()) {
                fields.add(part);
            }
        }
        return fields;
    }

    static int resolveTag(String left) {
        if (left.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(left);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return Fix42Dictionary.tagOf(left);
    }
}
