package com.demo.amps.connectors.ampssource;

import com.demo.amps.connectors.config.AmpsServerProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceFactory;
import java.util.Objects;

/**
 * Claims the connectors that configure {@code source.amps}.
 *
 * <p>The presence of the block is the whole test: a connector cannot configure two transports
 * (the validator refuses that), so no further disambiguation is needed or wanted.
 *
 * <p>Unlike the other drivers this one is built with the application's shared server block,
 * because a connector that reads AMPS usually reads the same instance it publishes into and
 * should not have to spell the address twice: {@code source.amps.server} overrides it only
 * for a bridge from somewhere else.
 */
public class AmpsSourceFactory implements SourceFactory {

    private final AmpsServerProperties defaults;

    /**
     * @param defaults the application's {@code amps-connectors.amps} block -- the server a
     *     connector subscribes to when {@code source.amps.server} is absent, and the owner of
     *     the client-name prefix either way
     */
    public AmpsSourceFactory(AmpsServerProperties defaults) {
        this.defaults = Objects.requireNonNull(defaults, "defaults");
    }

    @Override
    public boolean supports(ConnectorProperties connector) {
        return connector.getSource().getAmps() != null;
    }

    @Override
    public RecordSource create(ConnectorProperties connector) {
        return new AmpsRecordSource(connector, defaults);
    }
}
