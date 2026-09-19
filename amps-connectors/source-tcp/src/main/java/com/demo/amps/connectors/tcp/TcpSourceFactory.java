package com.demo.amps.connectors.tcp;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceFactory;

/**
 * Claims the connectors that configure {@code source.tcp}.
 *
 * <p>The presence of the block is the whole test: a connector cannot configure two transports
 * (the validator refuses that), so no further disambiguation is needed or wanted.
 */
public class TcpSourceFactory implements SourceFactory {

    @Override
    public boolean supports(ConnectorProperties connector) {
        return connector.getSource().getTcp() != null;
    }

    @Override
    public RecordSource create(ConnectorProperties connector) {
        return new TcpRecordSource(connector);
    }
}
