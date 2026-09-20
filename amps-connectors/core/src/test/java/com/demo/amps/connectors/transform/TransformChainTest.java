package com.demo.amps.connectors.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.config.RuleAlert;
import com.demo.amps.connectors.config.RuleProperties;
import com.demo.amps.connectors.config.TransformStep;
import com.demo.amps.connectors.source.SourceRecord;
import com.demo.amps.connectors.transform.rules.RuleSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransformChainTest {

    private static final SourceRecord RECORD = SourceRecord.of("", "C-1");

    private static Map<String, Object> order() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("11", "ORD-1");
        fields.put("55", "AAPL");
        fields.put("54", "1");
        fields.put("38", "100");
        fields.put("44", "185.5");
        return fields;
    }

    private static Map<String, Object> apply(TransformStep step, Map<String, Object> fields) {
        return TransformChain.compile(step).apply(RECORD, fields);
    }

    @Test
    @DisplayName("keep is a projection, in the order it names")
    void keepProjectsInTheOrderListed() {
        TransformStep step = new TransformStep();
        step.setKeep(List.of("55", "11"));
        Map<String, Object> result = apply(step, order());
        assertThat(result.keySet()).containsExactly("55", "11");
    }

    @Test
    @DisplayName("keeping a repeated field keeps every occurrence of it")
    void keepKeepsEveryOccurrence() {
        Map<String, Object> fields = order();
        fields.put("55#2", "MSFT");
        TransformStep step = new TransformStep();
        step.setKeep(List.of("55"));
        assertThat(apply(step, fields).keySet()).containsExactly("55", "55#2");
    }

    @Test
    void dropRemovesTheNamedFieldsAndTheirOccurrences() {
        Map<String, Object> fields = order();
        fields.put("55#2", "MSFT");
        TransformStep step = new TransformStep();
        step.setDrop(List.of("55", "44"));
        assertThat(apply(step, fields).keySet()).containsExactly("11", "54", "38");
    }

    @Test
    @DisplayName("a renamed field keeps its position in wire order")
    void renameHappensInPlace() {
        TransformStep step = new TransformStep();
        step.setRename(Map.of("55", "symbol"));
        assertThat(apply(step, order()).keySet())
                .containsExactly("11", "symbol", "54", "38", "44");
    }

    @Test
    @DisplayName("renaming a repeated field renames every occurrence, suffix kept")
    void renameCarriesTheOccurrenceSuffix() {
        Map<String, Object> fields = order();
        fields.put("55#2", "MSFT");
        TransformStep step = new TransformStep();
        step.setRename(Map.of("55", "symbol"));
        Map<String, Object> result = apply(step, fields);
        assertThat(result).containsEntry("symbol", "AAPL").containsEntry("symbol#2", "MSFT");
    }

    @Test
    void renameCanMoveAFieldIntoANestedObject() {
        TransformStep step = new TransformStep();
        step.setRename(Map.of("55", "order.symbol"));
        Map<String, Object> result = apply(step, order());
        assertThat(result).doesNotContainKey("55");
        assertThat(result.get("order")).isInstanceOf(Map.class);
    }

    @Test
    void setWritesLiteralValues() {
        TransformStep step = new TransformStep();
        step.setSet(Map.of("source", "kafka"));
        assertThat(apply(step, order())).containsEntry("source", "kafka");
    }

    @Test
    @DisplayName("values rewrites a listed code and passes an unlisted one through")
    void valuesRewritesKnownCodesOnly() {
        TransformStep step = new TransformStep();
        step.setValues(Map.of("54", Map.of("1", "BUY", "2", "SELL")));
        assertThat(apply(step, order())).containsEntry("54", "BUY");

        Map<String, Object> unknown = order();
        unknown.put("54", "7");
        assertThat(apply(step, unknown)).containsEntry("54", "7");
    }

    @Test
    @DisplayName("derive stores the expression's own type, so a number stays a number")
    void deriveKeepsTheComputedType() {
        TransformStep step = new TransformStep();
        step.setDerive(Map.of("notional", "#num(#f['38']) * #num(#f['44'])"));
        Map<String, Object> result = apply(step, order());
        assertThat(result.get("notional")).isInstanceOf(Double.class);
        assertThat((Double) result.get("notional")).isEqualTo(18550.0d);
    }

    @Test
    @DisplayName("derive sees the record as #r, so a source key or a topic can become a field")
    void deriveSeesTheRecord() {
        TransformStep step = new TransformStep();
        step.setDerive(Map.of("sourceKey", "#r.key", "origin", "#r.attributes['topic']"));
        Map<String, Object> result = TransformChain.compile(step).apply(
                RECORD.withAttributes(Map.of("topic", "orders")), order());
        assertThat(result).containsEntry("sourceKey", "C-1").containsEntry("origin", "orders");
    }

    @Test
    void theInputMapIsNeverMutated() {
        Map<String, Object> fields = order();
        TransformStep step = new TransformStep();
        step.setDrop(List.of("55"));
        apply(step, fields);
        assertThat(fields).containsKey("55");
    }

    @Test
    @DisplayName("steps run in order, and the fold stops at the first that drops the record")
    void foldsInOrderAndStopsAtTheFirstDrop() {
        TransformStep rename = new TransformStep();
        rename.setRename(Map.of("55", "symbol"));
        TransformStep keep = new TransformStep();
        keep.setKeep(List.of("symbol", "11"));

        TransformChain chain = new TransformChain(List.of(
                TransformChain.compile(rename), TransformChain.compile(keep)));
        assertThat(chain.apply(RECORD, order()).keySet()).containsExactly("symbol", "11");

        TransformChain dropping = new TransformChain(List.of(
                TransformChain.compile(rename),
                (record, fields) -> null,
                (record, fields) -> {
                    throw new AssertionError("a step after a drop must not run");
                }));
        assertThat(dropping.apply(RECORD, order())).isNull();
    }

    @Test
    void anEmptyChainIsTheIdentityAndSaysSo() {
        TransformChain chain = new TransformChain(List.of());
        assertThat(chain.isEmpty()).isTrue();
        assertThat(chain.size()).isZero();
        assertThat(chain.apply(RECORD, order())).isEqualTo(order());
    }

    @Test
    @DisplayName("a rules step compiles through the registry with a context, never as a built-in")
    void rulesStepsNeedAContext() {
        RuleProperties rule = new RuleProperties();
        rule.setName("flag-buys");
        rule.setWhen("#f['54'] == '1'");
        rule.getThen().setSet(Map.of("side", "BUY"));
        RuleAlert alert = new RuleAlert();
        alert.setCode("BUY_SEEN");
        alert.setMessage("buy #{#f['11']}");
        rule.getThen().setAlert(alert);
        TransformStep rules = new TransformStep();
        rules.setRules(List.of(rule));
        TransformStep keep = new TransformStep();
        keep.setKeep(List.of("11", "side"));

        assertThatThrownBy(() -> TransformChain.compile(rules))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules step")
                .hasMessageContaining("TransformRegistry.resolve");

        List<Alert> raised = new ArrayList<>();
        TransformContext context = new TransformContext(
                "orders", new TransformRegistry(Map.of()), raised::add);
        TransformChain chain = TransformChain.of(List.of(rules, keep), context);
        assertThat(chain.size()).isEqualTo(2);
        assertThat(chain.ruleSets()).hasSize(1);
        assertThat(chain.apply(RECORD, order()))
                .containsExactly(Map.entry("11", "ORD-1"), Map.entry("side", "BUY"));
        assertThat(chain.ruleSets().get(0).summary()).isEqualTo("rules[flag-buys=1]");
        assertThat(raised).singleElement().satisfies(fired -> {
            assertThat(fired.code()).isEqualTo("BUY_SEEN");
            assertThat(fired.message()).isEqualTo("buy ORD-1");
            assertThat(fired.connector()).isEqualTo("orders");
        });

        // The registry-only overload still compiles rules; they just raise nowhere.
        TransformChain bare = TransformChain.of(List.of(rules), new TransformRegistry(Map.of()));
        assertThat(bare.ruleSets()).singleElement().isInstanceOf(RuleSet.class);
        assertThat(bare.apply(RECORD, order())).containsEntry("side", "BUY");
        assertThat(new TransformChain(List.of()).ruleSets()).isEmpty();
    }

    @Test
    @DisplayName("a bean step is the registry's to resolve, not compile's")
    void beanStepsAreNotBuiltIns() {
        TransformStep bean = new TransformStep();
        bean.setBean("myEnricher");
        assertThatThrownBy(() -> TransformChain.compile(bean))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bean step");
    }

    @Test
    void refusesAStepThatNamesNoKindOrSeveral() {
        assertThatThrownBy(() -> TransformChain.compile(new TransformStep()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no kind");
        TransformStep twice = new TransformStep();
        twice.setKeep(List.of("55"));
        twice.setDrop(List.of("11"));
        assertThatThrownBy(() -> TransformChain.compile(twice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one kind");
    }
}
