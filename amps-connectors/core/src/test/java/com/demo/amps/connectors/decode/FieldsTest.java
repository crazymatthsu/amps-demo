package com.demo.amps.connectors.decode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FieldsTest {

    private static Map<String, Object> nested() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("price", 185.5d);
        order.put("symbol", "AAPL");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", "C-1");
        fields.put("order", order);
        return fields;
    }

    @Test
    void readsAFlatField() {
        assertThat(Fields.get(nested(), "id")).isEqualTo("C-1");
    }

    @Test
    @DisplayName("a dotted path walks into a nested object")
    void readsADottedPath() {
        assertThat(Fields.get(nested(), "order.price")).isEqualTo(185.5d);
        assertThat(Fields.get(nested(), "order.symbol")).isEqualTo("AAPL");
    }

    @Test
    void anAbsentPathReadsAsNull() {
        assertThat(Fields.get(nested(), "order.quantity")).isNull();
        assertThat(Fields.get(nested(), "missing.deeper")).isNull();
        assertThat(Fields.get(nested(), "id.deeper")).isNull();
    }

    @Test
    @DisplayName("a literal key containing dots wins over the walk")
    void prefersALiteralKeyOverWalking() {
        Map<String, Object> fields = new LinkedHashMap<>(nested());
        fields.put("order.price", "literal");
        assertThat(Fields.get(fields, "order.price")).isEqualTo("literal");
    }

    @Test
    @DisplayName("presence is a different question from having a value")
    void tellsPresenceApartFromNull() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("cleared", null);
        assertThat(Fields.contains(fields, "cleared")).isTrue();
        assertThat(Fields.get(fields, "cleared")).isNull();
        assertThat(Fields.contains(fields, "absent")).isFalse();
    }

    @Test
    void createsTheNestingADottedPutNames() {
        Map<String, Object> fields = new LinkedHashMap<>();
        Fields.put(fields, "a.b.c", 42L);
        assertThat(Fields.get(fields, "a.b.c")).isEqualTo(42L);
        assertThat(fields).containsOnlyKeys("a");
    }

    @Test
    @DisplayName("overwriting a field keeps its position in wire order")
    void overwritesInPlace() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("one", 1L);
        fields.put("two", 2L);
        fields.put("three", 3L);
        Fields.put(fields, "one", 99L);
        assertThat(fields.keySet()).containsExactly("one", "two", "three");
        assertThat(fields).containsEntry("one", 99L);
    }

    @Test
    void removesFlatAndNestedFields() {
        Map<String, Object> fields = nested();
        assertThat(Fields.remove(fields, "order.price")).isEqualTo(185.5d);
        assertThat(Fields.get(fields, "order.price")).isNull();
        assertThat(Fields.remove(fields, "id")).isEqualTo("C-1");
        assertThat(Fields.remove(fields, "nothing")).isNull();
    }

    @Test
    @DisplayName("#n suffixes name the later occurrences of a repeated field")
    void findsEveryOccurrenceOfARepeatedField() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("55", "AAPL");
        fields.put("38", "100");
        fields.put("55#2", "MSFT");
        fields.put("55#3", "IBM");
        assertThat(Fields.occurrenceKeys(fields, "55")).containsExactly("55", "55#2", "55#3");
        assertThat(Fields.occurrenceKeys(fields, "38")).containsExactly("38");
        assertThat(Fields.occurrenceKeys(fields, "44")).isEmpty();
    }

    @Test
    void stripsTheOccurrenceSuffixFromAKey() {
        assertThat(Fields.baseName("55#3")).isEqualTo("55");
        assertThat(Fields.baseName("55")).isEqualTo("55");
    }

    @Test
    void rendersValuesAsNullSafeText() {
        assertThat(Fields.text(null)).isNull();
        assertThat(Fields.text(42L)).isEqualTo("42");
        assertThat(Fields.text("x")).isEqualTo("x");
    }
}
