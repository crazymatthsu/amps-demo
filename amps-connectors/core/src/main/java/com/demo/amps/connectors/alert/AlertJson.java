package com.demo.amps.connectors.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;

/**
 * The wire form of an {@link Alert}: one flat JSON object with a fixed set of fields.
 *
 * <pre>{@code
 * {"timestamp":"2026-09-19T14:00:00Z","application":"instrument-enricher","severity":"WARN",
 *  "code":"UNKNOWN_SYMBOL","message":"symbol XYZ is not in instruments",
 *  "connector":"orders-enriched","repeats":0,"details":{"symbol":"XYZ","clOrdId":"ORD-7"}}
 * }</pre>
 *
 * <p>Built field by field with an {@link ObjectNode} rather than by serialising the record,
 * so the field names and their order are this class's promise and not a side effect of how
 * the record happens to be declared -- a reader on the other end of the topic is written
 * against these names. Every field is always present: a missing connector is an explicit
 * {@code null}, no details is {@code {}}, and the timestamp is ISO-8601 in UTC (what
 * {@link java.time.Instant#toString()} writes), because that is what an AMPS
 * {@code /timestamp} filter and a Kafka consumer in another language both parse without a
 * format string.
 *
 * <p>Details are whatever the raiser put in the map. A value jackson can write -- a string, a
 * number, a boolean, a nested map or list, a temporal -- is written as such; anything else
 * becomes its {@code toString()}, because an alert that could not be serialised would be an
 * alert about the alerting.
 */
public final class AlertJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private AlertJson() {
    }

    /**
     * @param alert the alert; stamped or not
     * @return the JSON object, on one line
     */
    public static String write(Alert alert) {
        try {
            return MAPPER.writeValueAsString(toNode(alert));
        } catch (JsonProcessingException e) {
            // Unreachable: every child of the node was itself built by the mapper.
            throw new IllegalStateException("alert could not be written as JSON", e);
        }
    }

    /**
     * The same object as a tree, for a sink that wraps the alert in an envelope of its own.
     *
     * @param alert the alert
     * @return the object node, fields in wire order
     */
    public static ObjectNode toNode(Alert alert) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("timestamp", alert.timestamp() == null ? null : alert.timestamp().toString());
        node.put("application", alert.application());
        node.put("severity", alert.severity().name());
        node.put("code", alert.code());
        node.put("message", alert.message());
        node.put("connector", alert.connector());
        node.put("repeats", alert.repeats());
        ObjectNode details = node.putObject("details");
        for (Map.Entry<String, Object> detail : alert.details().entrySet()) {
            details.set(detail.getKey(), value(detail.getValue()));
        }
        return node;
    }

    private static JsonNode value(Object value) {
        try {
            return MAPPER.valueToTree(value);
        } catch (IllegalArgumentException e) {
            return TextNode.valueOf(String.valueOf(value));
        }
    }
}
