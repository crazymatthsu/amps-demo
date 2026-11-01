package com.demo.amps.fix42.state.it;

import static com.demo.amps.fix42.state.it.OrderStateFixture.BLOTTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.machine.Counter;
import com.demo.amps.fix42.state.mock.MockFixFlow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cache without a body for an order -- a partial load (E54), an eviction
 * (E47, E48) -- and the record's schema at load (E62). In each, the entry
 * comes back from the record, never from the message (S9).
 */
class TombstoneIT {

    /** A bust of AAPL's first fill, after the order was filled and closed out. */
    private static final String AAPL_BUST = "35=8|20=1|19=EXEC-PARENT-AAPL-2|17=EXEC-PARENT-AAPL-BUST|150=1|39=1"
            + "|11=PARENT-AAPL-2|41=PARENT-AAPL-1|37=ORD-PARENT-AAPL|38=12000|44=185.75|14=10000|151=2000|6=185.6667"
            + "|60=20260821-14:59:00.000|";

    private OrderStateFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private void send(String raw) throws Exception {
        fixture.publisher.send(FixMessage.parse(raw.replace('|', '\u0001')));
    }

    private FixMessage record(String key) throws Exception {
        return fixture.sow.record(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID, key);
    }

    @Test
    @DisplayName("E54: load-open-orders-only, then a late bust for a terminal order lands via the tombstone")
    void partialLoad() throws Exception {
        fixture = OrderStateFixture.start("fix42os-partial", "demo");
        fixture.publish(MockFixFlow.events());
        fixture.flush();
        FixMessage before = record("PARENT-AAPL-1");
        fixture.disconnectPublisher();

        fixture.connect(OrderStateFixture.settings(fixture.server, "fix42os-partial", "demo",
                "order-state.load-open-orders-only=true"), List.of());
        assertThat(fixture.loaded.tombstones()).isPositive();
        assertThat(fixture.loaded.complete()).isFalse();
        assertThat(fixture.publisher.machine().state("PARENT-AAPL-1")).as("no body for a terminal order").isNull();

        send(AAPL_BUST);
        fixture.flush();
        FixMessage after = record("PARENT-AAPL-1");
        assertThat(after.value(FixTags.ORD_STATUS)).isEqualTo("1");
        assertThat(after.value(FixTags.CUM_QTY)).isEqualTo("10000");
        assertThat(after.value(FixTags.LEAVES_QTY)).isEqualTo("2000");
        // From the record, not the bust: the chain list and the count go on.
        assertThat(after.value(FixTags.Record.CL_ORD_ID_CHAIN))
                .isEqualTo(before.value(FixTags.Record.CL_ORD_ID_CHAIN));
        assertThat(Long.parseLong(after.value(FixTags.Record.MSG_COUNT)))
                .isEqualTo(Long.parseLong(before.value(FixTags.Record.MSG_COUNT)) + 1);
        assertThat(after.value(FixTags.Record.CHAIN_ORIGIN)).isEqualTo("D");
        assertThat(after.value(FixTags.SYMBOL)).isEqualTo("AAPL");
        assertThat(fixture.publisher.machine().counters().get(Counter.REBUILT_FROM_RECORD)).isEqualTo(1);
    }

    @Test
    @DisplayName("E47/E48: terminal-retention evicts; a late bust and a late request find the record again")
    void eviction() throws Exception {
        fixture = OrderStateFixture.start("fix42os-eviction", "demo", "order-state.terminal-retention=PT0.2S");
        fixture.publish(MockFixFlow.events());
        fixture.flush();
        Thread.sleep(400);
        int evicted = fixture.publisher.machine().evictExpired();
        assertThat(evicted).isPositive();
        assertThat(fixture.publisher.machine().state("PARENT-AAPL-1")).isNull();
        FixMessage before = record("PARENT-AAPL-1");

        send(AAPL_BUST);
        fixture.flush();
        FixMessage after = record("PARENT-AAPL-1");
        assertThat(after.value(FixTags.ORD_STATUS)).isEqualTo("1");
        assertThat(after.value(FixTags.Record.CL_ORD_ID_CHAIN))
                .isEqualTo(before.value(FixTags.Record.CL_ORD_ID_CHAIN));
        assertThat(Long.parseLong(after.value(FixTags.Record.MSG_COUNT)))
                .isEqualTo(Long.parseLong(before.value(FixTags.Record.MSG_COUNT)) + 1);

        // MSFT was canceled and evicted too; a late cancel under a new id names
        // an old one in 41, which only orders_audit still knows.
        assertThat(fixture.publisher.machine().state("PARENT-MSFT-1")).isNull();
        send("35=F|11=PARENT-MSFT-9|41=PARENT-MSFT-2|60=20260821-14:59:30.000|");
        fixture.flush();
        FixMessage msft = record("PARENT-MSFT-1");
        assertThat(msft.value(FixTags.Record.PENDING_COUNT)).isEqualTo("1");
        assertThat(msft.value(FixTags.ORD_STATUS)).isEqualTo("4");
        assertThat(fixture.publisher.machine().counters().get(Counter.MISS_LOOKUPS)).isEqualTo(1);
        assertThat(fixture.sow.records(BLOTTER)).hasSize(MockFixFlow.chains().size());
    }

