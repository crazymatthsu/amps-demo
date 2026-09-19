package com.demo.amps.connectors.encode;

import com.demo.amps.connectors.decode.Fields;
import java.util.Map;

/**
 * Writes the field map as NVFIX: {@code name=value} pairs in FIX framing, separated and
 * terminated by the field separator.
 *
 * <p>The same wire shape as {@link FixEncoder} with the one rule that made FIX strict dropped:
 * an NVFIX field is named, not numbered, so {@code symbol=AAPL} is exactly the point. It is
 * the format for a feed whose fields have names worth keeping -- a renamed FIX message, a
 * flattened JSON document -- on a topic that still wants FIX's cheap framing rather than JSON's
 * parsing cost.
 *
 * <p>The {@code #n} suffixes of a repeated field are stripped here too, so a group decoded out
 * of FIX can be published as NVFIX and still read as a group.
 */
public final class NvfixEncoder implements PayloadEncoder {

    private final char separator;

    /**
     * @param separator the field separator to write, normally SOH
     */
    public NvfixEncoder(char separator) {
        this.separator = separator;
    }

    @Override
    public String encode(Map<String, Object> fields) {
        StringBuilder payload = new StringBuilder(64);
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            String name = Fields.baseName(field.getKey());
            if (name.isEmpty()) {
                throw new IllegalArgumentException("a field with no name cannot be published "
                        + "to an nvfix topic");
            }
            String value = Fields.text(field.getValue());
            payload.append(name).append('=').append(value == null ? "" : value).append(separator);
        }
        return payload.toString();
    }
}
