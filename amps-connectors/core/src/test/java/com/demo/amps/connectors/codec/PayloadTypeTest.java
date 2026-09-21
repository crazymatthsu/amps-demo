package com.demo.amps.connectors.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayloadTypeTest {

    @Test
    @DisplayName("0/0 is UNSET: the text default, and not a codec")
    void zeroZeroIsUnset() {
        assertThat(PayloadType.UNSET.factoryId()).isZero();
        assertThat(PayloadType.UNSET.classId()).isZero();
        assertThat(PayloadType.UNSET.isSet()).isFalse();
        assertThat(PayloadType.of(0, 0)).isSameAs(PayloadType.UNSET);
        assertThat(new PayloadType(0, 0)).isEqualTo(PayloadType.UNSET);
    }

    @Test
    void aSetPairNamesACodecAndPrintsAsFactorySlashClass() {
        PayloadType type = PayloadType.of(100, 1);
        assertThat(type.isSet()).isTrue();
        assertThat(type.factoryId()).isEqualTo(100);
        assertThat(type.classId()).isEqualTo(1);
        assertThat(type).hasToString("100/1");
        assertThat(PayloadType.UNSET).hasToString("0/0");
        assertThat(type).isEqualTo(new PayloadType(100, 1)).isNotEqualTo(PayloadType.of(100, 2));
    }

    @Test
    @DisplayName("a half-set pair names nothing, and a negative id is not an id")
    void refusesHalfSetAndNegativePairs() {
        assertThatThrownBy(() -> PayloadType.of(0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0/5")
                .hasMessageContaining("half-set");
        assertThatThrownBy(() -> PayloadType.of(5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5/0");
        assertThatThrownBy(() -> PayloadType.of(-1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> PayloadType.of(1, -1))
                .isInstanceOf(IllegalArgumentException.class);
        // The canonical constructor is no back door.
        assertThatThrownBy(() -> new PayloadType(0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayloadType(-3, -3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