    @Test
    @DisplayName("docs/11 F12: a restart with eviction on evicts the finished orders it reloaded in full")
    void reloadedFinishedOrdersAreEvicted() throws Exception {
        fixture = OrderStateFixture.start("fix42os-reload-evict", "demo", "order-state.terminal-retention=PT0.2S");
        fixture.publish(MockFixFlow.events());
        fixture.flush();
        FixMessage before = record("PARENT-AAPL-1");
        long finished = fixture.sow.records(BLOTTER).stream()
                .filter(r -> "Y".equals(r.value(FixTags.Record.TERMINAL))).count();
        assertThat(finished).as("the scripted flow ends some orders").isPositive();
        fixture.disconnectPublisher();

        // load-open-orders-only stays false: every record comes back with a body.
        fixture.connect(List.of());
        assertThat(fixture.loaded.loaded()).isEqualTo(MockFixFlow.chains().size());
        assertThat(fixture.publisher.machine().state("PARENT-AAPL-1")).as("reloaded in full").isNotNull();
        Thread.sleep(400);
        assertThat(fixture.publisher.machine().evictExpired()).isEqualTo((int) finished);
        assertThat(fixture.publisher.machine().size()).isEqualTo(MockFixFlow.chains().size() - (int) finished);
        assertThat(fixture.publisher.machine().state("PARENT-AAPL-1")).isNull();

        // And a late bust still finds its record, through the tombstone.
        send(AAPL_BUST);
        fixture.flush();
        FixMessage after = record("PARENT-AAPL-1");
        assertThat(after.value(FixTags.ORD_STATUS)).isEqualTo("1");
        assertThat(after.value(FixTags.Record.CL_ORD_ID_CHAIN))
                .isEqualTo(before.value(FixTags.Record.CL_ORD_ID_CHAIN));
        assertThat(Long.parseLong(after.value(FixTags.Record.MSG_COUNT)))
                .isEqualTo(Long.parseLong(before.value(FixTags.Record.MSG_COUNT)) + 1);
        assertThat(after.value(FixTags.Record.CHAIN_ORIGIN)).isEqualTo("D");
    }

    @Test
    @DisplayName("E62: a record missing 9026 loads with its default; a newer schema refuses the start")
    void schema() throws Exception {
        fixture = OrderStateFixture.start("fix42os-schema", "demo");
        fixture.publisher.publishRaw("OLD-1", true, bytes("13011=OLD-1|9024=1|9019=C|9021=OLD-1|9030=1|39=0"
                + "|14=0|151=10|6=0|9015=NONE|9016=0|9017=N|9018=D|9023=2|9025=0|55=OLD|"));
        fixture.flush();
        fixture.disconnectPublisher();
        fixture.connect(List.of());
        assertThat(fixture.loaded.defaulted()).isEqualTo(1);
        assertThat(fixture.publisher.machine().state("OLD-1").lateFills()).isZero();
        assertThat(fixture.publisher.machine().counters().get(Counter.RECOVERED_WITH_DEFAULTS)).isEqualTo(1);

        fixture.publisher.publishRaw("NEW-1", true, bytes("13011=NEW-1|9024=2|39=0|"));
        fixture.flush();
        fixture.disconnectPublisher();
        assertThatThrownBy(() -> fixture.connect(List.of())).hasMessageContaining("schema 2");
    }

    private static byte[] bytes(String raw) {
        return raw.replace('|', '\u0001').getBytes(StandardCharsets.ISO_8859_1);
    }
}
