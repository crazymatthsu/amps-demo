package com.demo.amps.fix42.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.fix.Prices;
import com.demo.amps.fix42.mock.FixEvent;
import com.demo.amps.fix42.mock.MockFixFlow;
import com.demo.amps.fix42.publish.AmpsDeltaPublisher;
import com.demo.amps.fix42.publish.PublishPlanner;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The exposure views over the chained blotter, read back after the scripted
 * flow has been published: account x symbol x side, parents and children
 * kept apart, and the children rolled up under their parent as a check.
 *
 * <p>Two things are deliberate about how this reads the views:
 *
 * <ul>
 *   <li><b>Live values, no restart.</b> Every number here is what the view
 *       computed as the blotter records were updated in place -- each chain's
 *       record changed many times on its way to the state asserted below. On
 *       this AMPS build an aggregate wrapped in {@code IF()} accumulates
 *       add-only on exactly that path and only snaps to the right value when a
 *       restart rebuilds the view from the SOW; a suite that restarted before
 *       reading would have hidden it. The views use bare aggregates, and this
 *       is the test that would catch a regression to the guarded form.</li>
 *   <li><b>A second client, json-typed.</b> The views are json and the
 *       blotter is fix; a connection speaks one message type, so the views are
 *       read over {@code /amps/json} while the blotter is read over the
 *       publisher's {@code /amps/fix}.</li>
 * </ul>
 *
 * <p>Skipped, not failed, when no AMPS image is configured -- see
 * {@link AmpsTestServer#unavailableReason()}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposureViewIT {

    private static final Logger log = LoggerFactory.getLogger(ExposureViewIT.class);

    private static final String ORDERS = "sow/fix42/orders";
    private static final String PARENT_VIEW = "view/fix42/exposure/parent";
    private static final String CHILD_VIEW = "view/fix42/exposure/child";
    private static final String CHILDREN_BY_PARENT_VIEW = "view/fix42/exposure/children_by_parent";

    /** Tag 54 as FIX spells it; the view passes the code through unchanged. */
    private static final String BUY = "1";
    private static final String SELL = "2";

    private static final Duration VIEW_SETTLE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * One row of an account x symbol x side view, in the form the assertions
     * compare.
     *
     * <p>AvgPx is carried as the venue would print it -- four places, no
     * trailing zeros, via {@link Prices#plain(double)} -- so a row compares
     * exactly and reads like the FIX it came from, while {@code null} keeps
     * the meaning the json view gives it: no fills, nothing to average.
     */
    record Exposure(String account, String symbol, String side, long orders, long orderQty,
                    long leavesQty, long cumQty, String avgPx) {

        static Exposure of(JsonObject row) {
            Double avgPx = ViewReader.price(row, "AvgPx");
            return new Exposure(
                    ViewReader.text(row, "Account"),
                    ViewReader.text(row, "Symbol"),
                    ViewReader.text(row, "Side"),
                    ViewReader.quantity(row, "Orders"),
                    ViewReader.quantity(row, "OrderQty"),
                    ViewReader.quantity(row, "LeavesQty"),
                    ViewReader.quantity(row, "CumQty"),
                    avgPx == null ? null : Prices.plain(avgPx));
        }
    }

    private AmpsTestServer server;
    private Client fixClient;
    private Client jsonClient;
    private SowReader sow;
    private ViewReader views;

    @BeforeAll
    void publishTheFlow() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.FIX42_CHAINING);
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        server = AmpsTestServer.start(AmpsFlow.FIX42_CHAINING);

        Fix42Properties properties = Fix42Configurations.shipped(server.uri());
        properties.validate();
        long timeoutMs = properties.amps().timeoutMs();

        fixClient = new Client("fix42-exposure-it");
        fixClient.connect(properties.amps().uri());
        fixClient.logon(timeoutMs);

        AmpsDeltaPublisher publisher =
                new AmpsDeltaPublisher(fixClient, new PublishPlanner(properties), properties);
        List<FixEvent> events = MockFixFlow.events();
        for (FixEvent event : events) {
            publisher.send(event.message());
        }
        publisher.flush();
        log.info("published {} messages", events.size());
        sow = new SowReader(fixClient, timeoutMs);

        // The views are json-typed: their own connection.
        jsonClient = new Client("fix42-exposure-views-it");
        jsonClient.connect("tcp://127.0.0.1:" + server.port() + "/amps/json");
        jsonClient.logon(timeoutMs);
        views = new ViewReader(jsonClient, timeoutMs);

        awaitViewsCaughtUp();
        // What the server actually sent, for when an assertion below fails on
        // a shape rather than a number (a field's type, a null, a name).
        for (String view : List.of(PARENT_VIEW, CHILD_VIEW, CHILDREN_BY_PARENT_VIEW)) {
            log.info("{}: {}", view, views.records(view));
        }
    }

    /**
     * A publish is acknowledged once the SOW has it; the views catch up a
     * moment later. The last message of the flow is META's full fill, so the
     * META row reading filled is the sign the views have processed everything
     * ahead of it -- the update stream is ordered.
     */
    private void awaitViewsCaughtUp() {
        Awaitility.await("exposure views caught up with the published flow")
                .atMost(VIEW_SETTLE_TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    List<Exposure> parents = exposures(PARENT_VIEW);
                    assertThat(parents).hasSize(7);
                    assertThat(parents)
                            .filteredOn(row -> "META".equals(row.symbol()))
                            .singleElement()
                            .satisfies(meta -> {
                                assertThat(meta.cumQty()).isEqualTo(3_000);
                                assertThat(meta.leavesQty()).isZero();
                            });
                    assertThat(exposures(CHILD_VIEW)).hasSize(1);
                    assertThat(views.records(CHILDREN_BY_PARENT_VIEW)).hasSize(1);
                });
    }

    @AfterAll
    void tearDown() {
        if (jsonClient != null) {
            jsonClient.close();
        }
        if (fixClient != null) {
            fixClient.close();
        }
        if (server != null) {
            server.close();
        }
    }

    // ---- parents ------------------------------------------------------------

    @Test
    @DisplayName("the parent view holds one row per account/symbol/side, with the venue's totals")
    void parentViewAggregatesTheParentOrders() throws Exception {
        // Each row is one parent chain here because no two parents share an
        // account, symbol and side; the numbers are the blotter's own 38, 151,
        // 14 and 6 after the last report of each chain. Where they come from:
        assertThat(exposures(PARENT_VIEW)).containsExactlyInAnyOrder(
                // amended 10000 -> 12000, filled in three parts
                new Exposure("ACC-INSTL-01", "AAPL", BUY, 1, 12_000, 0, 12_000, "185.6333"),
                // 1500 filled, then cancelled: 151 to zero, 14 untouched
                new Exposure("ACC-INSTL-01", "MSFT", SELL, 1, 5_000, 0, 1_500, "410.2"),
                // never filled; the cancel was rejected, so still working 800
                new Exposure("ACC-HEDGE-07", "GOOG", BUY, 1, 800, 800, 0, null),
                // amend rejected (38 stays 4000), 1000 filled, done for day
                new Exposure("ACC-HEDGE-07", "NVDA", BUY, 1, 4_000, 0, 1_000, "121.75"),
                // the parent of the two child slices: filled as they filled
                new Exposure("ACC-INSTL-02", "TSLA", BUY, 1, 20_000, 0, 16_000, "242.0931"),
                // 3000 filled, first 2000 busted: restated to 1000 working 5000
                new Exposure("ACC-INSTL-01", "AMZN", BUY, 1, 6_000, 5_000, 1_000, "210.05"),
                // 1200 corrected to 511.95 before the closing 1800 at 512.00
                new Exposure("ACC-HEDGE-07", "META", SELL, 1, 3_000, 0, 3_000, "511.98"));
    }

    @Test
    @DisplayName("a group with no fills renders AvgPx as null, so the VWAP needs no guard")
    void unfilledGroupRendersNullAvgPx() throws Exception {
        // GOOG was acked and never filled: SUM(14 * 6) / SUM(14) is 0 / 0,
        // which a json-typed view renders as null (a fix-typed one prints 0).
        // This is the case an IF(SUM(/14) > 0, ..., 0) guard would exist for,
        // and the reason the views do without one: on this build an aggregate
        // inside IF() drifts on live updates.
        JsonObject google = row(PARENT_VIEW, "GOOG");

        assertThat(ViewReader.quantity(google, "CumQty")).isZero();
        assertThat(ViewReader.quantity(google, "LeavesQty")).isEqualTo(800);
        assertThat(google.has("AvgPx")).as("the field is projected").isTrue();
        assertThat(google.get("AvgPx").isJsonNull()).as("but holds JSON null").isTrue();
    }

    // ---- children -----------------------------------------------------------

    @Test
    @DisplayName("the child view rolls up the slices on their own, VWAP across them")
    void childViewAggregatesTheChildSlices() throws Exception {
        // Two TSLA slices under one account and side: A amended to 12000 and
        // filled in full, B filled 4000 of 8000 and cancelled.
        assertThat(exposures(CHILD_VIEW)).containsExactly(
                new Exposure("ACC-INSTL-02", "TSLA", BUY, 2, 20_000, 0, 16_000, "242.0931"));
    }

    @Test
    @DisplayName("AvgPx is the volume-weighted average of the group, not the mean of its prices")
    void avgPxIsVolumeWeighted() throws Exception {
        // Recomputed from the two child blotter records the view aggregates,
        // so the assertion does not depend on the scripted numbers: the row's
        // AvgPx must equal SUM(14 * 6) / SUM(14) over them. The unweighted
        // mean of the two AvgPx values (242.0908 and 242.1) would be 242.0954.
        List<FixMessage> slices = childRecords("PARENT-TSLA-1");
        assertThat(slices).hasSize(2);
        double notional = 0;
        long shares = 0;
        for (FixMessage slice : slices) {
            long cumQty = Long.parseLong(slice.value(FixTags.CUM_QTY));
            notional += cumQty * Double.parseDouble(slice.value(FixTags.AVG_PX));
            shares += cumQty;
        }

        Exposure row = exposures(CHILD_VIEW).getFirst();
        assertThat(row.cumQty()).isEqualTo(shares);
        assertThat(row.avgPx()).isEqualTo(Prices.plain(notional / shares));
    }

    // ---- the two levels ------------------------------------------------------

    @Test
    @DisplayName("parents and children are split on tag 9000, so no share is counted twice")
    void parentAndChildLevelsAreNeverSummedTogether() throws Exception {
        // The TSLA parent's 16000 IS its children's 16000: the venue reports
        // each slice's fill to the parent as well. One view over the whole
        // blotter would show TSLA/ACC-INSTL-02 as three orders and 32000
        // filled. The parent view sees only the parent record...
        Exposure parent = Exposure.of(row(PARENT_VIEW, "TSLA"));
        assertThat(parent.orders()).isEqualTo(1);
        assertThat(parent.cumQty()).isEqualTo(16_000);

        // ...and the child view only the slices, so the two never overlap.
        Exposure children = Exposure.of(row(CHILD_VIEW, "TSLA"));
        assertThat(children.orders()).isEqualTo(2);
        assertThat(children.cumQty()).isEqualTo(16_000);

        // Which is also visible in the counts: seven parent chains, two
        // slices, and every blotter record in exactly one of the two views.
        long parentOrders = exposures(PARENT_VIEW).stream().mapToLong(Exposure::orders).sum();
        long childOrders = exposures(CHILD_VIEW).stream().mapToLong(Exposure::orders).sum();
        assertThat(parentOrders).isEqualTo(7);
        assertThat(childOrders).isEqualTo(2);
        assertThat(parentOrders + childOrders).isEqualTo(sow.records(ORDERS).size());
    }

    @Test
    @DisplayName("children_by_parent agrees with the parent's own blotter record")
    void childrenByParentAgreesWithTheParentRecord() throws Exception {
        // The consistency check the third view is for. The scripted parent's
        // fills mirror its children's one for one, so its record and the
        // roll-up of its slices must say the same thing.
        List<JsonObject> rollups = views.records(CHILDREN_BY_PARENT_VIEW);
        assertThat(rollups).hasSize(1);
        JsonObject rollup = rollups.getFirst();
        assertThat(ViewReader.text(rollup, "ParentOrderID")).isEqualTo("PARENT-TSLA-1");
        assertThat(ViewReader.quantity(rollup, "Orders")).isEqualTo(2);

        FixMessage parent = parentRecord("PARENT-TSLA-1");
        assertThat(ViewReader.quantity(rollup, "OrderQty"))
                .as("the slices' quantities add up to the parent's")
                .isEqualTo(Long.parseLong(parent.value(FixTags.ORDER_QTY)));
        assertThat(ViewReader.quantity(rollup, "CumQty"))
                .as("what the slices filled is what the parent reports filled")
                .isEqualTo(Long.parseLong(parent.value(FixTags.CUM_QTY)));
        assertThat(ViewReader.quantity(rollup, "LeavesQty"))
                .as("nothing working on either side")
                .isEqualTo(Long.parseLong(parent.value(FixTags.LEAVES_QTY)));
        // AvgPx agrees to the four places the venue rounds tag 6 to. Exact
        // equality is not on offer: the parent's 6 is a running average rounded
        // at every fill, the roll-up a single division over the slices' 6.
        Double avgPx = ViewReader.price(rollup, "AvgPx");
        assertThat(avgPx).isNotNull();
        assertThat(Prices.plain(avgPx))
                .isEqualTo(Prices.plain(Double.parseDouble(parent.value(FixTags.AVG_PX))));
    }

    // ---- helpers ------------------------------------------------------------

    private List<Exposure> exposures(String view) throws Exception {
        return views.records(view).stream().map(Exposure::of).toList();
    }

    /** The single row of {@code view} for {@code symbol}, asserting there is one. */
    private JsonObject row(String view, String symbol) throws Exception {
        List<JsonObject> matching = views.records(view).stream()
                .filter(record -> symbol.equals(ViewReader.text(record, "Symbol")))
                .toList();
        assertThat(matching).as("exactly one %s row in %s", symbol, view).hasSize(1);
        return matching.getFirst();
    }

    /** The parent chain's blotter record: the one working under {@code clOrdId}. */
    private FixMessage parentRecord(String clOrdId) throws Exception {
        List<FixMessage> matching = sow.records(ORDERS).stream()
                .filter(record -> !record.has(FixTags.PARENT_ORDER_ID))
                .filter(record -> clOrdId.equals(record.value(FixTags.WORKING_CL_ORD_ID)))
                .toList();
        assertThat(matching).as("exactly one parent record working as %s", clOrdId).hasSize(1);
        return matching.getFirst();
    }

    /** The blotter records of every slice whose tag 9000 names {@code parentClOrdId}. */
    private List<FixMessage> childRecords(String parentClOrdId) throws Exception {
        return sow.records(ORDERS).stream()
                .filter(record -> parentClOrdId.equals(record.value(FixTags.PARENT_ORDER_ID)))
                .toList();
    }
}
