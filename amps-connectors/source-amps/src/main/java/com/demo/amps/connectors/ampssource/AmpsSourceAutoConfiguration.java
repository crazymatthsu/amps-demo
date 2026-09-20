package com.demo.amps.connectors.ampssource;

import com.demo.amps.connectors.ConnectorsAutoConfiguration;
import com.demo.amps.connectors.config.ConnectorsProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Contributes the AMPS driver to any application that depends on this module.
 *
 * <p>An application gets AMPS-as-a-source support by adding the dependency and nothing else:
 * the factory joins the {@code SourceResolver}'s list, and a connector selects it by
 * configuring {@code source.amps}. No {@code @ConditionalOnClass} guard, unlike the other
 * drivers: the AMPS client is core's own dependency and cannot be excluded without taking the
 * publish side with it.
 *
 * <p>Ordered after core's auto-configuration because the factory is handed the bound
 * {@code amps-connectors} properties -- the shared server block is what a connector
 * subscribes to when it names no server of its own.
 */
@AutoConfiguration(after = ConnectorsAutoConfiguration.class)
public class AmpsSourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AmpsSourceFactory ampsSourceFactory(ConnectorsProperties properties) {
        return new AmpsSourceFactory(properties.getAmps());
    }
}
