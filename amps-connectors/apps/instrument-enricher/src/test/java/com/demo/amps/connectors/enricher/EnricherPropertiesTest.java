package com.demo.amps.connectors.enricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The {@code enricher:} block binds the way the README says it does -- in particular that
 * the two maps are <em>merged</em> over the defaults, which is Spring Boot's rule for a map
 * property with an existing value and the reason a default entry is switched off by
 * blanking it rather than by leaving it out.
 */
class EnricherPropertiesTest {

    private static EnricherProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("enricher", Bindable.ofInstance(new EnricherProperties()))
                .orElseGet(EnricherProperties::new);
    }

    @Test
    @DisplayName("the defaults are the FIX SEDOL convention: 48 and 15 from the row, 22=2, pass on a miss")
    void defaults() {
        EnricherProperties properties = new EnricherProperties();
        assertThat(properties.getResource()).isEqualTo("instruments");
        assertThat(properties.getSymbolTag()).isEqualTo("55");
        assertThat(properties.getSet()).containsExactly(entry("48", "sedol"), entry("15", "currency"));
        assertThat(properties.getLiterals()).containsExactly(entry("22", "2"));
        assertThat(properties.getOnMiss()).isEqualTo(EnricherProperties.OnMiss.PASS);
    }

    @Test
    @DisplayName("a configured entry is merged over the defaults, and a blank one is how a default is switched off")
    void mapsMergeOverTheDefaults() {
        EnricherProperties properties = bind(Map.of(
                "enricher.set.5002", "ric",
                "enricher.set.15", "",
                "enricher.literals.5003", "refdata",
                "enricher.on-miss", "DROP",
                "enricher.resource", "instruments-eu"));

        assertThat(properties.getSet())
                .containsEntry("48", "sedol")        // the default, kept
                .containsEntry("15", "")             // the default, blanked: the enricher skips it
                .containsEntry("5002", "ric");       // the addition
        assertThat(properties.getLiterals())
                .containsEntry("22", "2")
                .containsEntry("5003", "refdata");
        assertThat(properties.getOnMiss()).isEqualTo(EnricherProperties.OnMiss.DROP);
        assertThat(properties.getResource()).isEqualTo("instruments-eu");
    }
}
