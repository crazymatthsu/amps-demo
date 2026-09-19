package com.demo.amps.connectors.encode;

import com.demo.amps.connectors.decode.Fields;
import java.util.Map;

/**
 * Writes the field map as FIX: {@code tag=value} pairs, separated <em>and terminated</em> by
 * the field separator.
 *
 * <p>Two rules make this more than a string join.
 *
 * <p>First, every key has to be a tag number. AMPS parses a {@code fix} message type by tag,
 * and a field called {@code symbol} would be written happily and then be unaddressable by any
 * filter or {@code <Key>} on the server -- a connector that looks like it works and publishes
 * records nobody can find. So a non-numeric key is refused per record, naming the key, which
 * is the runtime half of the rule the validator cannot check statically: a {@code rename}
 * to a word is only wrong once the message type is {@code fix}.
 *
 * <p>Second, the {@code #n} suffixes the decoder used to keep a repeating group are stripped,
 * so {@code 55}, {@code 55#2}, {@code 55#3} go back out as three {@code 55=} fields in the
 * order they arrived. That is what closes the round trip a FIX group needs.
 */
public final class FixEncoder implements PayloadEncoder {

    private final char separator;

    /**
     * @param separator the field separator to write, normally SOH
     */
    public FixEncoder(char separator) {
        this.separator = separator;
    }

    @Override
    public String encode(Map<String, Object> fields) {
        StringBuilder payload = new StringBuilder(64);
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            String tag = Fields.baseName(field.getKey());
            if (tag.isEmpty() || !tag.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("field '" + field.getKey()
                        + "' is not a FIX tag number, so it cannot be published to a fix topic");
            }
            String value = Fields.text(field.getValue());
            payload.append(tag).append('=').append(value == null ? "" : value).append(separator);
        }
        return payload.toString();
    }
}
