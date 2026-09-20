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

class TransformRegistryTest {

    private static final RecordTransform ENRICHER = (record, fields) -> {
        Map<String, Object> result = new LinkedHashMap<>(fields);
        result.put("enriched", true);
        return result;
    };

    private static TransformStep bean(String name) {
        TransformStep step = new TransformStep();
        step.setBean(name);
        return step;
    }

    @Test
    void resolvesABeanStepToItsBean() {
        TransformRegistry registry = new TransformRegistry(Map.of("myEnricher", ENRICHER));
        assertThat(registry.resolve(List.of(bean("myEnricher")))).containsExactly(ENRICHER);
        assertThat(registry.contains("myEnricher")).isTrue();
        assertThat(registry.names()).containsExactly("myEnricher");
    }

    @Test
    @DisplayName("an unknown bean names itself and everything that is registered")
    void refusesAnUnknownBeanAndListsWhatThereIs() {
        TransformRegistry registry = new TransformRegistry(Map.of("myEnricher", ENRICHER));
        assertThatThrownBy(() -> registry.resolve(List.of(bean("typo"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'typo'")
                .hasMessageContaining("myEnricher");
    }

    @Test
    @DisplayName("built-in steps and bean steps resolve to the same kind of thing")
    void resolvesBuiltInStepsToo() {
        TransformStep keep = new TransformStep();
        keep.setKeep(List.of("55"));
        TransformRegistry registry = new TransformRegistry(Map.of("myEnricher", ENRICHER));

        List<RecordTransform> resolved = registry.resolve(List.of(keep, bean("myEnricher")));
        assertThat(resolved).hasSize(2);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("11", "ORD-1");
        fields.put("55", "AAPL");
        TransformChain chain = new TransformChain(resolved);
        assertThat(chain.apply(SourceRecord.of(""), fields))
                .containsExactly(Map.entry("55", "AAPL"), Map.entry("enriched", true));
    }

    @Test
    @DisplayName("a rules step resolves to a RuleSet raising under the context's connector")
    void resolvesRulesStepsWithTheContext() {
        RuleProperties rule = new RuleProperties();
        rule.setName("enriched-buys");
        rule.setWhen("#f['enriched'] == true");
        rule.getThen().setBean("myEnricher");
        RuleAlert alert = new RuleAlert();
        alert.setCode("ENRICHED");
        rule.getThen().setAlert(alert);
        TransformStep rules = new TransformStep();
        rules.setRules(List.of(rule));

        TransformRegistry registry = new TransformRegistry(Map.of("myEnricher", ENRICHER));
        List<Alert> raised = new ArrayList<>();
        List<RecordTransform> resolved = registry.resolve(
                List.of(bean("myEnricher"), rules), new TransformContext("orders", registry, raised::add));
        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0)).isSameAs(ENRICHER);
        assertThat(resolved.get(1)).isInstanceOf(RuleSet.class);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("11", "ORD-1");
        new TransformChain(resolved).apply(SourceRecord.of(""), fields);
        assertThat(raised).singleElement().satisfies(fired -> {
            assertThat(fired.code()).isEqualTo("ENRICHED");
            assertThat(fired.message()).isEqualTo("enriched-buys");
            assertThat(fired.connector()).isEqualTo("orders");
            assertThat(fired.details()).containsEntry("rule", "enriched-buys");
        });

        // The context-less overload compiles the same rules for their own sake.
        assertThat(registry.resolve(List.of(rules))).singleElement().isInstanceOf(RuleSet.class);
        // A rule's bean action is resolved as strictly as a bean step.
        rule.getThen().setBean("typo");
        assertThatThrownBy(() -> registry.resolve(List.of(rules)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'typo'")
                .hasMessageContaining("myEnricher");
        assertThat(registry.require("myEnricher")).isSameAs(ENRICHER);
    }

    /** A bean that wants the connector's name: its bound copy stamps every record with it. */
    private static final class ConnectorAwareEnricher implements RecordTransform {

        final List<String> boundTo = new ArrayList<>();

        @Override
        public Map<String, Object> apply(SourceRecord record, Map<String, Object> fields) {
            return fields;
        }

        @Override
        public RecordTransform bind(TransformContext context) {
            boundTo.add(context.connectorName());
            return (record, fields) -> {
                Map<String, Object> result = new LinkedHashMap<>(fields);
                result.put("connector", context.connectorName());
                return result;
            };
        }
    }

    @Test
    @DisplayName("a bean step is bound to the connector it is resolved for, once per connector")
    void bindsABeanStepToItsConnector() {
        ConnectorAwareEnricher enricher = new ConnectorAwareEnricher();
        TransformRegistry registry = new TransformRegistry(Map.of("aware", enricher));
        TransformContext orders = new TransformContext("orders", registry, null);

        RecordTransform bound = registry.resolve(List.of(bean("aware")), orders).get(0);
        assertThat(bound).isNotSameAs(enricher);
        assertThat(bound.apply(SourceRecord.of("{}"), Map.of("55", "VOD.L")))
                .containsEntry("55", "VOD.L")
                .containsEntry("connector", "orders");
        // The default bind() is the bean itself: a transform that does not care sees nothing.
        assertThat(registry.resolve(List.of(bean("aware")))).isNotSameAs(enricher);
        assertThat(ENRICHER.bind(orders)).isSameAs(ENRICHER);

        // A rule's bean action is bound the same way.
        RuleProperties rule = new RuleProperties();
        rule.setName("tag");
        rule.setWhen("true");
        rule.getThen().setBean("aware");
        TransformStep rules = new TransformStep();
        rules.setRules(List.of(rule));
        RuleSet ruleSet = (RuleSet) registry.resolve(List.of(rules), orders).get(0);
        assertThat(ruleSet.apply(SourceRecord.of("{}"), Map.of("55", "VOD.L")))
                .containsEntry("connector", "orders");
        assertThat(enricher.boundTo).containsExactly("orders", "", "orders");
    }

    @Test
    void anApplicationWithNoTransformsGetsAnEmptyRegistry() {
        TransformRegistry registry = new TransformRegistry(Map.of());
        assertThat(registry.names()).isEmpty();
        assertThat(registry.resolve(List.of())).isEmpty();
    }
}
