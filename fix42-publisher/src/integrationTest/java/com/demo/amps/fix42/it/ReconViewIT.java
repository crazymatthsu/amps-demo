package com.demo.amps.fix42.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.mock.FixEvent;
import com.demo.amps.fix42.mock.MockFixFlow;
import com.demo.amps.fix42.mock.OrderChain;
import com.demo.amps.fix42.publish.AmpsDeltaPublisher;
import com.demo.amps.fix42.publish.PublishInstruction;
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
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The reconciliation view: the parent exposure level joined to the child
 * level on account x symbol x side, read live before and after a deliberate
 * break.
 *
 * <p>The scripted flow reconciles by construction. The TSLA parent reports
 * one partial fill per child fill, so the two levels agree and the view's
 * deltas for TSLA are zero, while every other parent has no slices and shows
 * JSON null in the child columns. The suite then publishes one execution
 * report the mock never sends: a fill on the cancelled slice B that its parent
 * never mirrors. That moves the child level and nothing else, which is the one
 * shape a break has, and the same live view is read before and after it with
 * no restart in between. The view's expressions are bare for the reason the
 * exposure views' aggregates are: on this build an aggregate inside
 * {@code IF()} drifts on exactly the update path this test exercises.
 *
 * <p>The break is selected by the reader. {@code <Filter>} is not supported on
 * a join view, so the view carries every parent group and a filtered sow
 * query, {@link #BREAKS}, picks out the rows that disagree.
 *
 * <p>Ordered, because the second test mutates what the first asserts: the
 * clean flow must be read before the break is published.
 *
 * <p>Skipped, not failed, when no AMPS image is configured -- see
 * {@link AmpsTestServer#unavailableReason(AmpsFlow)}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class ReconViewIT {

    private static final Logger log = LoggerFactory.getLogger(ReconViewIT.class);

    private static final String ORDERS = "sow/fix42/orders";
    private static final String RECON_VIEW = "view/fix42/recon/parent_vs_child";

    /**
     * The reader's break selection. A row with no child level has JSON null
     * in every child column and in both deltas, which is "nothing to
     * reconcile", not a break; a populated child level that disagrees on
     * CumQty is one.
     */
    private static final String BREAKS = "/ChildCumQty IS NOT NULL AND /CumQtyDelta != 0";

    /** Tag 54 as FIX spells it; the view passes the code through unchanged. */
    private static final String BUY = "1";
    private static final String SELL = "2";

    private static final String TSLA_PARENT = "PARENT-TSLA-1";
    private static final String SLICE_B = "CHILD-TSLA-B";

    private static final Duration VIEW_SETTLE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * One row of the view, in the form the assertions compare.
     *
     * <p>The parent columns are always present: the join is a LEFT OUTER join
     * from the parent view, so every parent group has a row. The child columns
     * and the deltas are {@code null} for a group with no slices, which is
     * what the json view renders for a column the second topic has no record
     * for, and what a subtraction over it yields.
     */
    record Recon(String account, String symbol, String side,
                 long parentOrders, Long childOrders,
                 long parentCumQty, Long childCumQty, Long cumQtyDelta,
                 long parentLeavesQty, Long childLeavesQty, Long leavesQtyDelta) {

        static Recon of(JsonObject row) {
            return new Recon(
                    ViewReader.text(row, "Account"),
                    ViewReader.text(row, "Symbol"),
                    ViewReader.text(row, "Side"),
                    ViewReader.quantity(row, "ParentOrders"),
                    ViewReader.quantityOrNull(row, "ChildOrders"),
                    ViewReader.quantity(row, "ParentCumQty"),
                    ViewReader.quantityOrNull(row, "ChildCumQty"),
                    ViewReader.quantityOrNull(row, "CumQtyDelta"),
                    ViewReader.quantity(row, "ParentLeavesQty"),
                    ViewReader.quantityOrNull(row, "ChildLeavesQty"),
                    ViewReader.quantityOrNull(row, "LeavesQtyDelta"));
        }

        /** A parent group with no slices: a row, but nothing to reconcile. */
        static Recon unsliced(String account, String symbol, String side, long orders,
                              long cumQty, long leavesQty) {
            return new Recon(account, symbol, side, orders, null, cumQty, null, null,
                    leavesQty, null, null);
        }

        /** Both levels present; the deltas are parent minus child, as the view projects them. */
        static Recon sliced(String account, String symbol, String side,
                            long parentOrders, long childOrders,
                            long parentCumQty, long childCumQty,
                            long parentLeavesQty, long childLeavesQty) {
            return new Recon(account, symbol, side, parentOrders, childOrders,
                    parentCumQty, childCumQty, parentCumQty - childCumQty,
                    parentLeavesQty, childLeavesQty, parentLeavesQty - childLeavesQty);
        }
    }

    private AmpsTestServer server;
    private Client fixClient;
    private Client jsonClient;
    private AmpsDeltaPublisher publisher;
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

        fixClient = new Client("fix42-recon-it");
        fixClient.connect(properties.amps().uri());
        fixClient.logon(timeoutMs);

        publisher = new AmpsDeltaPublisher(fixClient, new PublishPlanner(properties), properties);
        List<FixEvent> events = MockFixFlow.events();
        for (FixEvent event : events) {
            publisher.send(event.message());
        }
        publisher.flush();
        log.info("published {} messages", events.size());
        sow = new SowReader(fixClient, timeoutMs);

        // The view is json-typed: its own connection.
        jsonClient = new Client("fix42-recon-view-it");
        jsonClient.connect("tcp://127.0.0.1:" + server.port() + "/amps/json");
        jsonClient.logon(timeoutMs);
        views = new ViewReader(jsonClient, timeoutMs);

        awaitViewCaughtUp();
        log.info("{}: {}", RECON_VIEW, views.records(RECON_VIEW));
    }

    /**
     * A publish is acknowledged once the SOW has it; the exposure views catch
     * up a moment later, and the join a moment after that. The last message of
     * the flow is META's full fill, so META reading filled on the parent side
     * is the sign the parent level has processed everything ahead of it; the
     * TSLA row carrying its child level is the sign the child side, and the
     * join over it, have too.
     */
    private void awaitViewCaughtUp() {
        Awaitility.await("reconciliation view caught up with the published flow")
                .atMost(VIEW_SETTLE_TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    List<Recon> rows = recon();
                    assertThat(rows).hasSize(7);
                    assertThat(rows)
                            .filteredOn(row -> "META".equals(row.symbol()))
                            .singleElement()
                            .satisfies(meta -> {
                                assertThat(meta.parentCumQty()).isEqualTo(3_000);
                                assertThat(meta.parentLeavesQty()).isZero();
                            });
                    assertThat(rows)
                            .filteredOn(row -> "TSLA".equals(row.symbol()))
                            .singleElement()
                            .satisfies(tesla -> assertThat(tesla.childCumQty()).isEqualTo(16_000));
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

    @Test
    @Order(1)
    @DisplayName("the scripted flow reconciles: TSLA agrees at both levels, the rest have no child level")
    void cleanFlowReconciles() throws Exception {
        // One row per parent group, the parent columns from the parent view.
        // Six of the seven have no slices, and the join being LEFT OUTER from
        // the parent view keeps their rows with null child columns and null
        // deltas rather than dropping them. TSLA, the one sliced parent,
        // carries both levels and they agree: its parent reports one partial
        // per child fill, so the deltas are zero.
        assertThat(recon()).containsExactlyInAnyOrder(
                Recon.unsliced("ACC-INSTL-01", "AAPL", BUY, 1, 12_000, 0),
                Recon.unsliced("ACC-INSTL-01", "MSFT", SELL, 1, 1_500, 0),
                Recon.unsliced("ACC-HEDGE-07", "GOOG", BUY, 1, 0, 800),
                Recon.unsliced("ACC-HEDGE-07", "NVDA", BUY, 1, 1_000, 0),
                Recon.sliced("ACC-INSTL-02", "TSLA", BUY, 1, 2, 16_000, 16_000, 0, 0),
                Recon.unsliced("ACC-INSTL-01", "AMZN", BUY, 1, 1_000, 5_000),
                Recon.unsliced("ACC-HEDGE-07", "META", SELL, 1, 3_000, 0));

        // And the reader's break query, which is how a desk would watch this
        // view, finds nothing to report.
        assertThat(views.records(RECON_VIEW, BREAKS)).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("a child fill the parent never mirrors surfaces as exactly one break, live")
    void lateChildFillIsOneBreak() throws Exception {
        // The scripted slice B filled 4000 of 8000 and was cancelled. This is
        // a report the mock never sends: a further 1000 on that slice, taking
        // its CumQty to 5000 with 3000 back working, with no matching partial
        // on the parent. A venue would have sent it to the same chain, so it
        // carries the slice's working ClOrdID and, as its own reports do, the
        // cancel's predecessor in tag 41.
        OrderChain sliceB = MockFixFlow.chains().stream()
                .filter(chain -> SLICE_B.equals(chain.chainId()))
                .findFirst()
                .orElseThrow();
        FixMessage lateFill = FixMessage.ofType(FixTags.MsgType.EXECUTION_REPORT)
                .set(FixTags.ORDER_ID, sliceB.orderId())
                .set(FixTags.CL_ORD_ID, sliceB.currentClOrdId())
                .set(FixTags.PARENT_ORDER_ID, TSLA_PARENT)
                .set(FixTags.ORIG_CL_ORD_ID, SLICE_B + "-1")
                .set(FixTags.EXEC_ID, "EXEC-" + SLICE_B + "-99")
                .set(FixTags.EXEC_TRANS_TYPE, FixTags.ExecTransType.NEW)
                .set(FixTags.EXEC_TYPE, FixTags.ExecType.PARTIAL_FILL)
                .set(FixTags.ORD_STATUS, FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.ACCOUNT, "ACC-INSTL-02")
                .set(FixTags.SYMBOL, "TSLA")
                .set(FixTags.SIDE, BUY)
                .set(FixTags.ORDER_QTY, 8_000)
                .setDecimal(FixTags.PRICE, 242.00)
                .set(FixTags.CUM_QTY, 5_000)
                .set(FixTags.LEAVES_QTY, 3_000)
                .setDecimal(FixTags.AVG_PX, 242.10)
                .set(FixTags.LAST_SHARES, 1_000)
                .setDecimal(FixTags.LAST_PX, 242.10)
                .set(FixTags.TRANSACT_TIME, "20260821-13:45:00.000")
                .build();

        List<PublishInstruction> sent = publisher.send(lateFill);
        publisher.flush();
        // The partial-fill route projects the economics onto the blotter,
        // which is the only topic the views read.
        assertThat(sent).extracting(PublishInstruction::topic).contains(ORDERS);

        // The break appears on the live view, without a restart: the child
        // view's TSLA row moved to 17000, the parent's stayed at 16000, and
        // the join recomputed the deltas.
        Awaitility.await("the late fill surfaces as a break")
                .atMost(VIEW_SETTLE_TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(views.records(RECON_VIEW, BREAKS)).hasSize(1));

        List<Recon> breaks = views.records(RECON_VIEW, BREAKS).stream().map(Recon::of).toList();
        assertThat(breaks).containsExactly(
                Recon.sliced("ACC-INSTL-02", "TSLA", BUY, 1, 2, 16_000, 17_000, 0, 3_000));
        Recon tesla = breaks.getFirst();
        assertThat(tesla.cumQtyDelta()).as("parent minus child, so a child ahead is negative")
                .isEqualTo(-1_000);
        assertThat(tesla.leavesQtyDelta()).isEqualTo(-3_000);

        // The other six rows are untouched: still one row each, still nothing
        // on the child side. The break is a row's columns changing, never a
        // row appearing or disappearing.
        List<Recon> rows = recon();
        assertThat(rows).hasSize(7);
        assertThat(rows).filteredOn(row -> !"TSLA".equals(row.symbol()))
                .hasSize(6)
                .allSatisfy(row -> {
                    assertThat(row.childCumQty()).isNull();
                    assertThat(row.cumQtyDelta()).isNull();
                });

        // Traced to the record that moved: slice B's own blotter record now
        // reads 5000 filled, 3000 working, and the chain did not split: the
        // parent still has exactly two slices on the blotter.
        FixMessage record = sow.recordsBy(ORDERS, FixTags.CL_ORD_ID).get(sliceB.currentClOrdId());
        assertThat(record).as("slice B's blotter record, under its working ClOrdID").isNotNull();
        assertThat(record.value(FixTags.CUM_QTY)).isEqualTo("5000");
        assertThat(record.value(FixTags.LEAVES_QTY)).isEqualTo("3000");
        assertThat(sow.records(ORDERS).stream()
                .filter(r -> TSLA_PARENT.equals(r.value(FixTags.PARENT_ORDER_ID)))
                .toList())
                .hasSize(2);
    }

    // ---- helpers -----------------------------------------------------------

    private List<Recon> recon() throws Exception {
        return views.records(RECON_VIEW).stream().map(Recon::of).toList();
    }
}
