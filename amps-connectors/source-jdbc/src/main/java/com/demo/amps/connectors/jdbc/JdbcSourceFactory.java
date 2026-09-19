package com.demo.amps.connectors.jdbc;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceFactory;

/**
 * Claims the connectors that configure {@code source.jdbc}.
 *
 * <p>The presence of the block is the whole test: a connector cannot configure two transports
 * (the validator refuses that), so no further disambiguation is needed or wanted.
 */
public class JdbcSourceFactory implements SourceFactory {

    @Override
    public boolean supports(ConnectorProperties connector) {
        return connector.getSource().getJdbc() != null;
    }

    @Override
    public RecordSource create(ConnectorProperties connector) {
        return new JdbcRecordSource(connector);
    }
}
