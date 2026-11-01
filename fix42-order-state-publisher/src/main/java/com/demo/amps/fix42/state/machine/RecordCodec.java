package com.demo.amps.fix42.state.machine;

import java.util.ArrayList;
import java.util.List;

/**
 * The record's three list encodings, and their parsers: 9015 the pending set,
 * 9021 the ClOrdID chain, 9031 the busted ExecIDs.
 *
 * <p>Any {@code %}, {@code :}, {@code ;} or {@code ,} inside an id is
 * {@code %}-escaped ({@code %25}, {@code %3A}, {@code %3B}, {@code %2C}), so
 * an id such as {@code 20261031:DESK7:000123} round-trips (R11). The parsers
 * are the other half: the SOW rebuild (phase 4) reads the record back through
 * them, and a value they refuse loads as {@code 9018=UNRECOVERED} rather than
 * silently empty.
 */
public final class RecordCodec {

    /** The empty pending set: a delta can overwrite a field but never remove it. */
    public static final String NONE = "NONE";

    private RecordCodec() {
    }

    // ---- escaping -------------------------------------------------------------

    public static String escape(String id) {
        StringBuilder out = null;
        for (int i = 0; i < id.length(); i++) {
            String replacement = switch (id.charAt(i)) {
                case '%' -> "%25";
                case ':' -> "%3A";
                case ';' -> "%3B";
                case ',' -> "%2C";
                default -> null;
            };
            if (replacement != null && out == null) {
                out = new StringBuilder(id.length() + 8).append(id, 0, i);
            }
            if (out != null) {
                if (replacement != null) {
                    out.append(replacement);
                } else {
                    out.append(id.charAt(i));
                }
            }
        }
        return out == null ? id : out.toString();
    }

    /** @throws IllegalArgumentException on a {@code %} not followed by one of the four codes */
    public static String unescape(String text) {
        int percent = text.indexOf('%');
        if (percent < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length()).append(text, 0, percent);
        for (int i = percent; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            if (i + 2 >= text.length()) {
                throw new IllegalArgumentException("truncated escape in '" + text + "'");
            }
            String code = text.substring(i + 1, i + 3);
            out.append(switch (code) {
                case "25" -> '%';
                case "3A" -> ':';
                case "3B" -> ';';
                case "2C" -> ',';
                default -> throw new IllegalArgumentException("unknown escape %" + code + " in '" + text + "'");
            });
            i += 2;
        }
        return out.toString();
    }

    // ---- 9015 -------------------------------------------------------------------

    /**
     * {@code ACTION:flag:qty:px:id} per request, joined by {@code ;}, oldest
     * first; {@code NONE} for the empty set. {@code qty} and {@code px} are
     * empty where nothing was proposed, so a genuine zero price and "no
     * proposal" never read the same (S17).
     */
    public static String encodePending(List<PendingRequest> pending) {
        if (pending.isEmpty()) {
            return NONE;
        }
        StringBuilder out = new StringBuilder(pending.size() * 24);
        for (PendingRequest request : pending) {
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(request.action().name()).append(':')
                    .append(request.requested() ? 'R' : 'I').append(':')
                    .append(request.qty() == null ? "" : request.qty()).append(':')
                    .append(request.px() == null ? "" : request.px()).append(':')
                    .append(escape(request.clOrdId()));
        }
        return out.toString();
    }

    /** @throws IllegalArgumentException when the value is not one {@link #encodePending} writes */
    public static List<PendingRequest> decodePending(String text) {
        List<PendingRequest> pending = new ArrayList<>();
        if (NONE.equals(text)) {
            return pending;
        }
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("empty 9015");
        }
        for (String element : text.split(";", -1)) {
            String[] fields = element.split(":", -1);
            if (fields.length != 5) {
                throw new IllegalArgumentException("9015 element '" + element + "' has "
                        + fields.length + " fields, not 5");
            }
            PendingAction action = PendingAction.valueOf(fields[0]);
            boolean requested = switch (fields[1]) {
                case "R" -> true;
                case "I" -> false;
                default -> throw new IllegalArgumentException("9015 flag '" + fields[1] + "'");
            };
            String id = unescape(fields[4]);
            if (id.isEmpty()) {
                throw new IllegalArgumentException("9015 element '" + element + "' has no id");
            }
            pending.add(new PendingRequest(id, action, requested,
                    fields[2].isEmpty() ? null : fields[2], fields[3].isEmpty() ? null : fields[3]));
        }
        return pending;
    }

    // ---- 9021 and 9031 ------------------------------------------------------

    /** The last {@code limit} ids, oldest first, comma-joined and escaped; {@code null} for none. */
    public static String encodeIds(List<String> ids, int limit) {
        if (ids.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(ids.size() * 12);
        for (int i = Math.max(0, ids.size() - limit); i < ids.size(); i++) {
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(escape(ids.get(i)));
        }
        return out.toString();
    }

    public static List<String> decodeIds(String text) {
        List<String> ids = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return ids;
        }
        for (String id : text.split(",", -1)) {
            String plain = unescape(id);
            if (plain.isEmpty()) {
                throw new IllegalArgumentException("empty id in '" + text + "'");
            }
            ids.add(plain);
        }
        return ids;
    }
}
