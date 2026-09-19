package com.demo.amps.connectors.decode;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.SourceFormat;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordDecoderFactoryTest {

    private static ConnectorProperties connector(SourceFormat format) {
        return TestConnectors.withFormat(TestConnectors.simulated("decoders"), format);
    }

    @Test
    void jsonGetsTheJsonDecoder() {
        assertThat(RecordDecoderFactory.create(connector(SourceFormat.JSON)))
                .isInstanceOf(JsonRecordDecoder.class);
    }

    @Test
    @DisplayName("FIX and NVFIX share the delimited decoder -- they differ only in the tag")
    void fixAndNvfixShareTheDelimitedDecoder() {
        assertThat(RecordDecoderFactory.create(connector(SourceFormat.FIX)))
                .isInstanceOf(DelimitedRecordDecoder.class);
        assertThat(RecordDecoderFactory.create(connector(SourceFormat.NVFIX)))
                .isInstanceOf(DelimitedRecordDecoder.class);
    }

    @Test
    void textDecodesTheWholeLineIntoOneField() {
        RecordDecoder decoder = RecordDecoderFactory.create(connector(SourceFormat.TEXT));
        assertThat(decoder).isInstanceOf(TextRecordDecoder.class);
        assertThat(decoder.decode("2026-09-18 something happened"))
                .containsExactly(Map.entry("text", "2026-09-18 something happened"));
    }

    @Test
    @DisplayName("a pipe-delimited feed is read with the connector's own separator")
    void usesTheConfiguredFieldSeparator() {
        ConnectorProperties connector = connector(SourceFormat.FIX);
        connector.setFieldSeparator('|');
        assertThat(RecordDecoderFactory.create(connector).decode("11=C-1|55=AAPL|"))
                .containsExactly(Map.entry("11", "C-1"), Map.entry("55", "AAPL"));
    }
}
