package com.demo.amps.qfj2.seqno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeqnoJsonTest {

    private static final SeqnoSnapshot SNAPSHOT = new SeqnoSnapshot("FIX.4.2:DROPCOPY->VENUE", 12, 40,
            Instant.parse("2026-09-17T08:00:00Z"), Instant.parse("2026-09-17T08:14:03.117Z"), "host-a", 57);

    @Test
    @DisplayName("a checkpoint round-trips through its JSON form")
    void roundTrips() {
        String json = SeqnoJson.encode(SNAPSHOT);
        assertThat(json)
                .contains("\"sessionId\":\"FIX.4.2:DROPCOPY->VENUE\"")
                .contains("\"nextSenderMsgSeqNum\":12")
                .contains("\"nextTargetMsgSeqNum\":40")
                .contains("\"revision\":57")
                // Gson's default HTML escaping would turn the arrow into >.
                .doesNotContain("\\u003e");
        assertThat(SeqnoJson.decode(json)).isEqualTo(SNAPSHOT);
    }

    @Test
    @DisplayName("a record without the numbers is refused by name")
    void refusesARecordWithoutNumbers() {
        assertThatThrownBy(() -> SeqnoJson.decode("{\"sessionId\":\"FIX.4.2:A->B\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextSenderMsgSeqNum");
    }

    @Test
    @DisplayName("non-JSON is refused")
    void refusesNonJson() {
        assertThatThrownBy(() -> SeqnoJson.decode("8=FIX.4.235=A"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("source and revision are optional on the way in")
    void toleratesMissingOptionalFields() {
        SeqnoSnapshot decoded = SeqnoJson.decode("{\"sessionId\":\"FIX.4.2:A->B\",\"nextSenderMsgSeqNum\":3,"
                + "\"nextTargetMsgSeqNum\":4,\"creationTime\":\"2026-09-17T08:00:00Z\","
                + "\"updatedAt\":\"2026-09-17T08:00:01Z\"}");
        assertThat(decoded.source()).isEmpty();
        assertThat(decoded.revision()).isZero();
        assertThat(decoded.numbers()).isEqualTo("3/4");
    }

    @Test
    @DisplayName("FIX sequence numbers start at 1")
    void snapshotRejectsZeroSequenceNumbers() {
        assertThatThrownBy(() -> new SeqnoSnapshot("FIX.4.2:A->B", 0, 1, Instant.now(), Instant.now(), "x", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start at 1");
        assertThatThrownBy(() -> new SeqnoSnapshot(" ", 1, 1, Instant.now(), Instant.now(), "x", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
