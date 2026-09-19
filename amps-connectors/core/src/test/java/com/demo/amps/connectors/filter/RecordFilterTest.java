package com.demo.amps.connectors.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.config.FilterProperties;
import com.demo.amps.connectors.config.FilterRule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordFilterTest {

    private static Map<String, Object> order() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("35", "D");
        fields.put("11", "ORD-1");
        fields.put("55", "AAPL");
        fields.put("38", "100");
        fields.put("cleared", null);
        return fields;
    }

    private static FilterRule rule(String field) {
        FilterRule rule = new FilterRule();
        rule.setField(field);
        return rule;
    }

    private static RecordFilter filter(FilterProperties.Match match, FilterRule... rules) {
        FilterProperties properties = new FilterProperties();
        properties.setMatch(match);
        properties.setRules(List.of(rules));
        return new RecordFilter(properties);
    }

    private static RecordFilter filter(FilterRule... rules) {
        return filter(FilterProperties.Match.ALL, rules);
    }

    @Test
    void equalsComparesTheValueAsText() {
        FilterRule rule = rule("35");
        rule.setEquals("D");
        assertThat(filter(rule).accepts(order())).isTrue();
        rule.setEquals("G");
        assertThat(filter(rule).accepts(order())).isFalse();
    }

    @Test
    @DisplayName("not-equals is also true when the field is absent")
    void notEqualsIsTrueForAnAbsentField() {
        FilterRule rule = rule("99");
        rule.setNotEquals("D");
        assertThat(filter(rule).accepts(order())).isTrue();
    }

    @Test
    void inIsStringMembership() {
        FilterRule rule = rule("35");
        rule.setIn(List.of("D", "G", "F"));
        assertThat(filter(rule).accepts(order())).isTrue();
        rule.setIn(List.of("8", "9"));
        assertThat(filter(rule).accepts(order())).isFalse();
    }

    @Test
    @DisplayName("matches applies to the whole value, not a substring of it")
    void matchesIsAnchored() {
        FilterRule rule = rule("55");
        rule.setMatches("^[A-Z]{1,5}$");
        assertThat(filter(rule).accepts(order())).isTrue();
        rule.setMatches("AAP");
        assertThat(filter(rule).accepts(order())).isFalse();
    }

    @Test
    void theFourNumericComparisons() {
        FilterRule gt = rule("38");
        gt.setGt("99");
        assertThat(filter(gt).accepts(order())).isTrue();
        FilterRule gte = rule("38");
        gte.setGte("100");
        assertThat(filter(gte).accepts(order())).isTrue();
        FilterRule lt = rule("38");
        lt.setLt("100");
        assertThat(filter(lt).accepts(order())).isFalse();
        FilterRule lte = rule("38");
        lte.setLte("100");
        assertThat(filter(lte).accepts(order())).isTrue();
    }

    @Test
    @DisplayName("a non-numeric or absent field makes a numeric rule false, never an error")
    void aNonNumericFieldMakesANumericRuleFalse() {
        FilterRule gt = rule("55");
        gt.setGt("0");
        assertThat(filter(gt).accepts(order())).isFalse();
        FilterRule lt = rule("55");
        lt.setLt("0");
        assertThat(filter(lt).accepts(order())).isFalse();
        FilterRule absent = rule("999");
        absent.setGt("0");
        assertThat(filter(absent).accepts(order())).isFalse();
    }

    @Test
    @DisplayName("present asks about the key, so an explicit null is present")
    void presentAsksAboutTheKey() {
        FilterRule present = rule("cleared");
        present.setPresent(true);
        assertThat(filter(present).accepts(order())).isTrue();
        FilterRule absent = rule("999");
        absent.setPresent(false);
        assertThat(filter(absent).accepts(order())).isTrue();
    }

    @Test
    void allRequiresEveryRuleAndAnyRequiresOne() {
        FilterRule right = rule("35");
        right.setEquals("D");
        FilterRule wrong = rule("55");
        wrong.setEquals("MSFT");
        assertThat(filter(FilterProperties.Match.ALL, right, wrong).accepts(order())).isFalse();
        assertThat(filter(FilterProperties.Match.ANY, right, wrong).accepts(order())).isTrue();
        assertThat(filter(FilterProperties.Match.ANY, wrong).accepts(order())).isFalse();
    }

    @Test
    void anEmptyRuleListAcceptsEverything() {
        assertThat(filter().accepts(order())).isTrue();
        assertThat(filter(FilterProperties.Match.ANY).accepts(order())).isTrue();
    }

    @Test
    @DisplayName("a dotted path reaches into a nested object")
    void rulesReadDottedPaths() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("price", 185.5d);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("order", order);
        FilterRule rule = rule("order.price");
        rule.setGt("100");
        assertThat(filter(rule).accepts(fields)).isTrue();
    }

    @Test
    @DisplayName("the expression sees #f, #num and #str")
    void evaluatesTheSpelExpression() {
        FilterProperties properties = new FilterProperties();
        properties.setExpression("#f['35'] == 'D' && #num(#f['38']) > 50");
        assertThat(new RecordFilter(properties).accepts(order())).isTrue();

        properties.setExpression("#num(#f['38']) > 1000");
        assertThat(new RecordFilter(properties).accepts(order())).isFalse();

        properties.setExpression("#str(#f['55']).startsWith('AA')");
        assertThat(new RecordFilter(properties).accepts(order())).isTrue();
    }

    @Test
    @DisplayName("#str of an absent field is empty, so an expression needs no null check")
    void strIsNullSafe() {
        FilterProperties properties = new FilterProperties();
        properties.setExpression("#str(#f['999']).isEmpty()");
        assertThat(new RecordFilter(properties).accepts(order())).isTrue();
    }

    @Test
    @DisplayName("rules and expression are ANDed, not alternatives")
    void rulesAndExpressionBothHaveToPass() {
        FilterProperties properties = new FilterProperties();
        FilterRule rule = rule("35");
        rule.setEquals("D");
        properties.setRules(List.of(rule));
        properties.setExpression("#num(#f['38']) > 1000");
        assertThat(new RecordFilter(properties).accepts(order())).isFalse();
    }

    @Test
    @DisplayName("an expression that is not a predicate is a configuration mistake, so it throws")
    void refusesANonBooleanExpressionResult() {
        FilterProperties properties = new FilterProperties();
        properties.setExpression("#f['35']");
        assertThatThrownBy(() -> new RecordFilter(properties).accepts(order()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rather than a boolean");
    }

    @Test
    void anExpressionThatFailsThrowsWithItsText() {
        FilterProperties properties = new FilterProperties();
        properties.setExpression("#f['35'].nosuchmethod()");
        assertThatThrownBy(() -> new RecordFilter(properties).accepts(order()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nosuchmethod");
    }

    @Test
    void refusesARuleThatNamesNoOperatorOrSeveral() {
        assertThatThrownBy(() -> filter(rule("35")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no operator");
        FilterRule twice = rule("35");
        twice.setEquals("D");
        twice.setIn(List.of("D"));
        assertThatThrownBy(() -> filter(twice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void refusesANumericOperandThatIsNotANumber() {
        FilterRule rule = rule("38");
        rule.setGt("lots");
        assertThatThrownBy(() -> filter(rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a number");
    }
}
