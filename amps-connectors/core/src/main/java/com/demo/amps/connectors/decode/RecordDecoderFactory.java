package com.demo.amps.connectors.decode;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds the {@link RecordDecoder} a connector's {@code format} selects.
 *
 * <p>Static rather than a bean, because a decoder is a pure function of the connector's
 * configuration: there is nothing to inject and nothing to keep. The JSON mapper is shared and
 * left at its defaults -- this decoder only reads trees, so none of jackson's binding settings
 * apply to it.
 */
public final class RecordDecoderFactory {

    /** Shared, and never reconfigured: {@link JsonRecordDecoder} only calls {@code readTree}. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RecordDecoderFactory() {
    }

    /**
     * @param connector the connector configuration
     * @return a decoder for its wire format and field separator
     */
    public static RecordDecoder create(ConnectorProperties connector) {
        return switch (connector.getFormat()) {
            case FIX, NVFIX -> new DelimitedRecordDecoder(connector.getFieldSeparator());
            case JSON -> new JsonRecordDecoder(MAPPER);
            case TEXT -> new TextRecordDecoder();
        };
    }
}
