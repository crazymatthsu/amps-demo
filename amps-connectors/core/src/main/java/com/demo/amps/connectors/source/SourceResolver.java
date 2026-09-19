package com.demo.amps.connectors.source;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.SourceProperties;
import java.util.List;

/**
 * Picks the {@link RecordSource} for a connector out of the {@link SourceFactory} beans on the
 * classpath.
 *
 * <p>{@code driver: SIMULATED} short-circuits to {@link SimulatedSource} whatever transport is
 * configured -- the block still says what the connector stands in for, and the validator's
 * rules for it still apply, but nothing is dialled. That is what lets the demo stack and the
 * end-to-end tests drive the whole pipeline into a real AMPS with no broker on the other side.
 *
 * <p>Otherwise the first factory that claims the connector builds it. Claiming is a question
 * about configuration -- "is my block under {@code source:} present" -- and the validator has
 * already refused a source that names two blocks, so "first" and "only" are the same factory
 * by the time this runs. Nothing claiming it is the interesting failure, and it is a build
 * problem rather than a configuration one: the block is spelled correctly, its module is just
 * not on the classpath. The message says which one to add.
 */
public final class SourceResolver {

    private final List<SourceFactory> factories;

    public SourceResolver(List<SourceFactory> factories) {
        this.factories = List.copyOf(factories);
    }

    /**
     * @param connector the connector configuration
     * @return a fresh, unstarted source
     * @throws IllegalStateException if no factory on the classpath claims the connector
     */
    public RecordSource resolve(ConnectorProperties connector) {
        SourceProperties source = connector.getSource();
        if (source.getDriver() == SourceProperties.Driver.SIMULATED) {
            return new SimulatedSource(connector);
        }
        for (SourceFactory factory : factories) {
            if (factory.supports(connector)) {
                return factory.create(connector);
            }
        }
        List<String> blocks = source.configuredBlocks();
        throw new IllegalStateException("connector '" + connector.getName()
                + "': no source implementation for "
                + (blocks.isEmpty() ? "an empty source: block" : "source." + String.join("/", blocks))
                + (blocks.size() == 1
                        ? " -- add a dependency on :amps-connectors:source-" + blocks.get(0)
                        : " -- name exactly one transport block and depend on its module"));
    }
}
