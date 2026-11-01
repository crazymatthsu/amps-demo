package com.demo.amps.fix42.state.it;

import static com.demo.amps.fix42.state.it.OrderStateFixture.BLOTTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.machine.Counter;
import com.demo.amps.fix42.state.mock.EdgeCaseFlow;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.mock.MockFixFlow;
import com.demo.amps.fix42.state.mock.OrderChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A publisher restart mid-flow (docs/05 phase 4, 06 E49): the first half of
 * every chain, a new publisher on the same instance, the second half -- and
 * the end state compared, tag for tag, with the uninterrupted run.
 */
class RecoveryIT {

    private OrderStateFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static List<OrderChain> chains() {
        List<OrderChain> chains = new ArrayList<>(MockFixFlow.chains());
        chains.addAll(EdgeCaseFlow.chains());
        return chains;
    }

    @Test
    @DisplayName("recovery SOW: a restart mid-flow is invisible in the end state, tag for tag")
    void sowRecovery() throws Exception {
        fixture = OrderStateFixture.start("fix42os-recovery-sow", "demo");
        List<List<FixEvent>> halves = ReferenceRun.halves(chains());
        List<FixEvent> all = new ArrayList<>(halves.get(0));
        all.addAll(halves.get(1));
        ReferenceRun reference = ReferenceRun.of(all, fixture.properties.machineOptions());

        fixture.publish(halves.get(0));
        int before = fixture.publisher.machine().size();
        fixture.disconnectPublisher();
        fixture.connect(List.of());
        assertThat(fixture.loaded.loaded()).as("every record rebuilt").isEqualTo(before);
        assertThat(fixture.loaded.complete()).isTrue();

        fixture.publish(halves.get(1));
        fixture.flush();
        Map<String, FixMessage> blotter = fixture.sow.recordsBy(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID);
        assertThat(reference.differences(blotter)).isEmpty();
        assertThat(fixture.publisher.machine().counters().get(Counter.MIDSTREAM_CHAINS))
                .as("no order the SOW held became a second record").isZero();
    }

    @Test
    @DisplayName("E53/E55: recovery NONE with allow-incomplete-cache and the 37 lookup -- every order found, no second record")
    void noneWithLookups() throws Exception {
        fixture = OrderStateFixture.start("fix42os-recovery-none", "demo");
        List<OrderChain> chains = chains();
        List<List<FixEvent>> halves = ReferenceRun.halves(chains);
        fixture.publish(halves.get(0));
        fixture.disconnectPublisher();

        fixture.connect(OrderStateFixture.settings(fixture.server, "fix42os-recovery-none", "demo",
                "order-state.recovery=NONE", "order-state.allow-incomplete-cache=true",
                // E43 is reached only by its 37: its two replaces were never
                // seen, so no audit topic names its ids. Without this lookup it
                // becomes the documented MIDSTREAM twin (CacheMissTest).
                "order-state.miss-lookup-by-order-id=true"), List.of());
        assertThat(fixture.publisher.machine().cacheComplete()).isFalse();
        fixture.publish(halves.get(1));
        fixture.flush();

        Map<String, FixMessage> blotter = fixture.sow.recordsBy(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID);
        java.util.Set<String> extra = new java.util.TreeSet<>(blotter.keySet());
        chains.forEach(chain -> extra.remove(chain.rootClOrdId()));
        assertThat(extra).as("one record per order: none duplicated by a MIDSTREAM twin").isEmpty();
        List<String> wrong = new ArrayList<>();
        for (OrderChain chain : chains) {
            FixMessage record = blotter.get(chain.rootClOrdId());
            if (record == null) {
                wrong.add(chain + ": no record under its root");
            } else if (!chain.ordStatus().equals(record.value(FixTags.ORD_STATUS))
                    || !Long.toString(chain.cumQty()).equals(record.value(FixTags.CUM_QTY))
                    || !Long.toString(chain.leavesQty()).equals(record.value(FixTags.LEAVES_QTY))) {
                wrong.add(chain + ": " + record.value(39) + "/" + record.value(14) + "/" + record.value(151));
            }
        }
        assertThat(wrong).isEmpty();
        var counters = fixture.publisher.machine().counters();
        assertThat(counters.get(Counter.MISS_LOOKUPS)).isPositive();
        assertThat(counters.get(Counter.REBUILT_FROM_RECORD)).isPositive();
        assertThat(counters.get(Counter.MIDSTREAM_CHAINS)).isZero();

        // A resent D, against a record the cache had to look up: kept (R4c).
        int beforeResend = blotter.size();
        fixture.publisher.send(MockFixFlow.chains().getFirst().events().getFirst().message());
        fixture.flush();
        FixMessage apple = fixture.sow.record(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID, "PARENT-AAPL-1");
        assertThat(apple.value(FixTags.ORD_STATUS)).isEqualTo("2");
        assertThat(apple.value(FixTags.ORDER_QTY)).isEqualTo("12000");
        assertThat(fixture.sow.records(BLOTTER)).hasSize(beforeResend);

        // A report naming no id anyone has seen: the documented MIDSTREAM record.
        fixture.publisher.send(FixMessage.parse(("35=8|150=0|39=0|11=STRANGER-1|37=ORD-STRANGER|17=S-1|55=XYZ"
                + "|14=0|151=10|6=0|38=10|60=20261031-10:00:00.000|").replace('|', '\u0001')));
        fixture.flush();
        assertThat(fixture.sow.record(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID, "STRANGER-1")
                .value(FixTags.Record.CHAIN_ORIGIN)).isEqualTo("MIDSTREAM");
    }

    @Test
    @DisplayName("E56: recovery NONE against a non-empty blotter is refused without allow-incomplete-cache")
    void noneRefused() throws Exception {
        fixture = OrderStateFixture.start("fix42os-recovery-refused", "demo");
        fixture.publish(MockFixFlow.events());
        fixture.disconnectPublisher();
        assertThatThrownBy(() -> fixture.connect(OrderStateFixture.settings(fixture.server,
                "fix42os-recovery-refused", "demo", "order-state.recovery=NONE"), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-incomplete-cache");
    }
}
