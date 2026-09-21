package com.demo.amps.connectors.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayloadsTest {

    /** Two characters outside ASCII, so a wrong charset would show. */
    private static final String ACCENTED = "prix: 185,50 € é";

    @Test
    @DisplayName("text: a String as it is, bytes as UTF-8, null as empty, anything else as valueOf")
    void textOfEveryRepresentation() {
        assertThat(Payloads.text("{\"id\":1}")).isEqualTo("{\"id\":1}");
        assertThat(Payloads.text(ACCENTED.getBytes(StandardCharsets.UTF_8))).isEqualTo(ACCENTED);
        assertThat(Payloads.text(null)).isEmpty();
        assertThat(Payloads.text(42L)).isEqualTo("42");
    }

    @Test
    @DisplayName("bytes: an array as it is (not copied), text as UTF-8, null as empty")
    void bytesOfEveryRepresentation() {
        byte[] raw = {1, 2, 3};
        assertThat(Payloads.bytes(raw)).isSameAs(raw);
        assertThat(Payloads.bytes(ACCENTED)).isEqualTo(ACCENTED.getBytes(StandardCharsets.UTF_8));
        assertThat(Payloads.bytes(null)).isEmpty();
        assertThat(Payloads.bytes(7)).isEqualTo("7".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void textAndBytesRoundTrip() {
        String text = "11=ORD-1" + (char) 1 + "55=AAPL" + (char) 1;
        assertThat(Payloads.text(Payloads.bytes(text))).isEqualTo(text);
    }
}
