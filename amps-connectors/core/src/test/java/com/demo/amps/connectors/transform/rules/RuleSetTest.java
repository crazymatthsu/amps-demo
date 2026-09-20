package com.demo.amps.connectors.transform.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.config.RuleAlert;
import com.demo.amps.connectors.config.RuleProperties;
import com.demo.amps.connectors.config.RuleThen;
import com.demo.amps.connectors.source.SourceRecord;
import com.demo.amps.connectors.transform.RecordTransform;
import com.demo.amps.connectors.transform.TransformContext;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.ParseException;

class RuleSetTest {

    private static final SourceRecord RECORD = SourceRecord.of("", "ORD-1")
            .withAttributes(Map.of("topic", "orders"));

    private static final RecordTransform TAGGER = (record, fields) -> {
        Map<String, Object> result = new LinkedHashMap<>(fields);
        result.put("tagged", true);
        return result;
    };

    private final List<Alert> raised = new ArrayList<>();
    private final TransformRegistry registry = new TransformRegistry(
            Map.of("tagger", TAGGER, "dropper", (record, fields) -> null));
    private final TransformContext context =
            new TransformContext("orders-enriched", registry, raised::add);

    private static Map<String, Object> limitOrder() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("11", "ORD-1");
        fields.put("40", "2");
        fields.put("38", "2000");
        fields.put("44", "600");
        return fields;
    }

    private static RuleProperties rule(String name, String when) {
        RuleProperties rule = new RuleProperties();
        rule.setName(name);
        rule.setWhen(when);
        return rule;
    }

    private static RuleProperties setting(String name, String when, String field, String value) {
        RuleProperties rule = rule(name, when);
        rule.getThen().setSet(Map.of(field, value));
        return rule;
    }

    private static RuleProperties alerting(String name, String when, String code, String message) {
        RuleProperties rule = rule(name, when);
        RuleAlert alert = new RuleAlert();
        alert.setCode(code);
        alert.setMessage(message);
        rule.getThen().setAlert(alert);
        return rule;
    }

    private static RuleProperties dropping(String name, String when) {
        RuleProperties rule = rule(name, when);
        rule.getThen().setDrop(true);
        return rule;
    }

    private RuleSet compile(RuleProperties... rules) {
        return RuleSet.compile(List.of(rules), context);
    }

    @Test
    @DisplayName("a rule that hits counts, and one that does not leaves the record alone")
    void countsHitsPerRule() {
        RuleSet rules = compile(
                setting("large", "#num(#f['38']) * #num(#f['44']) > 1000000", "5001", "LARGE"),
                setting("never", "#f['40'] == '9'", "5002", "X"));

        Map<String, Object> result = rules.apply(RECORD, limitOrder());
        assertThat(result).containsEntry("5001", "LARGE").doesNotContainKey("5002");
        rules.apply(RECORD, limitOrder());

        assertThat(rules.rules()).containsExactly(
                new RuleSet.RuleStats("large", 2), new RuleSet.RuleStats("never", 0));
        assertThat(rules.summary()).isEqualTo("rules[large=2,never=0]");
        assertThat(rules.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a set by an earlier rule is a fact a later rule can test")
    void setIsVisibleToLaterRules() {
        RuleSet rules = compile(
                setting("first", "true", "flag", "on"),
                setting("second", "#f['flag'] == 'on'", "seen", "yes"));
        assertThat(rules.apply(RECORD, limitOrder())).containsEntry("seen", "yes");
        assertThat(rules.summary()).isEqualTo("rules[first=1,second=1]");
    }

    @Test
    @DisplayName("stop ends the step after a hit; a rule that misses does not stop anything")
    void stopEndsEvaluationAfterAHit() {
        RuleProperties first = setting("first", "true", "a", "1");
        first.setStop(true);
        RuleSet rules = compile(first, setting("second", "true", "b", "2"));
        assertThat(rules.apply(RECORD, limitOrder())).containsEntry("a", "1").doesNotContainKey("b");
        assertThat(rules.summary()).isEqualTo("rules[first=1,second=0]");

        RuleProperties miss = setting("miss", "false", "a", "1");
        miss.setStop(true);
        RuleSet stopping = compile(miss, setting("second", "true", "b", "2"));
        assertThat(stopping.apply(RECORD, limitOrder())).containsEntry("b", "2");
    }

    @Test
    @DisplayName("the actions run set, bean, alert, drop -- and the alert fires even when dropping")
    void runsTheActionsInOrderAndAlertsBeforeDropping() {
        RuleProperties rule = rule("limit-without-price", "#f['40'] == '2' && !#f.containsKey('44')");
        rule.getThen().setSet(Map.of("reason", "no-price"));
        rule.getThen().setBean("tagger");
        RuleAlert alert = new RuleAlert();
        alert.setSeverity(Alert.Severity.ERROR);
        alert.setCode("LIMIT_WITHOUT_PRICE");
        alert.setMessage("limit order #{#f['11']} has no price (#{#f['reason']}, tagged=#{#f['tagged']})");
        rule.getThen().setAlert(alert);
        rule.getThen().setDrop(true);
        RuleSet rules = compile(rule);

        Map<String, Object> order = limitOrder();
        order.remove("44");
        assertThat(rules.apply(RECORD, order)).isNull();

        assertThat(raised).hasSize(1);
        Alert fired = raised.get(0);
        assertThat(fired.severity()).isEqualTo(Alert.Severity.ERROR);
        assertThat(fired.code()).isEqualTo("LIMIT_WITHOUT_PRICE");
        assertThat(fired.connector()).isEqualTo("orders-enriched");
        // The message saw the set AND the bean's field: set, then bean, then alert.
        assertThat(fired.message()).isEqualTo(
                "limit order ORD-1 has no price (no-price, tagged=true)");
        assertThat(fired.details()).containsExactly(Map.entry("rule", "limit-without-price"));
        assertThat(rules.summary()).isEqualTo("rules[limit-without-price=1]");
    }

    @Test
    @DisplayName("drop alone discards the record quietly, and is counted by the rule")
    void dropDiscardsTheRecord() {
        RuleSet rules = compile(
                dropping("cancels", "#f['40'] == '2'"), setting("after", "true", "x", "y"));
        assertThat(rules.apply(RECORD, limitOrder())).isNull();
        assertThat(raised).isEmpty();
        assertThat(rules.summary()).isEqualTo("rules[cancels=1,after=0]");
    }

    @Test
    @DisplayName("without drop the record carries on with what set and bean did to it")
    void keepsTheRecordWhenNotDropping() {
        RuleProperties rule = rule("tag", "true");
        rule.getThen().setBean("tagger");
        rule.getThen().setSet(Map.of("5001", "LARGE"));
        RuleSet rules = compile(rule);
        Map<String, Object> result = rules.apply(RECORD, limitOrder());
        assertThat(result).containsEntry("5001", "LARGE").containsEntry("tagged", true);
        assertThat(raised).isEmpty();
    }

    @Test
    @DisplayName("a bean that returns null drops the record, and the alert still fires")
    void aDroppingBeanStillAlerts() {
        RuleProperties rule = alerting("dropped-by-bean", "true", "DROPPED", "gone: #{#f['11']}");
        rule.getThen().setBean("dropper");
        RuleSet rules = compile(rule, setting("after", "true", "never", "reached"));
        assertThat(rules.apply(RECORD, limitOrder())).isNull();
        assertThat(raised).extracting(Alert::message).containsExactly("gone: ORD-1");
        assertThat(rules.summary()).isEqualTo("rules[dropped-by-bean=1,after=0]");
    }

    @Test
    @DisplayName("#r in when sees the record: its key, action and attributes")
    void whenCanTestTheRecord() {
        RuleSet rules = compile(
                setting("by-key", "#r.key == 'ORD-1' && #r.attributes['topic'] == 'orders'", "k", "1"),
                setting("deletes", "#r.action.name() == 'DELETE'", "d", "1"));
        assertThat(rules.apply(RECORD, limitOrder())).containsEntry("k", "1").doesNotContainKey("d");
        assertThat(rules.apply(SourceRecord.delete("", "ORD-1"), limitOrder()))
                .containsEntry("d", "1").doesNotContainKey("k");
    }

    @Test
    @DisplayName("a plain message is a literal, and an absent message is the rule's name")
    void messagesDefaultSensibly() {
        RuleSet rules = compile(
                alerting("literal", "true", "A", "no template here"),
                alerting("unnamed", "true", "B", null));
        rules.apply(RECORD, limitOrder());
        assertThat(raised).extracting(Alert::message).containsExactly("no template here", "unnamed");
        assertThat(raised).extracting(Alert::severity)
                .containsExactly(Alert.Severity.WARN, Alert.Severity.WARN);
    }

    @Test
    @DisplayName("a when that fails to evaluate is a rejection, like a derive that fails")
    void evaluationFailuresAreIllegalArguments() {
        RuleSet rules = compile(setting("broken", "#f.noSuchMethod()", "a", "1"));
        assertThatThrownBy(() -> rules.apply(RECORD, limitOrder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[#f.noSuchMethod()]");

        RuleSet notBoolean = compile(setting("string", "#f['11']", "a", "1"));
        assertThatThrownBy(() -> notBoolean.apply(RECORD, limitOrder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rather than a boolean");

        RuleSet badTemplate = compile(alerting("t", "true", "C", "#{#f.noSuchMethod()}"));
        assertThatThrownBy(() -> badTemplate.apply(RECORD, limitOrder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("template");
        assertThat(raised).isEmpty();
    }

    @Test
    void theInputMapIsNeverMutated() {
        Map<String, Object> order = limitOrder();
        RuleProperties rule = setting("set", "true", "5001", "LARGE");
        rule.getThen().setBean("tagger");
        compile(rule).apply(RECORD, order);
        assertThat(order).isEqualTo(limitOrder());

        // No hit: the record is returned as it came, untouched.
        assertThat(compile(setting("miss", "false", "x", "y")).apply(RECORD, order))
                .isEqualTo(limitOrder());
    }

    @Test
    @DisplayName("a context with no connector raises unattributed alerts, and none() raises nowhere")
    void aBareContextStillWorks() {
        List<Alert> bare = new ArrayList<>();
        RuleSet rules = RuleSet.compile(
                List.of(alerting("a", "true", "A", "m")),
                new TransformContext(null, registry, bare::add));
        rules.apply(RECORD, limitOrder());
        assertThat(bare).singleElement().satisfies(alert -> assertThat(alert.connector()).isNull());

        RuleSet silent = RuleSet.compile(
                List.of(alerting("a", "true", "A", "m")), TransformContext.of(registry));
        assertThat(silent.apply(RECORD, limitOrder())).isEqualTo(limitOrder());
    }

    @Test
    @DisplayName("what is wrong with a rule is wrong at compile time")
    void refusesMalformedRulesAtCompileTime() {
        assertThatThrownBy(() -> RuleSet.compile(List.of(), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no rules");
        assertThatThrownBy(() -> RuleSet.compile(null, context))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> compile(setting(" ", "true", "a", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no name");
        assertThatThrownBy(() -> compile(
                setting("twice", "true", "a", "1"), setting("twice", "true", "b", "2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate rule name 'twice'");

        assertThatThrownBy(() -> compile(setting("no-when", " ", "a", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no when");
        assertThatThrownBy(() -> compile(setting("bad-when", "#f['11' ==", "a", "1")))
                .isInstanceOf(ParseException.class);

        assertThatThrownBy(() -> compile(rule("nothing", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no action");

        assertThatThrownBy(() -> compile(alerting("no-code", "true", " ", "m")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a code");
        assertThatThrownBy(() -> compile(alerting("bad-template", "true", "X", "open #{#f['11']")))
                .isInstanceOf(ParseException.class);

        RuleProperties unknownBean = rule("bean", "true");
        unknownBean.getThen().setBean("typo");
        assertThatThrownBy(() -> compile(unknownBean))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'typo'")
                .hasMessageContaining("tagger");
        RuleProperties blankBean = rule("bean", "true");
        blankBean.getThen().setBean(" ");
        assertThatThrownBy(() -> compile(blankBean))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank bean");
    }

    @Test
    void configuredActionsListsWhatAThenDoes() {
        RuleThen then = new RuleThen();
        assertThat(then.configuredActions()).isEmpty();
        then.setDrop(true);
        then.setAlert(new RuleAlert());
        then.setBean("tagger");
        then.setSet(Map.of("a", "1"));
        assertThat(then.configuredActions()).containsExactly("set", "bean", "alert", "drop");
    }
}
