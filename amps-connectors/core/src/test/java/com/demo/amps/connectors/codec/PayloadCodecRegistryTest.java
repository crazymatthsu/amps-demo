package com.demo.amps.connectors.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.decode.RecordDecoder;
import com.demo.amps.connectors.encode.PayloadEncoder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayloadCodecRegistryTest {

    private final TestPojoCodec pojo = new TestPojoCodec();

    /** A codec of one type with whichever sides the test gives it. */
    private static PayloadCodec codec(
            PayloadType type, RecordDecoder decoder, PayloadEncoder encoder) {
        return new PayloadCodec() {
            @Override
            public PayloadType type() {
                return type;
            }

            @Override
            public RecordDecoder decoder() {
                return decoder;
            }

            @Override
            public PayloadEncoder encoder() {
                return encoder;
            }
        };
    }

    @Test
    @DisplayName("the empty registry knows no type, and says so naming the type asked for")
    void emptyRegistryKnowsNothing() {
        PayloadCodecRegistry empty = PayloadCodecRegistry.empty();
        assertThat(empty.types()).isEmpty();
        assertThat(empty.canDecode(TestPojoCodec.TYPE)).isFalse();
        assertThat(empty.canEncode(TestPojoCodec.TYPE)).isFalse();
        assertThatThrownBy(() -> empty.decoder(PayloadType.of(7, 7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7/7")
                .hasMessageContaining("registered: []");
        assertThatThrownBy(() -> empty.encoder(PayloadType.of(7, 7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encodes payload type 7/7");
    }

    @Test
    void resolvesARegisteredCodecBothWays() {
        PayloadCodecRegistry registry = new PayloadCodecRegistry(List.of(pojo));
        assertThat(registry.types()).containsExactly(TestPojoCodec.TYPE);
        assertThat(registry.canDecode(TestPojoCodec.TYPE)).isTrue();
        assertThat(registry.canEncode(TestPojoCodec.TYPE)).isTrue();

        Map<String, Object> fields = registry.decoder(TestPojoCodec.TYPE)
                .decode(new TestPojoCodec.Order("O-1", 100, "185.50"));
        assertThat(fields).isInstanceOf(FieldView.class);
        assertThat(fields.get("id")).isEqualTo("O-1");
        assertThat(registry.encoder(TestPojoCodec.TYPE)).isNotNull();
        assertThat(registry).hasToString("PayloadCodecRegistry[100/1]");
    }

    @Test
    @DisplayName("an unknown set type names itself and the registered ones")
    void anUnknownTypeIsAnErrorNamingWhatIsRegistered() {
        PayloadCodecRegistry registry = new PayloadCodecRegistry(List.of(pojo));
        assertThatThrownBy(() -> registry.decoder(PayloadType.of(100, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("100/2")
                .hasMessageContaining("registered: [100/1]");
    }

    @Test
    @DisplayName("two codecs claiming one type are refused: a record could not know which decodes it")
    void refusesADuplicateType() {
        assertThatThrownBy(() -> new PayloadCodecRegistry(List.of(pojo, new TestPojoCodec())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100/1")
                .hasMessageContaining("claimed by both");
    }

    @Test
    @DisplayName("the text default is not a codec, so a codec claiming 0/0 is refused")
    void refusesTheUnsetType() {
        PayloadCodec text = codec(PayloadType.UNSET, payload -> Map.of(), fields -> "");
        assertThatThrownBy(() -> new PayloadCodecRegistry(List.of(text)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0/0");
        PayloadCodec untyped = codec(null, payload -> Map.of(), fields -> "");
        assertThatThrownBy(() -> new PayloadCodecRegistry(List.of(untyped)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a codec with one side answers for that side only")
    void aOneSidedCodecIsHalfRegistered() {
        PayloadType type = PayloadType.of(200, 3);
        PayloadCodecRegistry readOnly =
                new PayloadCodecRegistry(List.of(codec(type, payload -> Map.of(), null)));
        assertThat(readOnly.canDecode(type)).isTrue();
        assertThat(readOnly.canEncode(type)).isFalse();
        assertThat(readOnly.decoder(type)).isNotNull();
        assertThatThrownBy(() -> readOnly.encoder(type))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("200/3");
        assertThat(readOnly.types()).containsExactly(type);
    }
}
