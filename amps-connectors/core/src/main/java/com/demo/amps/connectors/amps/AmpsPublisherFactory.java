package com.demo.amps.connectors.amps;

import com.demo.amps.connectors.config.ConnectorProperties;

/**
 * Builds the {@link AmpsPublisher} for one connector.
 *
 * <p>The seam that lets a test run the whole flow -- Spring Integration, batching, timers,
 * acknowledgments -- against a recording publisher without an AMPS anywhere. The
 * auto-configuration contributes the real one ({@link HaAmpsPublisher}) behind
 * {@code @ConditionalOnMissingBean}, so a test declaring its own bean replaces it entirely.
 *
 * <p>A connector, not the server block, is the argument: the message type is part of the
 * connection URI, so two connectors on the same AMPS with different message types need two
 * clients.
 */
@FunctionalInterface
public interface AmpsPublisherFactory {

    /**
     * @param connector the connector configuration
     * @return a fresh, unconnected publisher
     */
    AmpsPublisher create(ConnectorProperties connector);
}
