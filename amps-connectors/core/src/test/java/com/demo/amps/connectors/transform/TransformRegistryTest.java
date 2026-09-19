package com.demo.amps.connectors.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.config.TransformStep;
import com.demo.amps.connectors.source.SourceRecord;
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
    void anApplicationWithNoTransformsGetsAnEmptyRegistry() {
        TransformRegistry registry = new TransformRegistry(Map.of());
        assertThat(registry.names()).isEmpty();
        assertThat(registry.resolve(List.of())).isEmpty();
    }
}
