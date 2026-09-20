package com.demo.amps.connectors.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Alert stamped() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("symbol", "XYZ");
        details.put("clOrdId", "ORD-7");
        return Alert.of(Alert.Severity.WARN, "UNKNOWN_SYMBOL", "symbol XYZ is not in instruments")
                .withConnector("orders-enriched")
                .withDetails(details)
                .withRepeats(3)
                .withTimestamp(Instant.parse("2026-09-19T14:00:00.123Z"))
                .withApplication("instrument-enricher");
    }

    @Test
    @DisplayName("the wire form has exactly these eight fields, in this order")
    void writesTheFixedFieldSet() throws Exception {
        JsonNode node = MAPPER.readTree(AlertJson.write(stamped()));

        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("timestamp", "application", "severity", "code",
                "message", "connector", "repeats", "details");

        assertThat(node.get("timestamp").asText()).isEqualTo("2026-09-19T14:00:00.123Z");
        assertThat(node.get("application").asText()).isEqualTo("instrument-enricher");
        assertThat(node.get("severity").asText()).isEqualTo("WARN");
        assertThat(node.get("code").asText()).isEqualTo("UNKNOWN_SYMBOL");
        assertThat(node.get("message").asText()).isEqualTo("symbol XYZ is not in instruments");
        assertThat(node.get("connector").asText()).isEqualTo("orders-enriched");
        assertThat(node.get("repeats").asInt()).isEqualTo(3);
        assertThat(node.get("details").get("symbol").asText()).isEqualTo("XYZ");
        assertThat(node.get("details").get("clOrdId").asText()).isEqualTo("ORD-7");
    }

    @Test
    @DisplayName("the timestamp is ISO-8601 in UTC, so a filter and a foreign consumer both parse it")
    void writesAnIsoTimestamp() {
        String json = AlertJson.write(stamped().withTimestamp(Instant.parse("2026-01-02T03:04:05Z")));
        assertThat(json).contains("\"timestamp\":\"2026-01-02T03:04:05Z\"");
    }

    @Test
    @DisplayName("every field is present even when it has nothing to say")
    void writesNullsAndEmptyDetailsExplicitly() throws Exception {
        JsonNode node = MAPPER.readTree(
                AlertJson.write(Alert.of(Alert.Severity.INFO, "STATUS", "")));

        assertThat(node.get("timestamp").isNull()).isTrue();
        assertThat(node.get("application").isNull()).isTrue();
        assertThat(node.get("connector").isNull()).isTrue();
        assertThat(node.get("message").asText()).isEmpty();
        assertThat(node.get("repeats").asInt()).isZero();
        assertThat(node.get("details").isObject()).isTrue();
        assertThat(node.get("details").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("details keep their types, and what jackson cannot write becomes text")
    void writesTypedDetails() throws Exception {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", 42);
        details.put("ok", true);
        details.put("nested", Map.of("a", List.of(1, 2)));
        details.put("nothing", null);
        details.put("when", Instant.parse("2026-01-01T00:00:00Z"));
        details.put("odd", new Object() {
            @Override
            public String toString() {
                return "odd-thing";
            }
        });
        JsonNode node = MAPPER.readTree(AlertJson.write(
                Alert.of(Alert.Severity.ERROR, "X", "m").withDetails(details))).get("details");

        assertThat(node.get("count").isInt()).isTrue();
        assertThat(node.get("ok").isBoolean()).isTrue();
        assertThat(node.get("nested").get("a").isArray()).isTrue();
        assertThat(node.get("nothing").isNull()).isTrue();
        assertThat(node.get("when").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(node.get("odd").asText()).isEqualTo("odd-thing");
    }

    @Test
    @DisplayName("an alert is immutable: the with-methods copy, and a code is required")
    void alertIsAValue() {
        Alert first = Alert.of(Alert.Severity.WARN, "A", "m");
        Alert second = first.withConnector("c");
        assertThat(first.connector()).isNull();
        assertThat(second.connector()).isEqualTo("c");
        assertThat(second.withRepeats(2).repeats()).isEqualTo(2);
        assertThat(second.summary()).isEqualTo("WARN A [c] m");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Alert.of(Alert.Severity.WARN, " ", "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
