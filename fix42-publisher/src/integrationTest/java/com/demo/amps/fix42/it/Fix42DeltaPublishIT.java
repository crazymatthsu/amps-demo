package com.demo.amps.fix42.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.mock.FixEvent;
import com.demo.amps.fix42.mock.MockFixFlow;
import com.demo.amps.fix42.publish.AmpsDeltaPublisher;
import com.demo.amps.fix42.publish.PublishPlanner;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole pipeline against a real AMPS instance: publish the mock FIX 4.2
 * flow into the {@code fix42-chaining} flow's topics, then read the SOW back
 * and check it says what it should.
 *
 * <p>The claim under test is the one the design rests on: <b>the AMPS chaining
 * key generator collapses a cancel/replace chain to one record, and
 * delta_publish merges each message into it without disturbing the fields
 * nobody sent.</b> Everything else here is corroboration.
 *
 * <p>Skipped, not failed, when no AMPS image is configured -- see
 * {@link AmpsTestServer#unavailableReason()}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fix42DeltaPublishIT {

    private static final Logger log = LoggerFactory.getLogger(Fix42DeltaPublishIT.class);

    private static final String PARENT_ORDERS = "sow/parent/orders";
    private static final String PARENT_ORDERS_AUDIT = "sow/parent/orders_audit";
    private static final String CHILD_ORDERS = "sow/child/orders";
    private static final String CHILD_ORDERS_AUDIT = "sow/child/orders_audit";
    private static final String PARENT_EXECS = "sow/parent/execs";
    private static final String PARENT_EXECS_AUDIT = "sow/parent/execs_audit";
    private static final String PARENT_REJECTS = "sow/parent/rejects";

    private AmpsTestServer server;
    private AmpsDeltaPublisher publisher;
    private Client client;
    private SowReader sow;

    @BeforeAll
    void publishTheFlow() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.FIX42_CHAINING);
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        server = AmpsTestServer.start(AmpsFlow.FIX42_CHAINING);

        // The real rulebook from application.yml, pointed at this instance --
        // so a change to the shipped configuration is exercised here too,
        // rather than against a copy that can drift.
        Fix42Properties properties = Fix42Configurations.shipped(server.uri());
        properties.validate();

        client = new Client("fix42-it");
        client.connect(properties.amps().uri());
        client.logon(properties.amps().timeoutMs());

        publisher = new AmpsDeltaPublisher(client, new PublishPlanner(properties), properties);

        List<FixEvent> events = MockFixFlow.events();
        for (FixEvent event : events) {
            publisher.send(event.message());
        }
        publisher.flush();
        log.info("published {} messages: {} full, {} delta", events.size(),
                publisher.fullPublishCount(), publisher.deltaPublishCount());

        sow = new SowReader(client, properties.amps().timeoutMs());
    }

    @AfterAll
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    // ---- the headline claim -------------------------------------------------

    @Test
    @DisplayName("a whole cancel/replace chain collapses to ONE record on the chained topic")
    void chainCollapsesToOneRecord() throws Exception {
        List<FixMessage> parents = sow.records(PARENT_ORDERS);

        // Seven parent chains published D/G/F under eleven distinct ClOrdIDs;
        // the chaining key generator resolves 11/41 back to one key per chain.
        assertThat(parents)
                .as("one record per parent order chain, not one per ClOrdID")
                .hasSize(7);

        Map<String, FixMessage> audit = sow.recordsBy(PARENT_ORDERS_AUDIT, FixTags.CL_ORD_ID);
        assertThat(audit.keySet())
                .as("the audit topic keeps every ClOrdID separately")
                .hasSizeGreaterThan(parents.size())
                .contains("PARENT-AAPL-1", "PARENT-AAPL-2", "PARENT-GOOG-2");
    }

    @Test
    @DisplayName("the merged record carries the latest amend AND the original order's terms")
    void deltaMergePreservesUntouchedFields() throws Exception {
        FixMessage apple = chainRecord(PARENT_ORDERS, "AAPL");

        // The last message to reach the blotter was the venue's replace
        // confirm (150=5), which is why tag 35 is 8 rather than G: the request
        // stages, the report decides.
        assertThat(apple.value(FixTags.MSG_TYPE)).isEqualTo("8");
        assertThat(apple.value(FixTags.CL_ORD_ID)).isEqualTo("PARENT-AAPL-2");
        assertThat(apple.value(FixTags.ORIG_CL_ORD_ID)).isEqualTo("PARENT-AAPL-1");
        // ...the amended terms, as the VENUE stated them:
        assertThat(apple.value(FixTags.ORDER_QTY)).isEqualTo("12000");
        assertThat(apple.value(FixTags.PRICE)).isEqualTo("185.75");
        // ...and, untouched by any delta since the original 35=D, its terms:
        assertThat(apple.value(FixTags.SYMBOL)).isEqualTo("AAPL");
        assertThat(apple.value(FixTags.SIDE)).isEqualTo("1");
        assertThat(apple.value(FixTags.ACCOUNT)).isEqualTo("ACC-INSTL-01");
        assertThat(apple.value(FixTags.ORD_TYPE)).isEqualTo("2");
        assertThat(apple.value(FixTags.CURRENCY)).isEqualTo("USD");
        assertThat(apple.value(FixTags.EX_DESTINATION)).isEqualTo("XNAS");
    }

    @Test
    @DisplayName("a cancel request merges onto the same record as the order it cancels")
    void cancelRequestJoinsTheChain() throws Exception {
        FixMessage microsoft = chainRecord(PARENT_ORDERS, "MSFT");

        // Last to touch the blotter is the cancel confirmation (150=4).
        assertThat(microsoft.value(FixTags.MSG_TYPE)).isEqualTo("8");
        assertThat(microsoft.value(FixTags.CL_ORD_ID)).isEqualTo("PARENT-MSFT-2");
        assertThat(microsoft.value(FixTags.ORIG_CL_ORD_ID)).isEqualTo("PARENT-MSFT-1");
        // Neither the cancel request nor its confirmation carried a quantity,
        // so 38 is still the one the original 35=D established.
        assertThat(microsoft.value(FixTags.ORDER_QTY)).isEqualTo("5000");
        assertThat(microsoft.value(FixTags.PRICE)).isEqualTo("410.25");
        assertThat(microsoft.value(FixTags.SYMBOL)).isEqualTo("MSFT");
    }

    // ---- parent / child separation -----------------------------------------

    @Test
    @DisplayName("child slices land on the child topics, keyed by their own chains")
    void childOrdersAreSeparate() throws Exception {
        List<FixMessage> children = sow.records(CHILD_ORDERS);

        assertThat(children).as("two child chains").hasSize(2);
        assertThat(children).allSatisfy(record -> {
            assertThat(record.value(FixTags.PARENT_ORDER_ID))
                    .as("every child record keeps its parent link in tag 9000")
                    .isEqualTo("PARENT-TSLA-1");
            assertThat(record.value(FixTags.SYMBOL)).isEqualTo("TSLA");
        });

        Map<String, FixMessage> audit = sow.recordsBy(CHILD_ORDERS_AUDIT, FixTags.CL_ORD_ID);
        assertThat(audit.keySet())
                .contains("CHILD-TSLA-A-1", "CHILD-TSLA-A-2", "CHILD-TSLA-B-1", "CHILD-TSLA-B-2");
    }

    @Test
    @DisplayName("the parent's own chain is not polluted by its children")
    void parentAndChildTopicsDoNotMix() throws Exception {
        assertThat(sow.records(PARENT_ORDERS))
                .as("no child slice should appear among the parent orders")
                .allSatisfy(record ->
                        assertThat(record.has(FixTags.PARENT_ORDER_ID)).isFalse());
    }

    // ---- executions ---------------------------------------------------------

    @Test
    @DisplayName("execs keyed on OrderID hold the latest report per order")
    void execsHoldLatestPerOrder() throws Exception {
        Map<String, FixMessage> execs = sow.recordsBy(PARENT_EXECS, FixTags.ORDER_ID);

        // One per chain that ever reached the venue -- all nine.
        assertThat(execs).hasSize(9);

        FixMessage apple = execs.get("ORD-PARENT-AAPL");
        assertThat(apple).isNotNull();
        // The AAPL chain ended fully filled: 12000 done, nothing working.
        assertThat(apple.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.FILLED);
        assertThat(apple.value(FixTags.EXEC_TYPE)).isEqualTo(FixTags.ExecType.FILL);
        assertThat(apple.value(FixTags.CUM_QTY)).isEqualTo("12000");
        assertThat(apple.value(FixTags.LEAVES_QTY)).isEqualTo("0");

        FixMessage microsoft = execs.get("ORD-PARENT-MSFT");
        assertThat(microsoft.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.CANCELED);
        assertThat(microsoft.value(FixTags.LEAVES_QTY)).isEqualTo("0");
        // Cancelled after a partial fill: the fill is still in the record, from
        // the delta that reported it. Nothing overwrote it, because the cancel
        // confirmation only sends 151.
        assertThat(microsoft.value(FixTags.CUM_QTY)).isEqualTo("1500");

        // AMZN's latest report is the bust: restated totals, reference intact.
        FixMessage amazon = execs.get("ORD-PARENT-AMZN");
        assertThat(amazon.value(FixTags.EXEC_TRANS_TYPE)).isEqualTo(FixTags.ExecTransType.CANCEL);
        assertThat(amazon.value(FixTags.EXEC_REF_ID)).isEqualTo("EXEC-PARENT-AMZN-2");
        assertThat(amazon.value(FixTags.CUM_QTY)).isEqualTo("1000");
        assertThat(amazon.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.PARTIALLY_FILLED);
    }

    @Test
    @DisplayName("execs_audit keyed on ExecID keeps every execution report")
    void execsAuditKeepsEveryReport() throws Exception {
        long expected = MockFixFlow.events().stream()
                .filter(event -> FixTags.MsgType.EXECUTION_REPORT.equals(event.msgType()))
                .count();

        Map<String, FixMessage> audit = sow.recordsBy(PARENT_EXECS_AUDIT, FixTags.EXEC_ID);

        assertThat(audit).as("one record per ExecID, none merged away").hasSize((int) expected);
        assertThat(audit.keySet()).allSatisfy(execId -> assertThat(execId).startsWith("EXEC-"));
    }

    @Test
    @DisplayName("a fill report carries this fill and the cumulative snapshot together")
    void fillReportsCarryBothFillAndSnapshot() throws Exception {
        Map<String, FixMessage> audit = sow.recordsBy(PARENT_EXECS_AUDIT, FixTags.EXEC_ID);

        // PARENT-AAPL: ack, partial, pending-replace, replace-ack, partial, fill.
        FixMessage firstPartial = audit.get("EXEC-PARENT-AAPL-2");
        assertThat(firstPartial.value(FixTags.EXEC_TYPE)).isEqualTo(FixTags.ExecType.PARTIAL_FILL);
        assertThat(firstPartial.value(FixTags.LAST_SHARES)).isEqualTo("2000");
        assertThat(firstPartial.value(FixTags.LAST_PX)).isEqualTo("185.45");
        assertThat(firstPartial.value(FixTags.CUM_QTY)).isEqualTo("2000");
        assertThat(firstPartial.value(FixTags.AVG_PX)).isEqualTo("185.45");

        // The ack before it reports no economics, and was configured to send none.
        FixMessage ack = audit.get("EXEC-PARENT-AAPL-1");
        assertThat(ack.value(FixTags.EXEC_TYPE)).isEqualTo(FixTags.ExecType.NEW);
        assertThat(ack.has(FixTags.LAST_SHARES)).isFalse();
        assertThat(ack.has(FixTags.CUM_QTY)).isFalse();
    }

    // ---- rejects ------------------------------------------------------------

    @Test
    @DisplayName("cancel rejects land keyed on the rejected request's ClOrdID")
    void rejectsAreKeyedByRejectedRequest() throws Exception {
        Map<String, FixMessage> rejects = sow.recordsBy(PARENT_REJECTS, FixTags.CL_ORD_ID);

        assertThat(rejects).hasSize(2);

        FixMessage cancelReject = rejects.get("PARENT-GOOG-2");
        assertThat(cancelReject.value(FixTags.CXL_REJ_RESPONSE_TO))
                .as("434=1 means a cancel was rejected")
                .isEqualTo("1");
        assertThat(cancelReject.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.NEW);
        assertThat(cancelReject.value(FixTags.TEXT)).isEqualTo("too late to cancel");

        FixMessage amendReject = rejects.get("PARENT-NVDA-2");
        assertThat(amendReject.value(FixTags.CXL_REJ_RESPONSE_TO))
                .as("434=2 means a replace was rejected")
                .isEqualTo("2");
        assertThat(amendReject.value(FixTags.ORD_STATUS))
                .isEqualTo(FixTags.OrdStatus.PARTIALLY_FILLED);
    }

    @Test
    @DisplayName("a stored fill record reconciles against itself: 38 = 14 + 151")
    void storedFillCarriesTheQuantityInvariant() throws Exception {
        Map<String, FixMessage> audit = sow.recordsBy(PARENT_EXECS_AUDIT, FixTags.EXEC_ID);

        // AAPL's first partial: 2000 of 10000 at 185.45.
        FixMessage fill = audit.get("EXEC-PARENT-AAPL-2");
        assertThat(fill.value(FixTags.LAST_SHARES)).isEqualTo("2000");
        assertThat(fill.value(FixTags.LAST_PX)).isEqualTo("185.45");
        assertThat(fill.value(FixTags.CUM_QTY)).isEqualTo("2000");
        assertThat(fill.value(FixTags.LEAVES_QTY)).isEqualTo("8000");

        // OrderQty rides along on the fill, so the invariant is checkable from
        // the stored record alone rather than needing another report type.
        long orderQty = Long.parseLong(fill.value(FixTags.ORDER_QTY));
        long cumQty = Long.parseLong(fill.value(FixTags.CUM_QTY));
        long leavesQty = Long.parseLong(fill.value(FixTags.LEAVES_QTY));
        assertThat(cumQty + leavesQty).isEqualTo(orderQty);
    }

    @Test
    @DisplayName("every stored fill reconciles; no stored ack claims a trade")
    void storedExecutionsAreSelfConsistent() throws Exception {
        for (FixMessage record : sow.records(PARENT_EXECS_AUDIT)) {
            String execType = record.value(FixTags.EXEC_TYPE);
            // A stored fill carries no tag 20 (the fill routes never select
            // it); busts and corrects carry 20=1/2 and an ordinary 150, so
            // tag 20 -- not 150 -- is what separates them here.
            String transType = record.value(FixTags.EXEC_TRANS_TYPE);
            boolean isRestatement = FixTags.ExecTransType.CANCEL.equals(transType)
                    || FixTags.ExecTransType.CORRECT.equals(transType);
            boolean isFill = !isRestatement
                    && (FixTags.ExecType.PARTIAL_FILL.equals(execType)
                            || FixTags.ExecType.FILL.equals(execType));

            if (isRestatement) {
                // Both name the execution they act on and restate absolutes
                // that reconcile; only a correct reports replacement trade
                // fields.
                assertThat(record.value(FixTags.EXEC_REF_ID))
                        .as("restatement %s names its target", record.value(FixTags.EXEC_ID))
                        .startsWith("EXEC-");
                long orderQty = Long.parseLong(record.value(FixTags.ORDER_QTY));
                long cumQty = Long.parseLong(record.value(FixTags.CUM_QTY));
                long leavesQty = Long.parseLong(record.value(FixTags.LEAVES_QTY));
                assertThat(cumQty + leavesQty).isEqualTo(orderQty);
                if (FixTags.ExecTransType.CANCEL.equals(transType)) {
                    assertThat(record.has(FixTags.LAST_SHARES))
                            .as("a bust reports no new trade")
                            .isFalse();
                } else {
                    assertThat(Long.parseLong(record.value(FixTags.LAST_SHARES)))
                            .as("a correct carries its replacement quantity")
                            .isPositive();
                }
            } else if (isFill) {
                long orderQty = Long.parseLong(record.value(FixTags.ORDER_QTY));
                long cumQty = Long.parseLong(record.value(FixTags.CUM_QTY));
                long leavesQty = Long.parseLong(record.value(FixTags.LEAVES_QTY));
                assertThat(cumQty + leavesQty)
                        .as("fill %s: 38=%d, 14=%d, 151=%d", record.value(FixTags.EXEC_ID),
                                orderQty, cumQty, leavesQty)
                        .isEqualTo(orderQty);
                assertThat(Long.parseLong(record.value(FixTags.LAST_SHARES)))
                        .as("a fill reports a traded quantity")
                        .isPositive();
            } else if (FixTags.ExecType.NEW.equals(execType)) {
                // An ack reports no trade, so the record carries no trade
                // fields at all -- this topic is keyed per ExecID, so nothing
                // merges into it from a neighbouring report.
                assertThat(record.has(FixTags.LAST_SHARES))
                        .as("ack %s must not claim a trade", record.value(FixTags.EXEC_ID))
                        .isFalse();
                assertThat(record.has(FixTags.LAST_PX)).isFalse();
            }
        }
    }

    // ---- busts and corrects -------------------------------------------------

    @Test
    @DisplayName("the blotter adopts the bust's restated totals -- and only those")
    void blotterAdoptsRestatedTotalsAfterBust() throws Exception {
        // AMZN filled 2000 @ 209.95 and 1000 @ 210.05, then the venue busted
        // the first fill. The bust's restated absolutes merged over the fill
        // deltas -- the same mechanism that applied them takes them back.
        FixMessage amazon = chainRecord(PARENT_ORDERS, "AMZN");

        assertThat(amazon.value(FixTags.CUM_QTY)).isEqualTo("1000");
        assertThat(amazon.value(FixTags.LEAVES_QTY)).isEqualTo("5000");
        assertThat(amazon.value(FixTags.ORDER_QTY)).isEqualTo("6000");
        // The exact recompute over the surviving fill, not a subtraction from
        // the rounded running average.
        assertThat(amazon.value(FixTags.AVG_PX)).isEqualTo("210.05");
        assertThat(amazon.value(FixTags.ORD_STATUS))
                .isEqualTo(FixTags.OrdStatus.PARTIALLY_FILLED);
        // 32/31 still hold the last REAL fill: a bust reports no new trade,
        // and its projection deliberately omits them.
        assertThat(amazon.value(FixTags.LAST_SHARES)).isEqualTo("1000");
        assertThat(amazon.value(FixTags.LAST_PX)).isEqualTo("210.05");
        // The reference pair stays off the blotter: 19/20 describe a PRIOR
        // execution and would sit stale on the merged record.
        assertThat(amazon.has(FixTags.EXEC_REF_ID)).isFalse();
        assertThat(amazon.has(FixTags.EXEC_TRANS_TYPE)).isFalse();
    }

    @Test
    @DisplayName("a corrected fill's economics flow through to the finished blotter record")
    void blotterReflectsCorrectedFillEconomics() throws Exception {
        // META's 1200 @ 512.10 was corrected to 511.95 before the final 1800
        // filled at 512.00. The terminal AvgPx is only 511.98 if the
        // correction actually applied -- it would read 512.04 otherwise.
        FixMessage meta = chainRecord(PARENT_ORDERS, "META");

        assertThat(meta.value(FixTags.CUM_QTY)).isEqualTo("3000");
        assertThat(meta.value(FixTags.LEAVES_QTY)).isEqualTo("0");
        assertThat(meta.value(FixTags.ORDER_QTY)).isEqualTo("3000");
        assertThat(meta.value(FixTags.AVG_PX)).isEqualTo("511.98");
        assertThat(meta.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.FILLED);
    }

    @Test
    @DisplayName("execs_audit keeps a bust/correct with tags 19/20, beside its untouched target")
    void execsAuditKeepsBustAndCorrectWithReferences() throws Exception {
        Map<String, FixMessage> audit = sow.recordsBy(PARENT_EXECS_AUDIT, FixTags.EXEC_ID);

        // The bust: its own ExecID, the reference pair, restated absolutes,
        // and no trade fields of its own.
        FixMessage bust = audit.get("EXEC-PARENT-AMZN-4");
        assertThat(bust.value(FixTags.EXEC_TRANS_TYPE)).isEqualTo(FixTags.ExecTransType.CANCEL);
        assertThat(bust.value(FixTags.EXEC_REF_ID)).isEqualTo("EXEC-PARENT-AMZN-2");
        assertThat(bust.value(FixTags.CUM_QTY)).isEqualTo("1000");
        assertThat(bust.has(FixTags.LAST_SHARES)).isFalse();

        // The busted fill's own audit record is untouched -- this topic is
        // keyed per ExecID, so the bust lands beside it, not on top of it.
        // Which execution actually stands is exactly what tag 19 is for.
        FixMessage bustedFill = audit.get("EXEC-PARENT-AMZN-2");
        assertThat(bustedFill.value(FixTags.LAST_SHARES)).isEqualTo("2000");
        assertThat(bustedFill.value(FixTags.LAST_PX)).isEqualTo("209.95");

        // The correct: reference pair plus the REPLACEMENT trade fields.
        FixMessage correct = audit.get("EXEC-PARENT-META-3");
        assertThat(correct.value(FixTags.EXEC_TRANS_TYPE)).isEqualTo(FixTags.ExecTransType.CORRECT);
        assertThat(correct.value(FixTags.EXEC_REF_ID)).isEqualTo("EXEC-PARENT-META-2");
        assertThat(correct.value(FixTags.LAST_SHARES)).isEqualTo("1200");
        assertThat(correct.value(FixTags.LAST_PX)).isEqualTo("511.95");
        assertThat(correct.value(FixTags.AVG_PX)).isEqualTo("511.95");
    }

    // ---- the pending-state family -------------------------------------------

    @Test
    @DisplayName("a rejected amend leaves the acked terms intact, with nothing to revert")
    void rejectedAmendNeverCorruptsTheAckedTerms() throws Exception {
        // NVDA asked to go 4000 -> 6000 and the venue rejected it (434=2).
        //
        // This is the case the whole pending-tag family exists for. The amend's
        // proposed terms went to 9010/9011, never to 38/44, so the reject has
        // nothing to undo -- it just clears the proposal. Compare with writing
        // the proposal into tag 38: a merge can overwrite but never remove, so
        // the record would keep a quantity the venue refused, and the only way
        // back would be re-publishing an older message over the top of it.
        FixMessage nvidia = chainRecord(PARENT_ORDERS, "NVDA");

        assertThat(nvidia.value(FixTags.ORDER_QTY))
                .as("the acked quantity was never overwritten by the proposal")
                .isEqualTo("4000");
        assertThat(nvidia.value(FixTags.PRICE)).isEqualTo("121.8");
        assertThat(nvidia.value(FixTags.PENDING_ACTION))
                .as("the rejected proposal has been cleared")
                .isEqualTo(FixTags.PendingAction.NONE);
        assertThat(nvidia.value(FixTags.PENDING_ORDER_QTY)).isEqualTo("0");
        assertThat(nvidia.value(FixTags.PENDING_PRICE)).isEqualTo("0");
        assertThat(nvidia.value(FixTags.PENDING_CL_ORD_ID)).isEqualTo("NONE");
        // The rejected request never became the working id.
        assertThat(nvidia.value(FixTags.WORKING_CL_ORD_ID)).isEqualTo("PARENT-NVDA-1");

        // And the blotter agrees with the venue, which is the real test.
        Map<String, FixMessage> execs = sow.recordsBy(PARENT_EXECS, FixTags.ORDER_ID);
        FixMessage venueView = execs.get("ORD-PARENT-NVDA");
        assertThat(venueView.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.DONE_FOR_DAY);
        assertThat(venueView.value(FixTags.CUM_QTY)).isEqualTo("1000");
    }

    @Test
    @DisplayName("a confirmed amend adopts the venue's terms and clears the proposal")
    void confirmedAmendAdoptsVenueTerms() throws Exception {
        // AAPL amended 10000@185.50 -> 12000@185.75 and the venue CONFIRMED it
        // with a 150=5 carrying its own 38/44. The blotter takes the venue's
        // numbers, not the ones we asked for -- the same principle the rest of
        // this design runs on, since a 4.2 report is a cumulative snapshot.
        FixMessage apple = chainRecord(PARENT_ORDERS, "AAPL");

        assertThat(apple.value(FixTags.ORDER_QTY)).isEqualTo("12000");
        assertThat(apple.value(FixTags.PRICE)).isEqualTo("185.75");
        assertThat(apple.value(FixTags.PENDING_ACTION)).isEqualTo(FixTags.PendingAction.NONE);
        assertThat(apple.value(FixTags.PENDING_ORDER_QTY)).isEqualTo("0");
        // The confirmed request's ClOrdID is now the working one.
        assertThat(apple.value(FixTags.WORKING_CL_ORD_ID)).isEqualTo("PARENT-AAPL-2");
        // Still carrying the original 35=D's terms, untouched throughout.
        assertThat(apple.value(FixTags.SYMBOL)).isEqualTo("AAPL");
        assertThat(apple.value(FixTags.ACCOUNT)).isEqualTo("ACC-INSTL-01");
    }

    @Test
    @DisplayName("a cancel in flight is visible as pending without altering the order")
    void cancelRequestShowsAsPending() throws Exception {
        // GOOG sent a 35=F which the venue rejected as too late, so the order
        // is still working -- pending cleared, terms untouched.
        FixMessage google = chainRecord(PARENT_ORDERS, "GOOG");

        assertThat(google.value(FixTags.PENDING_ACTION)).isEqualTo(FixTags.PendingAction.NONE);
        assertThat(google.value(FixTags.ORDER_QTY)).isEqualTo("800");
        assertThat(google.value(FixTags.WORKING_CL_ORD_ID)).isEqualTo("PARENT-GOOG-1");

        // MSFT's cancel was CONFIRMED, so its pending is cleared too -- the
        // difference between the two lives on the execs topic, not here.
        FixMessage microsoft = chainRecord(PARENT_ORDERS, "MSFT");
        assertThat(microsoft.value(FixTags.PENDING_ACTION))
                .isEqualTo(FixTags.PendingAction.NONE);
        Map<String, FixMessage> execs = sow.recordsBy(PARENT_EXECS, FixTags.ORDER_ID);
        assertThat(execs.get("ORD-PARENT-MSFT").value(FixTags.ORD_STATUS))
                .isEqualTo(FixTags.OrdStatus.CANCELED);
    }

    @Test
    @DisplayName("every blotter record carries a resolved pending state")
    void noRecordIsLeftPending() throws Exception {
        // Every scripted chain reaches a terminal or resolved point, so nothing
        // should still claim a request in flight. A record stuck on REPLACE
        // would mean a resolving report failed to project.
        for (String topic : List.of(PARENT_ORDERS, CHILD_ORDERS)) {
            assertThat(sow.records(topic)).allSatisfy(record -> {
                assertThat(record.value(FixTags.PENDING_ACTION))
                        .as("%s record %s", topic, record.value(FixTags.CL_ORD_ID))
                        .isEqualTo(FixTags.PendingAction.NONE);
                assertThat(record.value(FixTags.PENDING_ORDER_QTY)).isEqualTo("0");
            });
        }
    }

    @Test
    @DisplayName("the audit topic still records the amend exactly as it was sent")
    void auditKeepsTheUnprojectedMessage() throws Exception {
        // The projection is a blotter concern. An audit trail that showed
        // 9010=12000 instead of 38=12000 would be recording a rewrite rather
        // than the message.
        Map<String, FixMessage> audit = sow.recordsBy(PARENT_ORDERS_AUDIT, FixTags.CL_ORD_ID);

        FixMessage amend = audit.get("PARENT-AAPL-2");
        assertThat(amend.value(FixTags.MSG_TYPE)).isEqualTo("G");
        assertThat(amend.value(FixTags.ORDER_QTY)).isEqualTo("12000");
        assertThat(amend.value(FixTags.PRICE)).isEqualTo("185.75");
        assertThat(amend.has(FixTags.PENDING_ORDER_QTY))
                .as("no pending-family tags on the audit trail")
                .isFalse();
    }

    // ---- helpers ------------------------------------------------------------

    /** The single chained record for a symbol, asserting there is exactly one. */
    private FixMessage chainRecord(String topic, String symbol) throws Exception {
        List<FixMessage> matching = sow.records(topic).stream()
                .filter(record -> symbol.equals(record.value(FixTags.SYMBOL)))
                .toList();
        assertThat(matching)
                .as("exactly one chained record for %s in %s", symbol, topic)
                .hasSize(1);
        return matching.getFirst();
    }

}
