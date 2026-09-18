package com.demo.amps.qfj2.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.qfj2.seqno.SeqnoAdmin;
import com.demo.amps.qfj2.support.InMemorySeqnoReplicator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import quickfix.SessionID;
import quickfix.SessionSettings;

class SeqnoAdminRunnerTest {

    private static final SessionID ID = new SessionID("FIX.4.2", "DROPCOPY", "VENUE");

    @TempDir
    Path dir;

    private final InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private SeqnoAdmin admin() {
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "initiator");
        settings.setString("FileStorePath", dir.resolve("store").toString());
        settings.setString(ID, "BeginString", "FIX.4.2");
        settings.setString(ID, "SenderCompID", "DROPCOPY");
        settings.setString(ID, "TargetCompID", "VENUE");
        return new SeqnoAdmin(settings, replicator, "admin@test");
    }

    private int run(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i + 1 < properties.length; i += 2) {
            environment.setProperty(properties[i], properties[i + 1]);
        }
        SeqnoAdminRunner runner = new SeqnoAdminRunner(admin(), environment,
                new PrintStream(output, true, StandardCharsets.UTF_8));
        runner.run(new DefaultApplicationArguments());
        return runner.getExitCode();
    }

    private String printed() {
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("set-file, file-to-amps, then show reports both sides in sync")
    void setThenCopyThenShow() {
        assertThat(run("seqno.action", "set-file", "seqno.sender", "100", "seqno.target", "200")).isZero();
        assertThat(printed()).contains("file -> 100/200");

        assertThat(run("seqno.action", "file-to-amps")).isZero();
        assertThat(printed()).contains("file 100/200 -> AMPS");

        assertThat(run("seqno.action", "show")).isZero();
        assertThat(printed()).contains("FIX.4.2:DROPCOPY->VENUE").contains("in sync").contains("admin@test");
    }

    @Test
    @DisplayName("set-amps then amps-to-file, limited to one session")
    void setAmpsThenAmpsToFile() {
        assertThat(run("seqno.action", "set-amps", "seqno.sender", "7", "seqno.target", "8",
                "seqno.session", "FIX.4.2:DROPCOPY->VENUE")).isZero();
        assertThat(run("seqno.action", "amps-to-file", "seqno.session", "FIX.4.2:DROPCOPY->VENUE")).isZero();
        assertThat(printed()).contains("AMPS 7/8 -> file");
    }

    @Test
    @DisplayName("bad usage exits 2, an error exits 1")
    void exitCodes() {
        assertThat(run("seqno.action", "explode")).isEqualTo(2);
        assertThat(printed()).contains("unknown action 'explode'");
        assertThat(run("seqno.action", "set-file")).isEqualTo(1);
        assertThat(printed()).contains("--seqno.sender=N");
        assertThat(run("seqno.action", "show", "seqno.session", "FIX.4.2:NO->PE")).isEqualTo(1);
        assertThat(printed()).contains("no session 'FIX.4.2:NO->PE'");
    }
}
