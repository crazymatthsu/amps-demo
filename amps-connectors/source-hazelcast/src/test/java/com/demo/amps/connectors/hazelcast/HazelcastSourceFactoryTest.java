package com.demo.amps.connectors.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How {@code serialization-factories} becomes factories on the client: names resolved to the
 * application's beans when the source is built, refused readably when they cannot be, and
 * registered on every client configuration the source dials with. No cluster: what a
 * registered factory does to a value is {@link HazelcastRecordSourceTest}'s business.
 */
class HazelcastSourceFactoryTest {

    private static final String BEAN = "tradeFactory";

    private static ConnectorProperties naming(Map<Integer, String> factories) {
        ConnectorProperties connector = TestConnectors.hazelcastMap("positions", "positions");
        connector.getSource().getHazelcast().setSerializationFactories(new LinkedHashMap<>(factories));
        return connector;
    }

    @Test
    @DisplayName("a named factory is resolved to its bean and registered on the client")
    void resolvesAndRegistersTheNamedFactory() {
        Trades.Factory bean = new Trades.Factory();
        HazelcastSourceFactory factory =
                new HazelcastSourceFactory(name -> BEAN.equals(name) ? bean : null);
        ConnectorProperties connector = naming(Map.of(Trades.FACTORY_ID, BEAN));

        assertThat(factory.resolve(connector)).containsExactly(Map.entry(Trades.FACTORY_ID, bean));

        HazelcastRecordSource source = (HazelcastRecordSource) factory.create(connector);
        // On the client, because that is where a value is deserialized before the listener
        // sees it: a client without the factory cannot deliver the value at all.
        assertThat(source.clientConfig().getSerializationConfig().getDataSerializableFactories())
                .containsExactly(Map.entry(Trades.FACTORY_ID, bean));
    }

    @Test
    @DisplayName("a connector naming no factory needs no resolver, and registers nothing")
    void noFactoriesNoResolution() {
        HazelcastSourceFactory factory = new HazelcastSourceFactory();
        ConnectorProperties connector = TestConnectors.hazelcast("events", "events");

        HazelcastRecordSource source = (HazelcastRecordSource) factory.create(connector);

        assertThat(source.clientConfig().getSerializationConfig().getDataSerializableFactories())
                .isEmpty();
    }

    @Test
    @DisplayName("a name that is not a factory bean fails the build, naming the connector and the id")
    void anUnknownBeanIsRefused() {
        HazelcastSourceFactory factory = new HazelcastSourceFactory(name -> {
            throw new IllegalArgumentException("No bean named '" + name + "' available");
        });
        ConnectorProperties connector = naming(Map.of(Trades.FACTORY_ID, "nobody"));

        assertThatThrownBy(() -> factory.create(connector))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connector 'positions'")
                .hasMessageContaining("serialization-factories[1000]")
                .hasMessageContaining("'nobody'")
                .hasMessageContaining("No bean named 'nobody' available");

        // A resolver that answers null is the same mistake, reported the same way.
        assertThatThrownBy(() -> new HazelcastSourceFactory(name -> null).create(connector))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialization-factories[1000]");
    }

    @Test
    @DisplayName("built without a resolver, the factory refuses a connector that names one")
    void theBareFactoryRefusesNamedFactories() {
        ConnectorProperties connector = naming(Map.of(Trades.FACTORY_ID, BEAN));

        assertThatThrownBy(() -> new HazelcastSourceFactory().create(connector))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without an application context");
    }

    @Test
    @DisplayName("the source itself refuses a connector whose named factory was not supplied")
    void theSourceRefusesAMissingFactory() {
        ConnectorProperties connector = naming(Map.of(Trades.FACTORY_ID, BEAN));

        // A client built without it would fail on the first typed value, on a Hazelcast
        // event thread; better to fail where the connector is built.
        assertThatThrownBy(() -> new HazelcastRecordSource(connector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names factory 1000 ('tradeFactory')");
        assertThatThrownBy(() -> new HazelcastRecordSource(connector, Map.of(2, new Trades.Factory())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names factory 1000");
    }

    @Test
    @DisplayName("the auto-configuration resolves names through the application's own beans")
    void theAutoConfigurationResolvesBeansByName() {
        ConnectorProperties connector = naming(Map.of(Trades.FACTORY_ID, BEAN));
        HazelcastSourceProperties hazelcast = connector.getSource().getHazelcast();
        hazelcast.setTypedValues(HazelcastSourceProperties.TypedValues.OBJECT);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HazelcastSourceAutoConfiguration.class))
                .withUserConfiguration(WithTradeFactory.class)
                .run(context -> {
                    HazelcastSourceFactory factory = context.getBean(HazelcastSourceFactory.class);
                    assertThat(factory.resolve(connector))
                            .containsExactly(Map.entry(Trades.FACTORY_ID,
                                    context.getBean(BEAN, DataSerializableFactory.class)));

                    // A bean of the right name but the wrong type is refused the same way.
                    assertThatThrownBy(() -> factory.resolve(naming(Map.of(1, "notAFactory"))))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("serialization-factories[1]")
                            .hasMessageContaining("'notAFactory'");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class WithTradeFactory {

        @Bean(BEAN)
        DataSerializableFactory tradeFactory() {
            return new Trades.Factory();
        }

        @Bean
        String notAFactory() {
            return "a string";
        }
    }
}
