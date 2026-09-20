package com.demo.amps.connectors.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ControlCommandTest {

    @Test
    @DisplayName("the documented shape parses field for field, args in wire order")
    void parsesTheFullShape() {
        ControlCommand command = ControlCommand.parse(
                "{\"command\":\"reload\",\"target\":\"instruments\",\"to\":\"instrument-enricher\","
                        + "\"requestId\":\"r-1\",\"args\":{\"b\":\"2\",\"a\":1,\"n\":null,\"t\":true}}");
        assertThat(command.command()).isEqualTo("reload");
        assertThat(command.target()).isEqualTo("instruments");
        assertThat(command.to()).isEqualTo("instrument-enricher");
        assertThat(command.requestId()).isEqualTo("r-1");
        assertThat(command.args().keySet()).containsExactly("b", "a", "n", "t");
        assertThat(command.args()).containsEntry("a", "1").containsEntry("n", null)
                .containsEntry("t", "true");
        assertThat(command.isBroadcast()).isFalse();
        assertThat(command.describe())
                .isEqualTo("reload target=instruments to=instrument-enricher requestId=r-1 "
                        + "args={b=2, a=1, n=null, t=true}");
    }

    @Test
    @DisplayName("only command is required; everything else defaults, and unknown fields are ignored")
    void requiresOnlyTheCommand() {
        ControlCommand command = ControlCommand.parse(
                "{\"command\":\" status \",\"future\":{\"x\":1},\"args\":null}");
        assertThat(command.command()).isEqualTo("status");
        assertThat(command.target()).isNull();
        assertThat(command.to()).isNull();
        assertThat(command.requestId()).isNull();
        assertThat(command.args()).isEmpty();
        assertThat(command.isBroadcast()).isTrue();
        assertThat(command.describe()).isEqualTo("status");
        assertThat(ControlCommand.parse("{\"command\":\"status\",\"to\":\"ALL\"}").isBroadcast())
                .isTrue();
    }

    @Test
    @DisplayName("what is not a command says what it is instead")
    void refusesWhatIsNotACommand() {
        assertThatThrownBy(() -> ControlCommand.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> ControlCommand.parse("[1,2]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("an array");
        assertThatThrownBy(() -> ControlCommand.parse("\"reload\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a string");
        assertThatThrownBy(() -> ControlCommand.parse(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> ControlCommand.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ControlCommand.parse("{\"target\":\"instruments\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"command\"");
        assertThatThrownBy(() -> ControlCommand.parse("{\"command\":\"  \"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"command\"");
        assertThatThrownBy(() -> ControlCommand.parse("{\"command\":\"x\",\"args\":[1]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"args\"");
        assertThatThrownBy(() -> ControlCommand.parse("{\"command\":\"x\",\"args\":{\"a\":{}}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"args\".a");
    }

    @Test
    @DisplayName("the record itself insists on a command and keeps args as an immutable copy")
    void theRecordGuardsItsInvariants() {
        assertThatThrownBy(() -> new ControlCommand(" ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> args = new HashMap<>();
        args.put("k", "v");
        ControlCommand command = new ControlCommand("x", null, null, null, args);
        args.put("later", "no");
        assertThat(command.args()).containsExactly(Map.entry("k", "v"));
        assertThatThrownBy(() -> command.args().put("y", "z"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ControlCommand("x", null, null, null, null).args()).isEmpty();
    }
}
