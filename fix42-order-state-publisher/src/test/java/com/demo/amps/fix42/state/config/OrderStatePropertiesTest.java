package com.demo.amps.fix42.state.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.fix42.state.it.OrderStateConfigurations;
import com.demo.amps.fix42.state.machine.MachineOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderStatePropertiesTest {

    private static final String URI = "tcp://127.0.0.1:9007/amps/fix";

    @Test
    @DisplayName("the shipped configuration binds, validates, and its defaults are production's")
    void shippedIsValid() {
        OrderStateProperties properties = OrderStateConfigurations.shipped(URI, null);
        properties.validate();
        assertThat(properties.mode()).isEqualTo(Mode.LENIENT);
        assertThat(properties.amps().publishStore()).isEqualTo(PublishStoreType.FILE);
        assertThat(properties.keysOf("sow/fix42/order_state")).containsExactly(13011);
        assertThat(properties.keysOf("fix42/unrouted")).isEmpty();
        assertThat(properties.routes().getLast().catchAll()).isTrue();
        assertThat(properties.routes().get(3).guarded("sow/fix42/execs", 37, "NONE")).isTrue();
        assertThat(properties.routes().get(3).guarded("sow/fix42/execs_audit", 17, "0")).isTrue();
        assertThat(properties.machineOptions()).isEqualTo(MachineOptions.defaults());
    }

    @Test
    @DisplayName("the demo profile: a memory store and strict mode")
    void demoProfile() {
        OrderStateProperties properties = OrderStateConfigurations.shipped(URI, "demo");
        properties.validate();
        assertThat(properties.mode()).isEqualTo(Mode.STRICT);
        assertThat(properties.amps().publishStore()).isEqualTo(PublishStoreType.MEMORY);
    }

    @Test
    @DisplayName("a named dialect overlays its knobs and keeps the rest")
    void dialect() {
        MachineOptions options = OrderStateConfigurations.shipped(URI, null, "order-state.dialect=venue-z")
                .machineOptions();
        assertThat(options.cancelRejectStatus()).isEqualTo(CancelRejectStatusPolicy.NEVER);
        assertThat(options.lateFillAfterTerminal()).isEqualTo(LateFillPolicy.ADOPT_VENUE);
        assertThat(options.fillUnderPendingId()).isEqualTo(FillUnderPendingIdPolicy.ANSWERS_REPLACE_IF_TERMS_MATCH);
        assertThat(options.nonFillAfterTerminal()).isEqualTo(NonFillAfterTerminalPolicy.ADOPT_VENUE_IF_LATER);
    }

    @Test
    @DisplayName("refusals: a json URI, an audit key that is not the blotter's, an unknown dialect")
    void refusals() {
        assertThatThrownBy(() -> OrderStateConfigurations.shipped("tcp://h:9007/amps/json", null).validate())
                .hasMessageContaining("/amps/fix");
        assertThatThrownBy(() -> OrderStateConfigurations.shipped(URI, null, "audit.key-tag=11").validate())
                .hasMessageContaining("audit.key-tag");
        assertThatThrownBy(() -> OrderStateConfigurations.shipped(URI, null, "order-state.dialect=nope").validate())
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("docs/11 F11: eviction or a partial load without the miss lookup is refused")
    void tombstonesNeedTheMissLookup() {
        assertThatThrownBy(() -> OrderStateConfigurations.shipped(URI, null,
                "order-state.terminal-retention=PT10M", "order-state.miss-lookup=false").validate())
                .hasMessageContaining("order-state.terminal-retention need order-state.miss-lookup: true")
                .hasMessageContaining("F11");
        assertThatThrownBy(() -> OrderStateConfigurations.shipped(URI, null,
                "order-state.load-open-orders-only=true", "order-state.miss-lookup=false").validate())
                .hasMessageContaining("order-state.load-open-orders-only need order-state.miss-lookup: true");
        assertThatThrownBy(() -> OrderStateConfigurations.shipped(URI, null, "order-state.terminal-retention=PT10M",
                "order-state.load-open-orders-only=true", "order-state.miss-lookup=false").validate())
                .hasMessageContaining("terminal-retention and order-state.load-open-orders-only need");
    }

    @Test
    @DisplayName("docs/11 F11: with the miss lookup on, or with no tombstones, each is accepted")
    void tombstonesWithTheMissLookupAreAccepted() {
        OrderStateConfigurations.shipped(URI, null, "order-state.terminal-retention=PT10M").validate();
        OrderStateConfigurations.shipped(URI, null, "order-state.load-open-orders-only=true").validate();
        OrderStateConfigurations.shipped(URI, null, "order-state.miss-lookup=false").validate();
        OrderStateConfigurations.shipped(URI, null, "order-state.terminal-retention=PT0S",
                "order-state.miss-lookup=false").validate();
    }
}
