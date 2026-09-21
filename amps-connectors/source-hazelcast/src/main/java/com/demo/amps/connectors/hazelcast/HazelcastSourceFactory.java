package com.demo.amps.connectors.hazelcast;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceFactory;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Claims the connectors that configure {@code source.hazelcast}.
 *
 * <p>The presence of the block is the whole test: a connector cannot configure two transports
 * (the validator refuses that), so no further disambiguation is needed or wanted.
 *
 * <p>What the factory adds to {@code new HazelcastRecordSource(connector)} is the
 * application's beans: {@code serialization-factories} names {@code DataSerializableFactory}
 * beans by factory id, and this is where a name becomes a bean -- through the resolver the
 * auto-configuration hands over, which is the bean factory. A name nobody registered fails the
 * connector's start with the bean factory's own message, prefixed with the connector and the
 * factory id it was written under.
 */
public class HazelcastSourceFactory implements SourceFactory {

    private final Function<String, DataSerializableFactory> beans;

    /**
     * A factory with no application context behind it: fine for every connector that names no
     * serialization factory, and an error at {@link #create} for one that does.
     */
    public HazelcastSourceFactory() {
        this(name -> {
            throw new IllegalStateException("no bean named '" + name + "' can be resolved: this "
                    + "HazelcastSourceFactory was built without an application context");
        });
    }

    /**
     * @param beans resolves a bean name to the {@code DataSerializableFactory} registered
     *     under it; expected to throw, readably, for a name that is not one
     */
    public HazelcastSourceFactory(Function<String, DataSerializableFactory> beans) {
        this.beans = Objects.requireNonNull(beans, "beans");
    }

    @Override
    public boolean supports(ConnectorProperties connector) {
        return connector.getSource().getHazelcast() != null;
    }

    @Override
    public RecordSource create(ConnectorProperties connector) {
        return new HazelcastRecordSource(connector, resolve(connector));
    }

    /**
     * The connector's {@code serialization-factories}, each name resolved to its bean.
     *
     * <p>Package-private so a test can assert the resolution without a cluster.
     *
     * @param connector the connector configuration
     * @return the factories by factory id, in configuration order
     * @throws IllegalStateException if a name does not resolve, naming the connector and the id
     */
    Map<Integer, DataSerializableFactory> resolve(ConnectorProperties connector) {
        Map<Integer, DataSerializableFactory> resolved = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> named
                : connector.getSource().getHazelcast().getSerializationFactories().entrySet()) {
            try {
                resolved.put(named.getKey(), Objects.requireNonNull(beans.apply(named.getValue()),
                        "the resolver returned null"));
            } catch (RuntimeException e) {
                throw new IllegalStateException("connector '" + connector.getName()
                        + "': source.hazelcast.serialization-factories[" + named.getKey()
                        + "] names bean '" + named.getValue() + "', which is not a "
                        + "DataSerializableFactory the application registered: " + e.getMessage(),
                        e);
            }
        }
        return resolved;
    }
}
