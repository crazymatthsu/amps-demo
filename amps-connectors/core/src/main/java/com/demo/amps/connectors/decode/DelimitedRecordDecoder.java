package com.demo.amps.connectors.decode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decoder for the two delimited formats, FIX and NVFIX.
 *
 * <p>Both are {@code tag=value} pairs separated by a field separator (SOH by default); they
 * differ only in whether the tag is a FIX tag number or a field name, which this layer does
 * not care about. Splitting on the <em>first</em> {@code =} keeps values containing {@code =}
 * intact, and a trailing separator -- which every real FIX message has -- is tolerated rather
 * than read as an empty field.
 *
 * <p>A repeated tag is kept, not overwritten: the first occurrence goes under the bare tag and
 * later ones under {@code tag#2}, {@code tag#3}… in the order they appeared. That is what
 * makes a repeating group survive the round trip -- the encoder strips the suffix again -- and
 * it is why a filter on {@code 55} answers about the <em>first</em> {@code 55} unless it names
 * an occurrence explicitly. Correlating one group entry's fields with another's is not
 * something a flat field map can express, and this framework does not pretend otherwise.
 */
public final class DelimitedRecordDecoder implements RecordDecoder {

    private final char separator;

    /**
     * @param separator the field separator, normally SOH
     */
    public DelimitedRecordDecoder(char separator) {
        this.separator = separator;
    }

    @Override
    public Map<String, Object> decode(String payload) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) {
            return fields;
        }
        int start = 0;
        int length = payload.length();
        while (start < length) {
            int end = payload.indexOf(separator, start);
            if (end < 0) {
                end = length;
            }
            if (end > start) {
                addPair(fields, payload, start, end);
            }
            start = end + 1;
        }
        return fields;
    }

    /** One {@code tag=value} segment, under its tag or under the next free occurrence of it. */
    private static void addPair(Map<String, Object> fields, String payload, int start, int end) {
        int equals = payload.indexOf('=', start);
        if (equals < 0 || equals >= end) {
            // A segment with no '=' is not a field: junk between messages, or a checksum a
            // feed wrote oddly. Ignored rather than failing the whole message.
            return;
        }
        String tag = payload.substring(start, equals).trim();
        if (tag.isEmpty()) {
            return;
        }
        String value = payload.substring(equals + 1, end);
        if (!fields.containsKey(tag)) {
            fields.put(tag, value);
            return;
        }
        int occurrence = 2;
        while (fields.containsKey(tag + Fields.OCCURRENCE + occurrence)) {
            occurrence++;
        }
        fields.put(tag + Fields.OCCURRENCE + occurrence, value);
    }
}
