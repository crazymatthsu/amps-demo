package com.demo.amps.fix42.state.it;

import static com.demo.amps.fix42.state.it.OrderStateFixture.BLOTTER;
import static com.demo.amps.fix42.state.it.OrderStateFixture.EXECS;
import static com.demo.amps.fix42.state.it.OrderStateFixture.EXECS_AUDIT;
import static com.demo.amps.fix42.state.it.OrderStateFixture.ORDERS_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.Prices;
import com.demo.amps.fix42.state.mock.EdgeCaseFlow;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.mock.MockFixFlow;
import com.demo.amps.fix42.state.mock.OrderChain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The scripted flow's ten chains and every edge case, under the default
 * dialect, through the real publisher into a real AMPS -- then read back
 * (docs/05 phase 3). The unit suite proves the machine; this proves the
 * record a {@code sow} query returns is the machine's, after AMPS merged
 * every delta.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderStateEndStateIT {

    private OrderStateFixture fixture;
    private List<OrderChain> chains;
    private Map<String, FixMessage> blotter;

    @BeforeAll
    void publishEverything() throws Exception {
        fixture = OrderStateFixture.start("fix42os-end-state", "demo");
        chains = new ArrayList<>(MockFixFlow.chains());
        chains.addAll(EdgeCaseFlow.chains());
        List<FixEvent> events = new ArrayList<>();
        chains.forEach(chain -> events.addAll(chain.events()));
        fixture.publish(events);
        fixture.flush();
        blotter = fixture.sow.recordsBy(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID);
    }

    @AfterAll
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @DisplayName("one record per order: the cache's keys and the SOW's agree")
    void oneRecordPerOrder() {
        assertThat(blotter).hasSize(fixture.publisher.machine().size());
        Set<String> keys = new HashSet<>();
        for (OrderChain chain : chains) {
            keys.add(fixture.publisher.machine().keyForClOrdId(chain.rootClOrdId()));
        }
        assertThat(blotter.keySet()).containsAll(keys);
        assertThat(keys).doesNotContainNull().hasSize(chains.size());
    }

    @Test
    @DisplayName("every order's venue fields equal its OrderChain's final state")
    void venueFieldsEqualTheChains() {
        List<String> wrong = new ArrayList<>();
        for (OrderChain chain : chains) {
            FixMessage record = blotter.get(fixture.publisher.machine().keyForClOrdId(chain.rootClOrdId()));
            compare(wrong, chain, "39", chain.ordStatus(), record.value(FixTags.ORD_STATUS));
            compare(wrong, chain, "14", Long.toString(chain.cumQty()), record.value(FixTags.CUM_QTY));
            compare(wrong, chain, "151", Long.toString(chain.leavesQty()), record.value(FixTags.LEAVES_QTY));
            if (Double.parseDouble(record.value(FixTags.AVG_PX)) != Double.parseDouble(Prices.plain(chain.avgPx()))) {
                wrong.add(chain + " 6=" + record.value(FixTags.AVG_PX) + " (venue " + Prices.plain(chain.avgPx()) + ")");
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("the merged record keeps the D's terms; no record carries 35; every one carries 13011 and 12024")
    void dsTermsSurviveEveryDelta() {
        for (OrderChain chain : MockFixFlow.chains()) {
            FixMessage record = blotter.get(chain.rootClOrdId());
            assertThat(record.value(FixTags.SYMBOL)).as(chain.chainId()).isEqualTo(chain.symbol());
            assertThat(record.value(FixTags.ACCOUNT)).as(chain.chainId()).isEqualTo(chain.account());
            assertThat(record.value(FixTags.SIDE)).as(chain.chainId()).isEqualTo(chain.side());
            assertThat(record.value(FixTags.HANDL_INST)).as(chain.chainId()).isEqualTo("1");
        }
        for (FixMessage record : blotter.values()) {
            assertThat(record.has(FixTags.MSG_TYPE)).as(record.printable()).isFalse();
            assertThat(record.value(FixTags.Record.SCHEMA_VERSION)).as(record.printable()).isEqualTo("2");
        }
    }

    @Test
    @DisplayName("AAPL: the amended terms as the venue stated them, 12014 rotated, three ids in 12021")
    void appleAmended() {
        FixMessage apple = blotter.get("PARENT-AAPL-1");
        assertThat(apple.value(FixTags.ORDER_QTY)).isEqualTo("12000");
        assertThat(apple.value(FixTags.PRICE)).isEqualTo("185.75");
        assertThat(apple.value(FixTags.Record.WORKING_CL_ORD_ID)).isEqualTo("PARENT-AAPL-2");
        assertThat(apple.value(FixTags.Record.CL_ORD_ID_CHAIN)).isEqualTo("PARENT-AAPL-1,PARENT-AAPL-2");
        assertThat(apple.value(FixTags.ORD_STATUS)).isEqualTo("2");
        assertThat(apple.value(FixTags.Record.CHAIN_ORIGIN)).isEqualTo("D");
    }

    @Test
    @DisplayName("NVDA: the rejected amend never reached 38/44 (the stateless blotter's NVDA case)")
    void nvidiaAmendRejected() {
        FixMessage nvidia = blotter.get("PARENT-NVDA-1");
        assertThat(nvidia.value(FixTags.ORDER_QTY)).isEqualTo("4000");
        assertThat(nvidia.value(FixTags.PRICE)).isEqualTo("121.8");
        assertThat(nvidia.value(FixTags.Record.PENDING_ORDER_QTY)).isEqualTo("0");
        assertThat(nvidia.value(FixTags.ORD_STATUS)).isEqualTo("3");
    }

    @Test
    @DisplayName("a chain first seen mid-stream (E40) became a record by delta_publish, then took its late D")
    void midstreamThenLateD() {
        FixMessage record = blotter.get("EC-E40-1");
        assertThat(record.value(FixTags.Record.CHAIN_ORIGIN)).isEqualTo("D_LATE");
        assertThat(record.value(FixTags.HANDL_INST)).as("a term only the D carries").isEqualTo("1");
        assertThat(record.value(FixTags.SYMBOL)).isEqualTo("MSFT");
    }

    @Test
    @DisplayName("E57: the order whose OrderID is another's ClOrdID has its own record")
    void orderIdEqualToAnotherClOrdId() {
        // A's D said 11=123456; B's venue OrderID is 123456. Typed maps: B's
        // reports never resolved to A (S1).
        assertThat(blotter.get("123456").value(FixTags.ORDER_ID)).isEqualTo("ORD-EC-E57A");
        assertThat(blotter.get("EC-E57B-1").value(FixTags.ORDER_ID)).isEqualTo("123456");
        assertThat(blotter.get("EC-E57B-1").value(FixTags.CUM_QTY)).isEqualTo("100");
        assertThat(blotter.get("EC-E57B-1").value(FixTags.Record.CHAIN_ORIGIN)).isEqualTo("MIDSTREAM");
        assertThat(blotter.get("123456").value(FixTags.CUM_QTY)).isEqualTo("0");
    }

    @Test
    @DisplayName("orders_audit: one whole message per request ClOrdID, each with 13011")
    void ordersAudit() throws Exception {
        Map<String, FixMessage> audit = fixture.sow.recordsBy(ORDERS_AUDIT, FixTags.CL_ORD_ID);
        Set<String> requestIds = new HashSet<>();
        for (OrderChain chain : chains) {
            for (FixEvent event : chain.events()) {
                if (List.of("D", "G", "F").contains(event.msgType())) {
                    requestIds.add(event.message().value(FixTags.CL_ORD_ID));
                }
            }
        }
        assertThat(audit.keySet()).containsExactlyInAnyOrderElementsOf(requestIds);
        for (FixMessage record : audit.values()) {
            assertThat(record.value(FixTags.Record.ROOT_CL_ORD_ID)).as(record.printable()).isNotEmpty();
            assertThat(record.has(FixTags.MSG_TYPE)).as("the message whole, 35 and all").isTrue();
        }
        // The history of one order is one query.
        assertThat(fixture.sow.records(ORDERS_AUDIT, "/13011 = 'PARENT-AAPL-1'")).hasSize(2);
        assertThat(fixture.sow.records(EXECS_AUDIT, "/13011 = 'PARENT-AAPL-1'")).hasSize(6);
    }

    @Test
    @DisplayName("execs: never keyed by 37=NONE; execs_audit holds every report with a real ExecID")
    void execs() throws Exception {
        assertThat(fixture.sow.records(EXECS, "/37 = 'NONE'")).isEmpty();
        long reports = chains.stream().flatMap(c -> c.events().stream())
                .filter(e -> "8".equals(e.msgType()))
                .map(e -> e.message().value(FixTags.EXEC_ID))
                .filter(id -> !id.isEmpty() && !"0".equals(id)).distinct().count();
        assertThat(fixture.sow.records(EXECS_AUDIT)).hasSize((int) reports);
    }

    @Test
    @DisplayName("S3: every route live, the publish store drains to zero -- every topic written is journalled")
    void storeDrains() {
        assertThat(fixture.publisher.publishStoreDepth()).isZero();
        assertThat(fixture.publisher.counters().publishFailures()).isZero();
        assertThat(fixture.repairs.refused()).isZero();
    }

    private static void compare(List<String> wrong, OrderChain chain, String tag, String expected, String actual) {
        if (!expected.equals(actual)) {
            wrong.add(chain + " " + tag + "=" + actual + " (venue " + expected + ")");
        }
    }
}
