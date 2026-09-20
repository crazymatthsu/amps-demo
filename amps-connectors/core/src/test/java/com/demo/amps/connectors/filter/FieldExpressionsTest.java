package com.demo.amps.connectors.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.source.SourceRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;

class FieldExpressionsTest {

    private static final SourceRecord RECORD = SourceRecord.delete("", "ORD-1")
            .withAttributes(Map.of("topic", "orders", "partition", "3"));

    private static Map<String, Object> order() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("35", "D");
        fields.put("11", "ORD-1");
        fields.put("38", "100");
        fields.put("44", 185.5d);
        return fields;
    }

    private static Object eval(String text) {
        return FieldExpressions.evaluate(FieldExpressions.parse(text), text, RECORD, order());
    }

    private static boolean test(String text) {
        return FieldExpressions.test(FieldExpressions.parse(text), text, RECORD, order());
    }

    @Test
    @DisplayName("#f is the field map, and #num/#str coerce its values")
    void bindsTheFieldsAndTheHelpers() {
        assertThat(eval("#f['35']")).isEqualTo("D");
        assertThat(eval("#num(#f['38']) * #num(#f['44'])")).isEqualTo(18550.0d);
        assertThat(eval("#str(#f['99'])")).isEqualTo("");
        assertThat(eval("#num(#f['35'])")).isEqualTo(Double.NaN);
        assertThat(test("#f.containsKey('11') && !#f.containsKey('44x')")).isTrue();
    }

    @Test
    @DisplayName("#r is the record: its key, its action and its transport attributes")
    void bindsTheRecordAsItself() {
        assertThat(eval("#r.key")).isEqualTo("ORD-1");
        assertThat(eval("#r.action")).isEqualTo(SourceRecord.Action.DELETE);
        assertThat(eval("#r.attributes['topic']")).isEqualTo("orders");
        assertThat(eval("#r.attributes['missing']")).isNull();
        assertThat(test("#r.action.name() == 'DELETE'")).isTrue();
        assertThat(test("#r.key == #f['11']")).isTrue();
    }

    @Test
    @DisplayName("the fields-only overloads leave #r unbound, which a filter never notices")
    void theFieldsOnlyOverloadsStillWork() {
        Expression expression = FieldExpressions.parse("#f['35'] == 'D'");
        assertThat(FieldExpressions.test(expression, "t", order())).isTrue();
        assertThat(FieldExpressions.evaluate(expression, "t", order())).isEqualTo(true);
        Expression record = FieldExpressions.parse("#r");
        assertThat(FieldExpressions.evaluate(record, "#r", order())).isNull();
    }

    @Test
    @DisplayName("an evaluation failure names the expression, so the log says what to fix")
    void wrapsEvaluationFailuresWithTheExpressionText() {
        assertThatThrownBy(() -> eval("#f.noSuchMethod()"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[#f.noSuchMethod()]");
        assertThatThrownBy(() -> test("#f['35']"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rather than a boolean")
                .hasMessageContaining("String");
        assertThatThrownBy(() -> test("null"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answered null");
    }

    @Test
    @DisplayName("a template renders #{...} per record and leaves the rest of the text alone")
    void rendersTemplates() {
        String text = "limit order #{#f['11']} on #{#r.attributes['topic']} has no price";
        Expression template = FieldExpressions.parseTemplate(text);
        assertThat(FieldExpressions.render(template, text, RECORD, order()))
                .isEqualTo("limit order ORD-1 on orders has no price");

        String literal = "no expressions here";
        assertThat(FieldExpressions.render(
                FieldExpressions.parseTemplate(literal), literal, RECORD, order()))
                .isEqualTo(literal);

        String nulls = "missing=#{#f['99']}";
        assertThat(FieldExpressions.render(
                FieldExpressions.parseTemplate(nulls), nulls, RECORD, order()))
                .isEqualTo("missing=");
    }

    @Test
    @DisplayName("a template that does not parse fails at parse time, and one that fails names itself")
    void templatesFailLoudly() {
        assertThatThrownBy(() -> FieldExpressions.parseTemplate("open #{#f['11']"))
                .isInstanceOf(ParseException.class);
        String text = "bad #{#f.noSuchMethod()}";
        Expression template = FieldExpressions.parseTemplate(text);
        assertThatThrownBy(() -> FieldExpressions.render(template, text, RECORD, order()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("template [" + text + "]");
    }

    @Test
    void numAndStrAreNullSafe() {
        assertThat(FieldExpressions.num(null)).isNaN();
        assertThat(FieldExpressions.num(" 42 ")).isEqualTo(42.0d);
        assertThat(FieldExpressions.num(7L)).isEqualTo(7.0d);
        assertThat(FieldExpressions.num("x")).isNaN();
        assertThat(FieldExpressions.str(null)).isEmpty();
        assertThat(FieldExpressions.str(12)).isEqualTo("12");
    }
}
