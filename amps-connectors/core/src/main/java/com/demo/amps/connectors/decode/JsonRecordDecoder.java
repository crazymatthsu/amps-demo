package com.demo.amps.connectors.decode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoder for JSON payloads, keeping the document's structure and its types.
 *
 * <p>Deliberately <em>not</em> flattened: a nested object stays a nested {@link Map} and an
 * array stays a {@link List}, because this pipeline's job is to publish a payload again at the
 * other end, and a flattened document cannot be re-encoded into the one it came from. Dotted
 * addressing -- what a filter or a key writes as {@code order.price} -- is {@link Fields}'
 * business instead, which walks the nesting on the way in and recreates it on the way out.
 *
 * <p>Types are preserved as far as JSON can express them, because an encoder at the far end
 * writes what it is given: an integral number decodes to {@link Long} (or
 * {@link java.math.BigInteger} when it does not fit), a fractional one to
 * {@link java.math.BigDecimal} rather than {@code double} so a price survives the round trip
 * digit for digit, and an explicit {@code null} stays null and present.
 */
public final class JsonRecordDecoder implements RecordDecoder {

    private final ObjectReader reader;

    /**
     * @param mapper the mapper to parse with; shared and left exactly as it is. The two
     *     settings this decoder insists on are applied to a derived reader instead:
     *     {@code USE_BIG_DECIMAL_FOR_FLOATS}, because jackson otherwise reads a fractional
     *     number into a {@code double}, and {@code STRIP_TRAILING_BIGDECIMAL_ZEROES} off,
     *     because it normalises the decimal afterwards. Without both, a price published as
     *     {@code 185.50} comes back out as {@code 185.5} -- the same number, and not the same
     *     message
     */
    public JsonRecordDecoder(ObjectMapper mapper) {
        this.reader = mapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    @Override
    public Map<String, Object> decode(String payload) {
        if (payload == null || payload.isBlank()) {
            // A record with no body: legitimate for a delete, and there is nothing malformed
            // about it. The pipeline decides whether an empty map is usable.
            return new LinkedHashMap<>();
        }
        JsonNode root;
        try {
            root = reader.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "payload is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("a JSON payload must be an object, not "
                    + (root == null ? "empty" : root.getNodeType().toString().toLowerCase()));
        }
        return object(root);
    }

    /** One JSON object as an ordered field map. */
    private static Map<String, Object> object(JsonNode node) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            fields.put(member.getKey(), value(member.getValue()));
        }
        return fields;
    }

    /** One JSON value as the java type the encoders can write back unchanged. */
    private static Object value(JsonNode node) {
        if (node.isObject()) {
            return object(node);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                values.add(value(element));
            }
            return values;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            // Long unless the feed really is publishing numbers beyond 64 bits, in which case
            // losing precision would be worse than the unusual type.
            return node.canConvertToLong() ? (Object) node.longValue() : node.bigIntegerValue();
        }
        if (node.isNumber()) {
            // BigDecimal, not double: a price that went out as 185.50 comes back as 185.50.
            return node.decimalValue();
        }
        return node.asText();
    }
}
