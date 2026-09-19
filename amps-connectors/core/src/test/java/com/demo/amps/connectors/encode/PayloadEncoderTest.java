package com.demo.amps.connectors.encode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.decode.DelimitedRecordDecoder;
import com.demo.amps.connectors.decode.JsonRecordDecoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayloadEncoderTest {

    private static final char SOH = TestConnectors.SOH;

    private static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            fields.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return fields;
    }

    @Test
    @DisplayName("JSON writes each value in its own type, so a filter on a number works")
    void jsonWritesTypedValues() {
        String payload = new JsonEncoder(new ObjectMapper()).encode(fields(
                "id", "C-1", "qty", 100L, "price", new BigDecimal("185.50"), "live", true,
                "cleared", null));
        assertThat(payload).isEqualTo(
                "{\"id\":\"C-1\",\"qty\":100,\"price\":185.50,\"live\":true,\"cleared\":null}");
    }

    @Test
    void jsonKeepsNestedObjectsAndArrays() {
        String payload = new JsonEncoder(new ObjectMapper()).encode(fields(
                "order", fields("symbol", "AAPL", "qty", 5L), "legs", List.of(1L, 2L)));
        assertThat(payload)
                .isEqualTo("{\"order\":{\"symbol\":\"AAPL\",\"qty\":5},\"legs\":[1,2]}");
    }

    @Test
    @DisplayName("a JSON document survives decode then encode unchanged")
    void jsonRoundTrips() {
        ObjectMapper mapper = new ObjectMapper();
        String original = "{\"id\":\"C-1\",\"order\":{\"symbol\":\"AAPL\",\"price\":185.50},"
                + "\"legs\":[1,2],\"cleared\":null}";
        assertThat(new JsonEncoder(mapper).encode(new JsonRecordDecoder(mapper).decode(original)))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("FIX ends with a separator, the way a real message does")
    void fixWritesTagValuePairsAndTerminates() {
        assertThat(new FixEncoder(SOH).encode(fields("11", "C-1", "55", "AAPL", "38", 100L)))
                .isEqualTo("11=C-1" + SOH + "55=AAPL" + SOH + "38=100" + SOH);
    }

    @Test
    @DisplayName("a FIX repeating group survives decode then encode")
    void fixRoundTripsARepeatingGroup() {
        String original = TestConnectors.delimited(
                "11", "C-1", "55", "AAPL", "38", "100", "55", "MSFT", "38", "200");
        assertThat(new FixEncoder(SOH).encode(new DelimitedRecordDecoder(SOH).decode(original)))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("a non-numeric tag is refused, naming it -- AMPS could never address it")
    void fixRefusesANonNumericTag() {
        assertThatThrownBy(() -> new FixEncoder(SOH).encode(fields("symbol", "AAPL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'symbol'")
                .hasMessageContaining("not a FIX tag number");
    }

    @Test
    void fixWritesANullValueAsEmpty() {
        assertThat(new FixEncoder(SOH).encode(fields("58", null)))
                .isEqualTo("58=" + SOH);
    }

    @Test
    @DisplayName("NVFIX is FIX framing with names, so a renamed field is the point")
    void nvfixWritesNamedFields() {
        assertThat(new NvfixEncoder(SOH).encode(fields("symbol", "AAPL", "qty", 100L)))
                .isEqualTo("symbol=AAPL" + SOH + "qty=100" + SOH);
    }

    @Test
    void nvfixStripsOccurrenceSuffixesToo() {
        assertThat(new NvfixEncoder('|').encode(fields("symbol", "AAPL", "symbol#2", "MSFT")))
                .isEqualTo("symbol=AAPL|symbol=MSFT|");
    }

    @Test
    void theFactoryMapsEachMessageTypeToItsEncoder() {
        assertThat(PayloadEncoderFactory.create("json", SOH)).isInstanceOf(JsonEncoder.class);
        assertThat(PayloadEncoderFactory.create("fix", SOH)).isInstanceOf(FixEncoder.class);
        assertThat(PayloadEncoderFactory.create("nvfix", SOH)).isInstanceOf(NvfixEncoder.class);
        assertThat(PayloadEncoderFactory.create("JSON", SOH)).isInstanceOf(JsonEncoder.class);
    }

    @Test
    void theFactoryRefusesAMessageTypeAmpsDoesNotKnowHere() {
        assertThatThrownBy(() -> PayloadEncoderFactory.create("xml", SOH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("json/fix/nvfix");
    }
}
