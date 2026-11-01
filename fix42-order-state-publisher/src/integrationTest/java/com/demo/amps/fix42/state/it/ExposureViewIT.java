package com.demo.amps.fix42.state.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.demo.amps.fix42.state.mock.EdgeCaseFlow;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.mock.MockFixFlow;
import com.demo.amps.fix42.state.mock.OrderChain;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The four views of fix42-chaining, declared over {@code sow/fix42/order_state}
 * (docs/05 phase 3). The expected numbers are computed from the mock venue's
 * own final state per order, so the check is the blotter's against the venue,
 * through AMPS's aggregation: terminal orders contribute no LeavesQty, and the
 * MIDSTREAM records (E40, E57B) contribute what their reports carried.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposureViewIT {

    private OrderStateFixture fixture;
    private List<OrderChain> chains;

    @BeforeAll
    void publish() throws Exception {
        fixture = OrderStateFixture.start("fix42os-views", "demo");
        chains = new ArrayList<>(MockFixFlow.chains());
        chains.addAll(EdgeCaseFlow.chains());
        List<FixEvent> events = new ArrayList<>();
        chains.forEach(chain -> events.addAll(chain.events()));
        fixture.publish(events);
        fixture.flush();
    }

    @AfterAll
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private record Totals(long orders, long orderQty, long leavesQty, long cumQty, double notional) {
        Totals plus(OrderChain chain) {
            return new Totals(orders + 1, orderQty + chain.orderQty(), leavesQty + chain.leavesQty(),
                    cumQty + chain.cumQty(), notional + chain.cumQty() * chain.avgPx());
        }
    }

    private Map<String, Totals> expected(boolean children) {
        Map<String, Totals> groups = new TreeMap<>();
        for (OrderChain chain : chains) {
            if ((chain.parentClOrdId() != null) != children) {
                continue;
            }
            String group = chain.account() + "|" + chain.symbol() + "|" + chain.side();
            groups.merge(group, new Totals(0, 0, 0, 0, 0).plus(chain), (a, b) -> new Totals(a.orders + b.orders,
                    a.orderQty + b.orderQty, a.leavesQty + b.leavesQty, a.cumQty + b.cumQty, a.notional + b.notional));
        }
        return groups;
    }

    @Test
    @DisplayName("parent and child exposure equal the venue's totals per account x symbol x side")
    void exposureEqualsTheVenue() {
        for (boolean children : new boolean[] {false, true}) {
            String view = children ? "view/fix42/exposure/child" : "view/fix42/exposure/parent";
            Map<String, Totals> expected = expected(children);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                Map<String, JsonObject> actual = new TreeMap<>();
                for (JsonObject row : fixture.views.json(view, null)) {
                    actual.put(row.get("Account").getAsString() + "|" + row.get("Symbol").getAsString() + "|"
                            + row.get("Side").getAsString(), row);
                }
                assertThat(actual.keySet()).as(view).containsExactlyElementsOf(expected.keySet());
                expected.forEach((group, totals) -> {
                    JsonObject row = actual.get(group);
                    assertThat(number(row, "Orders")).as("%s %s orders", view, group).isEqualTo(totals.orders());
                    assertThat(number(row, "OrderQty")).as("%s %s 38", view, group).isEqualTo(totals.orderQty());
                    assertThat(number(row, "LeavesQty")).as("%s %s 151", view, group).isEqualTo(totals.leavesQty());
                    assertThat(number(row, "CumQty")).as("%s %s 14", view, group).isEqualTo(totals.cumQty());
                    if (totals.cumQty() > 0) {
                        assertThat(row.get("AvgPx").getAsDouble()).as("%s %s vwap", view, group)
                                .isCloseTo(totals.notional() / totals.cumQty(), within(0.001));
                    }
                });
            });
        }
    }

    @Test
    @DisplayName("children_by_parent: the TSLA slices roll up to their parent's 14, as the scripted flow intends")
    void childrenByParent() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<JsonObject> rows = fixture.views.json("view/fix42/exposure/children_by_parent", null);
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.get("ParentOrderID").getAsString()).isEqualTo("PARENT-TSLA-1");
                assertThat(number(row, "Orders")).isEqualTo(2);
                assertThat(number(row, "CumQty")).isEqualTo(16_000);
                assertThat(number(row, "LeavesQty")).isZero();
            });
        });
    }

    @Test
    @DisplayName("recon: the TSLA group reconciles parent against children; unsliced groups carry nulls")
    void reconciliation() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<JsonObject> breaks = fixture.views.json("view/fix42/recon/parent_vs_child",
                    "/ChildCumQty IS NOT NULL AND /CumQtyDelta != 0");
            assertThat(breaks).isEmpty();
            List<JsonObject> tesla = fixture.views.json("view/fix42/recon/parent_vs_child", "/Symbol = 'TSLA'");
            assertThat(tesla).singleElement().satisfies(row -> assertThat(number(row, "CumQtyDelta")).isZero());
        });
    }

    private static long number(JsonObject row, String field) {
        JsonElement element = row.get(field);
        if (element == null || element.isJsonNull()) {
            throw new AssertionError("no " + field + " in " + row);
        }
        return element.getAsBigDecimal().longValueExact();
    }
}
