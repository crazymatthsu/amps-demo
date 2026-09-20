package com.demo.amps.connectors.resource.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.config.JdbcResourceProperties;
import com.demo.amps.connectors.config.ResourceProperties;
import com.demo.amps.connectors.resource.AppResource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcResourceFactoryTest {

    private final JdbcResourceFactory factory = new JdbcResourceFactory();

    private static ResourceProperties entry(String name, boolean jdbc) {
        ResourceProperties resource = new ResourceProperties();
        resource.setName(name);
        if (jdbc) {
            JdbcResourceProperties block = new JdbcResourceProperties();
            block.setUrl("jdbc:h2:mem:factory");
            block.setQuery("SELECT symbol, sedol FROM instruments");
            block.setKeyColumns(List.of("symbol"));
            resource.setJdbc(block);
        }
        return resource;
    }

    @Test
    @DisplayName("the jdbc block is what the factory claims")
    void supportsTheJdbcBlockOnly() {
        assertThat(factory.supports(entry("instruments", true))).isTrue();
        assertThat(factory.supports(entry("rics", false))).isFalse();
    }

    @Test
    @DisplayName("create() builds an unstarted lookup table named after the entry")
    void createsALookupTableNamedAfterTheEntry() {
        AppResource resource = factory.create(entry("instruments", true), Alerts.none());

        assertThat(resource).isInstanceOf(JdbcLookupTable.class);
        assertThat(resource.name()).isEqualTo("instruments");
        assertThat(resource.isAvailable()).as("nothing loads until start()").isFalse();
        assertThat(resource.isReloadable()).isTrue();
    }
}
