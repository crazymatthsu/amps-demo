package com.demo.amps.qfj2.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionSettings;

class SessionSettingsLoaderTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("system property beats environment beats default")
    void resolvesInOrder() {
        Properties system = new Properties();
        system.setProperty("HOST", "from-sysprop");
        Map<String, String> env = Map.of("HOST", "from-env", "PORT", "9999");

        assertThat(SessionSettingsLoader.resolve("a=${HOST:dflt} b=${PORT:1} c=${OTHER:dflt}", env, system))
                .isEqualTo("a=from-sysprop b=9999 c=dflt");
    }

    @Test
    @DisplayName("an unresolved placeholder with no default fails by name")
    void unresolvedWithoutDefaultFails() {
        assertThatThrownBy(() -> SessionSettingsLoader.resolve("x=${NOPE}", Map.of(), new Properties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("${NOPE}");
    }

    @Test
    @DisplayName("values containing regex or dollar characters come through untouched")
    void valuesAreLiteral() {
        assertThat(SessionSettingsLoader.resolve("x=${V}", Map.of("V", "a$b\\c"), new Properties()))
                .isEqualTo("x=a$b\\c");
        assertThat(SessionSettingsLoader.resolve("x=${V:with:colon}", Map.of(), new Properties()))
                .isEqualTo("x=with:colon");
    }

    @Test
    @DisplayName("a file loads into QuickFIX/J settings with the placeholders applied")
    void loadsAFile() throws Exception {
        Path file = dir.resolve("quickfixj.cfg");
        Files.writeString(file, """
                [default]
                ConnectionType=initiator
                SocketConnectHost=${QFJ2_TEST_HOST_THAT_IS_NOT_SET:venue.example}
                SocketConnectPort=9876

                [session]
                BeginString=FIX.4.2
                SenderCompID=A
                TargetCompID=B
                """);
        SessionSettings settings = SessionSettingsLoader.load(file);
        assertThat(settings.getString("SocketConnectHost")).isEqualTo("venue.example");
        assertThat(settings.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing file is reported with its absolute path")
    void missingFileIsReported() {
        assertThatThrownBy(() -> SessionSettingsLoader.load(dir.resolve("nope.cfg")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope.cfg");
    }
}
