package com.demo.amps.qfj2.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.qfj2.flow.DestinationSpec;
import com.demo.amps.qfj2.flow.DestinationType;
import com.demo.amps.qfj2.flow.RuleSpec;
import com.demo.amps.qfj2.seqno.RecoveryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The startup validation, on hand-built inputs. */
class QfjPropertiesTest {

    @TempDir
    Path dir;

    private Path settingsFile;

    @BeforeEach
    void settingsFile() throws Exception {
        settingsFile = dir.resolve("quickfixj.cfg");
        Files.writeString(settingsFile, "[default]\nConnectionType=initiator\n");
    }

    private QfjProperties properties(String ampsUri, String seqnoUri, boolean seqnoEnabled,
                                     List<RuleSpec> rules, List<DestinationSpec> destinations) {
        return new QfjProperties("test",
                new QfjProperties.Engine(settingsFile.toString(), false, false),
                new QfjProperties.Amps(ampsUri, "c", 1000, 1000, 1000, true),
                new QfjProperties.Seqno(seqnoEnabled, seqnoUri, "sow/quickfixj/seqno", "s", 1000,
                        RecoveryPolicy.AMPS_WINS, true, 0, 1000, ""),
                new QfjProperties.Flow(QfjProperties.ChannelMode.DIRECT, false, true, rules, destinations),
                new QfjProperties.Mock(false, 1000, 0, List.of("AAPL")));
    }

    private static DestinationSpec amps(String name, String topic) {
        return new DestinationSpec(name, DestinationType.AMPS, topic, null, List.of());
    }

    private static DestinationSpec fix(String name, String session) {
        return new DestinationSpec(name, DestinationType.FIX, null, session, List.of());
    }

    @Test
    @DisplayName("a sound configuration passes")
    void soundConfigurationPasses() {
        assertThatCode(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/json", true,
                List.of(new RuleSpec("set-tag", 5001, null, null, "X", List.of())),
                List.of(amps("blotter", "t"), fix("down", "FIX.4.2:A->B"))).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the settings file must exist")
    void settingsFileMustExist() throws Exception {
        Files.delete(settingsFile);
        assertThatThrownBy(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/json", true,
                List.of(), List.of()).validate())
                .hasMessageContaining("not a file");
    }

    @Test
    @DisplayName("an AMPS destination needs a connection on the fix message type")
    void ampsDestinationNeedsFixUri() {
        assertThatThrownBy(() -> properties("tcp://h:9007/amps/json", "tcp://h:9007/amps/json", true,
                List.of(), List.of(amps("blotter", "t"))).validate())
                .hasMessageContaining("/amps/fix");
        // Without an AMPS destination the fix URI is never opened, so it is not checked.
        assertThatCode(() -> properties("tcp://h:9007/amps/json", "tcp://h:9007/amps/json", true,
                List.of(), List.of()).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the checkpoint connection needs the json message type, when enabled")
    void seqnoNeedsJsonUri() {
        assertThatThrownBy(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/fix", true,
                List.of(), List.of()).validate())
                .hasMessageContaining("/amps/json");
        assertThatCode(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/fix", false,
                List.of(), List.of()).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rules are checked with their index")
    void rulesAreChecked() {
        assertThatThrownBy(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/json", true,
                List.of(new RuleSpec("no-such-rule", 1, null, null, null, List.of())), List.of()).validate())
                .hasMessageContaining("qfj.flow.rules[0]")
                .hasMessageContaining("no-such-rule");
    }

    @Test
    @DisplayName("destinations need unique names, a type, and the parameter their type needs")
    void destinationsAreChecked() {
        assertThatThrownBy(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/json", true,
                List.of(), List.of(amps("x", "t"), amps("x", "u"))).validate())
                .hasMessageContaining("duplicate destination name 'x'");
        assertThatThrownBy(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/json", true,
                List.of(), List.of(amps("x", " "))).validate())
                .hasMessageContaining("needs a topic");
        assertThatThrownBy(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/json", true,
                List.of(), List.of(fix("x", null))).validate())
                .hasMessageContaining("needs a session");
        assertThatThrownBy(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/json", true,
                List.of(), List.of(fix("x", "not-a-session-id"))).validate())
                .hasMessageContaining("BeginString:Sender->Target");
        assertThatThrownBy(() -> properties("tcp://h:9007/amps/fix", "tcp://h:9007/amps/json", true,
                List.of(), List.of(new DestinationSpec("x", null, "t", null, List.of()))).validate())
                .hasMessageContaining("needs a type");
    }
}
