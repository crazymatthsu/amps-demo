package com.demo.amps.connectors.decode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonRecordDecoderTest {

    private final JsonRecordDecoder decoder = new JsonRecordDecoder(new ObjectMapper());

    @Test
    @DisplayName("an integral number decodes to Long, a fractional one to BigDecimal")
    void keepsNumericTypes() {
        Map<String, Object> fields = decoder.decode("{\"qty\":100,\"price\":185.50}");
        assertThat(fields.get("qty")).isEqualTo(100L);
        assertThat(fields.get("price")).isEqualTo(new BigDecimal("185.50"));
    }

    @Test
    @DisplayName("BigDecimal rather than double, so a price round-trips digit for digit")
    void keepsTrailingZerosOnADecimal() {
        assertThat(decoder.decode("{\"price\":185.50}").get("price")).hasToString("185.50");
    }

    @Test
    void keepsBooleansAndStrings() {
        Map<String, Object> fields = decoder.decode("{\"live\":true,\"id\":\"C-1\"}");
        assertThat(fields.get("live")).isEqualTo(Boolean.TRUE);
        assertThat(fields.get("id")).isEqualTo("C-1");
    }

    @Test
    @DisplayName("an explicit null is present and null, not absent")
    void keepsExplicitNulls() {
        Map<String, Object> fields = decoder.decode("{\"cleared\":null}");
        assertThat(fields).containsKey("cleared");
        assertThat(fields.get("cleared")).isNull();
    }

    @Test
    @DisplayName("nested objects stay objects, so the document can be published again")
    void keepsNestedObjects() {
        Map<String, Object> fields = decoder.decode(
                "{\"id\":\"C-1\",\"order\":{\"symbol\":\"AAPL\",\"qty\":5}}");
        assertThat(fields.get("order")).isInstanceOf(Map.class);
        assertThat(Fields.get(fields, "order.symbol")).isEqualTo("AAPL");
        assertThat(Fields.get(fields, "order.qty")).isEqualTo(5L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsArraysAsLists() {
        Map<String, Object> fields = decoder.decode("{\"legs\":[1,2,3]}");
        assertThat(fields.get("legs")).isInstanceOf(List.class);
        assertThat((List<Object>) fields.get("legs")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void keepsTheDocumentsFieldOrder() {
        assertThat(decoder.decode("{\"c\":1,\"a\":2,\"b\":3}").keySet())
                .containsExactly("c", "a", "b");
    }

    @Test
    void anEmptyPayloadDecodesToNoFields() {
        assertThat(decoder.decode("")).isEmpty();
        assertThat(decoder.decode(null)).isEmpty();
        assertThat(decoder.decode("   ")).isEmpty();
    }

    @Test
    @DisplayName("a payload that is not an object is rejected, not silently emptied")
    void refusesANonObjectRoot() {
        assertThatThrownBy(() -> decoder.decode("[1,2,3]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an object");
        assertThatThrownBy(() -> decoder.decode("\"just a string\""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesMalformedJson() {
        assertThatThrownBy(() -> decoder.decode("{\"id\":"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }
}
