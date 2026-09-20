package com.demo.amps.connectors.enricher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * A connector application with one piece of code in it: FIX orders from Kafka, enriched
 * with the instrument's SEDOL and currency from a database table, published to AMPS.
 *
 * <p>Everything a config-only application has, this has -- the same auto-configuration,
 * the same lifecycle, the same image recipe -- plus the {@code instrumentEnricher} bean from
 * {@link EnricherConfiguration} and its {@link EnricherProperties}. The connectors, the
 * resource and the control and alert channels all arrive as configuration
 * ({@code config/local/streams/instrument-enricher/}); the baked {@code application.yml}
 * defines none of them, so the bare application boots idle, as the generic runner does.
 *
 * <p>The scan is confined to this package on purpose: the framework contributes its beans
 * through {@code AutoConfiguration.imports}, not through scanning, which is what keeps an
 * application's own package the only place its own beans can come from.
 */
@SpringBootApplication
@EnableConfigurationProperties(EnricherProperties.class)
public class InstrumentEnricherApplication {

    public static void main(String[] args) {
        SpringApplication.run(InstrumentEnricherApplication.class, args);
    }
}
