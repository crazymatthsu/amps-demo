package com.demo.amps.connectors.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.config.KeyProperties;
import com.demo.amps.connectors.source.SourceRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeyExtractorTest {

    private static Map<String, Object> order() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("11", "ORD-1");
        fields.put("55", "AAPL");
        return fields;
    }

    private static KeyExtractor keys(KeyProperties.Mode mode, String... fields) {
        KeyProperties properties = new KeyProperties();
        properties.setMode(mode);
        properties.setFields(List.of(fields));
        return new KeyExtractor(properties);
    }

    @Test
    @DisplayName("PUBLISHER joins the key fields with the separator")
    void publisherJoinsTheKeyFields() {
        assertThat(keys(KeyProperties.Mode.PUBLISHER, "11", "55")
                .key(SourceRecord.of("x"), order()))
                .isEqualTo("ORD-1|AAPL");
    }

    @Test
    @DisplayName("PUBLISHER with no fields falls back to the source's own key")
    void publisherFallsBackToTheSourceKey() {
        assertThat(keys(KeyProperties.Mode.PUBLISHER).key(SourceRecord.of("x", "K-7"), order()))
                .isEqualTo("K-7");
    }

    @Test
    @DisplayName("a record with no determinable key is refused, never published unkeyed")
    void publisherRefusesARecordWithNoKey() {
        assertThatThrownBy(() -> keys(KeyProperties.Mode.PUBLISHER)
                .key(SourceRecord.of("x"), order()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no key");
        assertThatThrownBy(() -> keys(KeyProperties.Mode.PUBLISHER, "11", "99")
                .key(SourceRecord.of("x"), order()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'99'");
    }

    @Test
    @DisplayName("SERVER sends no key: its job is to check the payload carries the fields")
    void serverChecksRatherThanBuilds() {
        assertThat(keys(KeyProperties.Mode.SERVER, "11").key(SourceRecord.of("x"), order()))
                .isNull();
        assertThatThrownBy(() -> keys(KeyProperties.Mode.SERVER, "11", "99")
                .key(SourceRecord.of("x"), order()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'99'")
                .hasMessageContaining("<Key>");
    }

    @Test
    void theDeleteFilterNamesEveryKeyField() {
        assertThat(keys(KeyProperties.Mode.SERVER, "11", "55").deleteFilter(order()))
                .isEqualTo("/11 = 'ORD-1' AND /55 = 'AAPL'");
    }

    @Test
    @DisplayName("a single quote in a value is doubled, the way AMPS' filter syntax escapes it")
    void theDeleteFilterEscapesSingleQuotes() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("11", "O'Brien");
        assertThat(keys(KeyProperties.Mode.SERVER, "11").deleteFilter(fields))
                .isEqualTo("/11 = 'O''Brien'");
    }

    @Test
    @DisplayName("a delete that carries none of the key fields cannot be addressed")
    void theDeleteFilterIsNullWhenAKeyFieldIsAbsent() {
        assertThat(keys(KeyProperties.Mode.SERVER, "11", "99").deleteFilter(order())).isNull();
        assertThat(keys(KeyProperties.Mode.SERVER).deleteFilter(order())).isNull();
    }

    @Test
    void reportsItsModeAndWhetherItHasFields() {
        assertThat(keys(KeyProperties.Mode.SERVER, "11").mode())
                .isEqualTo(KeyProperties.Mode.SERVER);
        assertThat(keys(KeyProperties.Mode.PUBLISHER, "11").hasFields()).isTrue();
        assertThat(keys(KeyProperties.Mode.PUBLISHER).hasFields()).isFalse();
    }
}
