package com.demo.amps.connectors.ampssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.config.AmpsServerProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.SourceProperties;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SimulatedSource;
import com.demo.amps.connectors.source.SourceResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The factory claims exactly the connectors that configure {@code source.amps}. */
class AmpsSourceFactoryTest {

    private final AmpsServerProperties defaults = new AmpsServerProperties();
    private final AmpsSourceFactory factory = new AmpsSourceFactory(defaults);

    @Test
    @DisplayName("supports a connector with a source.amps block and no other")
    void supportsTheAmpsBlock() {
        assertThat(factory.supports(AmpsTestConnectors.amps("bridge", "sow/orders"))).isTrue();
        assertThat(factory.supports(TestConnectors.kafka("k", "orders", "g"))).isFalse();
        assertThat(factory.supports(TestConnectors.tcp("t", 5001))).isFalse();
        assertThat(factory.supports(TestConnectors.jdbc("j", "jdbc:h2:mem:x", "SELECT 1"))).isFalse();
        assertThat(factory.supports(TestConnectors.hazelcast("h", "events"))).isFalse();
        assertThat(factory.supports(TestConnectors.simulated("s"))).isFalse();
    }

    @Test
    @DisplayName("creates an AMPS source dialling the shared server block")
    void createsAnAmpsSource() {
        defaults.setHost("amps-1");
        ConnectorProperties connector = AmpsTestConnectors.amps("bridge", "sow/orders");

        try (RecordSource source = factory.create(connector)) {
            assertThat(source).isInstanceOf(AmpsRecordSource.class);
            assertThat(((AmpsRecordSource) source).uri()).isEqualTo("tcp://amps-1:9007/amps/json");
            assertThat(source.isConnected()).isFalse();
        }
    }

    @Test
    @DisplayName("the resolver picks it for source.amps, and the simulator still wins the driver switch")
    void resolvesThroughTheResolver() {
        SourceResolver resolver = new SourceResolver(List.of(factory));
        ConnectorProperties bridge = AmpsTestConnectors.amps("bridge", "sow/orders");

        try (RecordSource source = resolver.resolve(bridge)) {
            assertThat(source).isInstanceOf(AmpsRecordSource.class);
        }
        bridge.getSource().setDriver(SourceProperties.Driver.SIMULATED);
        try (RecordSource source = resolver.resolve(bridge)) {
            assertThat(source).isInstanceOf(SimulatedSource.class);
        }
        // Without the driver on the classpath the resolver names the module to add.
        assertThatThrownBy(() -> new SourceResolver(List.of()).resolve(
                AmpsTestConnectors.amps("bridge", "sow/orders")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source.amps")
                .hasMessageContaining(":amps-connectors:source-amps");
    }

    @Test
    @DisplayName("the shared server block is not optional")
    void requiresTheDefaults() {
        assertThatThrownBy(() -> new AmpsSourceFactory(null))
                .isInstanceOf(NullPointerException.class);
    }
}
