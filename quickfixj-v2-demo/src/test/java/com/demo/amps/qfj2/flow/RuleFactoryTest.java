package com.demo.amps.qfj2.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.qfj2.engine.Direction;
import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.mock.ExecutionReports;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.SenderCompID;
import quickfix.field.TargetCompID;

class RuleFactoryTest {

    private static final SessionID SESSION = new SessionID("FIX.4.2", "DROPCOPY", "VENUE");
    private static final FixContext CONTEXT =
            new FixContext(SESSION, Direction.INBOUND, Instant.parse("2026-09-17T08:14:03.117Z"));

    private static Message report() {
        Message message = ExecutionReports.sample("T", 1, "AAPL");
        message.getHeader().setField(new SenderCompID("VENUE"));
        message.getHeader().setField(new TargetCompID("DROPCOPY"));
        return message;
    }

    private static RuleSpec spec(String type, Integer tag, Integer from, Integer to, String value, List<String> msgTypes) {
        return new RuleSpec(type, tag, from, to, value, msgTypes);
    }

    @Test
    @DisplayName("set-tag writes a constant")
    void setTag() throws Exception {
        Message message = report();
        RuleFactory.create(spec("set-tag", 5001, null, null, "VENUE-DROPCOPY", List.of())).apply(message, CONTEXT);
        assertThat(message.getString(5001)).isEqualTo("VENUE-DROPCOPY");
    }

    @Test
    @DisplayName("copy-tag reads the body first, then the header, and skips a missing source")
    void copyTag() throws Exception {
        Message message = report();
        RuleFactory.create(spec("copy-tag", null, 49, 5002, null, List.of())).apply(message, CONTEXT);
        assertThat(message.getString(5002)).isEqualTo("VENUE");

        RuleFactory.create(spec("copy-tag", null, 55, 5005, null, List.of())).apply(message, CONTEXT);
        assertThat(message.getString(5005)).isEqualTo("AAPL");

        RuleFactory.create(spec("copy-tag", null, 9999, 5006, null, List.of())).apply(message, CONTEXT);
        assertThat(message.isSetField(5006)).isFalse();
    }

    @Test
    @DisplayName("remove-tag drops a body tag")
    void removeTag() {
        Message message = report();
        assertThat(message.isSetField(55)).isTrue();
        RuleFactory.create(spec("remove-tag", 55, null, null, null, List.of())).apply(message, CONTEXT);
        assertThat(message.isSetField(55)).isFalse();
    }

    @Test
    @DisplayName("source-session and received-time write the context")
    void contextRules() throws Exception {
        Message message = report();
        RuleFactory.create(spec("source-session", 5003, null, null, null, List.of())).apply(message, CONTEXT);
        RuleFactory.create(spec("received-time", 5004, null, null, null, List.of())).apply(message, CONTEXT);
        assertThat(message.getString(5003)).isEqualTo("FIX.4.2:DROPCOPY->VENUE");
        assertThat(message.getString(5004)).isEqualTo("20260917-08:14:03.117");
    }

    @Test
    @DisplayName("msg-types limits a rule to the listed message types")
    void msgTypeFilter() {
        EnrichmentRule rule = RuleFactory.create(spec("set-tag", 5001, null, null, "X", List.of("D", "G")));
        Message message = report();
        rule.apply(message, CONTEXT);
        assertThat(message.isSetField(5001)).as("35=8 is not listed").isFalse();
        assertThat(rule.describe()).contains("35 in");

        EnrichmentRule matching = RuleFactory.create(spec("set-tag", 5001, null, null, "X", List.of("8")));
        matching.apply(message, CONTEXT);
        assertThat(message.isSetField(5001)).isTrue();
    }

    @Test
    @DisplayName("type names are forgiving about case and underscores")
    void typeNamesAreForgiving() {
        assertThat(RuleFactory.create(spec("Set_Tag", 5001, null, null, "X", null)).describe()).startsWith("set-tag");
    }

    @Test
    @DisplayName("an unknown type, or a type missing its parameter, is refused with the index")
    void refusesBadSpecs() {
        assertThatThrownBy(() -> RuleFactory.create(spec("frobnicate", 1, null, null, null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown rule type 'frobnicate'")
                .hasMessageContaining("set-tag");
        assertThatThrownBy(() -> RuleFactory.create(spec("set-tag", 5001, null, null, null, List.of())))
                .hasMessageContaining("needs 'value'");
        assertThatThrownBy(() -> RuleFactory.create(spec("copy-tag", null, 49, null, null, List.of())))
                .hasMessageContaining("needs 'to'");
        assertThatThrownBy(() -> RuleFactory.create(spec("remove-tag", -5, null, null, null, List.of())))
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> RuleFactory.create(spec(null, 1, null, null, null, List.of())))
                .hasMessageContaining("needs a type");
        assertThatThrownBy(() -> RuleFactory.createAll(List.of(
                spec("set-tag", 5001, null, null, "ok", List.of()),
                spec("set-tag", null, null, null, "no tag", List.of()))))
                .hasMessageContaining("qfj.flow.rules[1]");
    }
}
