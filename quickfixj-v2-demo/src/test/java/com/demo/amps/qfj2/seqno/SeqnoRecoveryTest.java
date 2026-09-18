package com.demo.amps.qfj2.seqno;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.qfj2.seqno.SeqnoRecovery.Applied;
import com.demo.amps.qfj2.seqno.SeqnoRecovery.Decision;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The startup decision, every cell of the matrix, with no server and no QuickFIX/J. */
class SeqnoRecoveryTest {

    private static Optional<SeqnoSnapshot> amps(int sender, int target) {
        return Optional.of(new SeqnoSnapshot("FIX.4.2:A->B", sender, target,
                Instant.parse("2026-09-17T08:00:00Z"), Instant.parse("2026-09-17T09:00:00Z"), "prod-a", 57));
    }

    @Test
    @DisplayName("no checkpoint in AMPS: the file stands, whatever the policy")
    void noCheckpointMeansTheFileStands() {
        for (RecoveryPolicy policy : RecoveryPolicy.values()) {
            Decision decision = SeqnoRecovery.decide(policy, 5, 9, Optional.empty());
            assertThat(decision.applied()).as(policy.name()).isEqualTo(Applied.FILE);
            assertThat(decision.numbers()).isEqualTo("5/9");
            assertThat(decision.rewritesFile(5, 9)).isFalse();
            assertThat(decision.reason()).contains("no checkpoint in AMPS");
        }
    }

    @Test
    @DisplayName("amps-wins on an empty disk: the failover case")
    void ampsWinsOnAnEmptyDisk() {
        Decision decision = SeqnoRecovery.decide(RecoveryPolicy.AMPS_WINS, 1, 1, amps(12, 40));
        assertThat(decision.applied()).isEqualTo(Applied.AMPS);
        assertThat(decision.numbers()).isEqualTo("12/40");
        assertThat(decision.rewritesFile(1, 1)).isTrue();
        assertThat(decision.reason()).contains("replaces the file's 1/1").doesNotContain("AHEAD");
    }

    @Test
    @DisplayName("amps-wins with the file ahead: applied, but the reason warns")
    void ampsWinsWarnsWhenTheFileIsAhead() {
        Decision decision = SeqnoRecovery.decide(RecoveryPolicy.AMPS_WINS, 14, 40, amps(12, 40));
        assertThat(decision.applied()).isEqualTo(Applied.AMPS);
        assertThat(decision.numbers()).isEqualTo("12/40");
        assertThat(decision.reason()).contains("AHEAD").contains("resend request");
    }

    @Test
    @DisplayName("amps-wins when both agree: nothing to rewrite")
    void ampsWinsWhenBothAgree() {
        Decision decision = SeqnoRecovery.decide(RecoveryPolicy.AMPS_WINS, 12, 40, amps(12, 40));
        assertThat(decision.applied()).isEqualTo(Applied.AMPS);
        assertThat(decision.rewritesFile(12, 40)).isFalse();
        assertThat(decision.reason()).contains("agrees");
    }

    @Test
    @DisplayName("file-wins keeps the file even when AMPS is ahead")
    void fileWinsKeepsTheFile() {
        Decision decision = SeqnoRecovery.decide(RecoveryPolicy.FILE_WINS, 5, 6, amps(12, 40));
        assertThat(decision.applied()).isEqualTo(Applied.FILE);
        assertThat(decision.numbers()).isEqualTo("5/6");
        assertThat(decision.reason()).contains("is overwritten");
    }

    @Test
    @DisplayName("highest takes each number from whichever side is further along")
    void highestMergesPerNumber() {
        Decision merged = SeqnoRecovery.decide(RecoveryPolicy.HIGHEST, 20, 6, amps(12, 40));
        assertThat(merged.applied()).isEqualTo(Applied.MERGED);
        assertThat(merged.numbers()).isEqualTo("20/40");

        Decision ampsAhead = SeqnoRecovery.decide(RecoveryPolicy.HIGHEST, 1, 1, amps(12, 40));
        assertThat(ampsAhead.applied()).isEqualTo(Applied.AMPS);
        assertThat(ampsAhead.numbers()).isEqualTo("12/40");

        Decision fileAhead = SeqnoRecovery.decide(RecoveryPolicy.HIGHEST, 30, 50, amps(12, 40));
        assertThat(fileAhead.applied()).isEqualTo(Applied.FILE);
        assertThat(fileAhead.numbers()).isEqualTo("30/50");
    }
}
