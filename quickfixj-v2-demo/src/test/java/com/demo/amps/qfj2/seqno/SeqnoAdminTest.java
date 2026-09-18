package com.demo.amps.qfj2.seqno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.qfj2.support.InMemorySeqnoReplicator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionID;
import quickfix.SessionSettings;

class SeqnoAdminTest {

    private static final SessionID ID = new SessionID("FIX.4.2", "DROPCOPY", "VENUE");

    @TempDir
    Path dir;

    private final InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();

    private SeqnoAdmin admin() {
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "initiator");
        settings.setString("FileStorePath", dir.resolve("store").toString());
        settings.setString(ID, "BeginString", "FIX.4.2");
        settings.setString(ID, "SenderCompID", "DROPCOPY");
        settings.setString(ID, "TargetCompID", "VENUE");
        return new SeqnoAdmin(settings, replicator, "admin@test");
    }

    @Test
    @DisplayName("show reports both sides absent before anything exists, and creates nothing")
    void showReportsNothingBeforeTheStoreExists() throws Exception {
        SeqnoAdmin admin = admin();
        SeqnoAdmin.State state = admin.show(ID);
        assertThat(state.file()).isEmpty();
        assertThat(state.amps()).isEmpty();
        assertThat(state.inSync()).isFalse();
        assertThat(admin.hasFileStore(ID)).isFalse();
    }

    @Test
    @DisplayName("set-file creates the store and writes the numbers QuickFIX/J will read")
    void setFileCreatesAndWritesTheStore() throws Exception {
        SeqnoAdmin admin = admin();
        SeqnoAdmin.FileNumbers numbers = admin.setFile(ID, OptionalInt.of(100), OptionalInt.of(200));
        assertThat(numbers.numbers()).isEqualTo("100/200");
        assertThat(numbers.senderSeqNumFile())
                .isEqualTo(dir.resolve("store").resolve("FIX.4.2-DROPCOPY-VENUE.senderseqnums"));
        assertThat(Files.isRegularFile(numbers.senderSeqNumFile())).isTrue();
        assertThat(Files.isRegularFile(dir.resolve("store").resolve("FIX.4.2-DROPCOPY-VENUE.targetseqnums"))).isTrue();
        assertThat(admin.show(ID).file()).get().extracting(SeqnoAdmin.FileNumbers::numbers).isEqualTo("100/200");

        // One number at a time leaves the other alone.
        assertThat(admin.setFile(ID, OptionalInt.of(101), OptionalInt.empty()).numbers()).isEqualTo("101/200");
    }

    @Test
    @DisplayName("set-amps publishes a new revision, keeping any number not given")
    void setAmpsKeepsTheOtherNumber() throws Exception {
        replicator.seed(new SeqnoSnapshot(ID.toString(), 12, 40, Instant.parse("2026-09-17T08:00:00Z"),
                Instant.parse("2026-09-17T09:00:00Z"), "prod-a", 57));
        SeqnoSnapshot written = admin().setAmps(ID, OptionalInt.of(100), OptionalInt.empty());
        assertThat(written.numbers()).isEqualTo("100/40");
        assertThat(written.revision()).isEqualTo(58);
        assertThat(written.source()).isEqualTo("admin@test");
        assertThat(written.creationTime()).isEqualTo(Instant.parse("2026-09-17T08:00:00Z"));
        assertThat(replicator.latest(ID.toString())).contains(written);
    }

    @Test
    @DisplayName("set-amps with no checkpoint yet starts from the file, or from 1/1")
    void setAmpsWithoutACheckpointStartsFromTheFile() throws Exception {
        SeqnoAdmin admin = admin();
        assertThat(admin.setAmps(ID, OptionalInt.empty(), OptionalInt.of(9)).numbers()).isEqualTo("1/9");
        replicator.clear();
        admin.setFile(ID, OptionalInt.of(7), OptionalInt.of(8));
        assertThat(admin.setAmps(ID, OptionalInt.of(70), OptionalInt.empty()).numbers()).isEqualTo("70/8");
    }

    @Test
    @DisplayName("file-to-amps and amps-to-file copy the numbers across")
    void copiesInBothDirections() throws Exception {
        SeqnoAdmin admin = admin();
        admin.setFile(ID, OptionalInt.of(7), OptionalInt.of(8));
        assertThat(admin.fileToAmps(ID).numbers()).isEqualTo("7/8");
        assertThat(admin.show(ID).inSync()).isTrue();

        admin.setAmps(ID, OptionalInt.of(9), OptionalInt.of(10));
        assertThat(admin.show(ID).inSync()).isFalse();
        assertThat(admin.ampsToFile(ID).numbers()).isEqualTo("9/10");
        assertThat(admin.show(ID).inSync()).isTrue();
    }

    @Test
    @DisplayName("sessions are found by their printed id, and a wrong one lists the right ones")
    void findsSessionsByPrintedId() {
        SeqnoAdmin admin = admin();
        assertThat(admin.sessions()).containsExactly(ID);
        assertThat(admin.session("FIX.4.2:DROPCOPY->VENUE")).isEqualTo(ID);
        assertThatThrownBy(() -> admin.session("FIX.4.2:X->Y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FIX.4.2:DROPCOPY->VENUE");
    }
}
