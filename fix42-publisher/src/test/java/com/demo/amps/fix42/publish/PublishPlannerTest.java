package com.demo.amps.fix42.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.mock.OrderScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Routing, without a server.
 *
 * <p>The planner is the part of the publisher with actual decisions in it, and
 * all of them are testable offline because it is stateless: one message in, a
 * list of publishes out.
 */
class PublishPlannerTest {

    private static final Map<String, List<Integer>> TOPIC_KEYS = Map.of(
            "sow/fix42/orders", List.of(11),
            "sow/fix42/orders_audit", List.of(11),
            "sow/fix42/execs", List.of(37),
            "sow/fix42/execs_audit", List.of(17),
            "sow/fix42/rejects", List.of(11));

    /**
     * A rulebook that splits the blotter by scope. Not what ships -- parents
     * and children share sow/fix42/orders -- but the placeholder is still a
     * supported knob, and this is what exercises it.
     */
    private static final Map<String, List<Integer>> SCOPED_TOPIC_KEYS = Map.of(
            "sow/parent/orders", List.of(11),
            "sow/parent/orders_audit", List.of(11),
            "sow/child/orders", List.of(11),
            "sow/child/orders_audit", List.of(11));

    private static PublishPlanner planner(Fix42Properties.Route... routes) {
        return planner(TOPIC_KEYS, routes);
    }

    private static PublishPlanner planner(Map<String, List<Integer>> topicKeys,
                                          Fix42Properties.Route... routes) {
        return new PublishPlanner(new Fix42Properties(
                new Fix42Properties.Amps("tcp://127.0.0.1:9007/amps/fix", "test", 10_000),
                topicKeys, List.of(routes)));
    }

    private static Fix42Properties.Route newOrderRoute() {
        return newOrderRoute("sow/fix42/orders", "sow/fix42/orders_audit");
    }

    private static Fix42Properties.Route newOrderRoute(String... topics) {
        return new Fix42Properties.Route("new-order", List.of("D"), List.of(), List.of(),
                PublishMode.FULL, List.of(), List.of(), List.of(topics), List.of(), null);
    }

    private static Fix42Properties.Route amendRoute() {
        return new Fix42Properties.Route("amend", List.of("G"), List.of(), List.of(),
                PublishMode.DELTA, List.of(35, 11, 41, 60), List.of(38, 44, 59),
                List.of("sow/fix42/orders", "sow/fix42/orders_audit"), List.of(), null);
    }

    private static Fix42Properties.Route execRoute(String name, List<String> execTypes,
                                                   List<Integer> changeable) {
        return new Fix42Properties.Route(name, List.of("8"), execTypes, List.of(),
                PublishMode.DELTA, List.of(35, 11, 41, 37, 17, 39, 150, 60), changeable,
                List.of("sow/fix42/execs", "sow/fix42/execs_audit"), List.of(), null);
    }

    /** The bust rule: matches on tag 20, carries the 19/20 reference pair. */
    private static Fix42Properties.Route bustRoute() {
        return new Fix42Properties.Route("exec-bust", List.of("8"), List.of(), List.of("1"),
                PublishMode.DELTA, List.of(35, 11, 41, 37, 17, 19, 20, 39, 150, 60),
                List.of(38, 14, 151, 6),
                List.of("sow/fix42/execs", "sow/fix42/execs_audit"), List.of(), null);
    }

    private static final List<String> EXEC_TOPICS =
            List.of("sow/fix42/execs", "sow/fix42/execs_audit");
    private static final List<Integer> EXEC_IDENTITY = List.of(35, 11, 41, 37, 17, 39, 150, 60);

    /**
     * What a terminal projection stamps: LeavesQty to zero, and the pending
     * family cleared. Ordered, so the projected payload has a stable shape.
     */
    private static Map<Integer, String> terminalSetTags() {
        Map<Integer, String> set = new LinkedHashMap<>();
        set.put(FixTags.LEAVES_QTY, "0");
        set.put(FixTags.PENDING_ACTION, FixTags.PendingAction.NONE);
        set.put(FixTags.PENDING_ORDER_QTY, "0");
        set.put(FixTags.PENDING_PRICE, "0");
        set.put(FixTags.PENDING_CL_ORD_ID, "NONE");
        return set;
    }

    /** The shipped cancel/done rule: terminal, projecting, 151 forced to zero. */
    private static Fix42Properties.Route cancelOrDoneRoute() {
        return new Fix42Properties.Route("exec-cancel-or-done", List.of("8"), List.of("4", "3"),
                List.of(), PublishMode.DELTA, EXEC_IDENTITY, List.of(151), EXEC_TOPICS,
                List.of("sow/fix42/orders"),
                new Fix42Properties.Projection(List.of(11, 41, 39, 150, 60, 14, 151, 6),
                        Map.of(), terminalSetTags()));
    }

    /** The shipped expiry/reject rule: the same terminal shape, with 103/58 for the exec topics. */
    private static Fix42Properties.Route expiredOrRejectedRoute() {
        return new Fix42Properties.Route("exec-expired-or-rejected", List.of("8"),
                List.of("C", "8"), List.of(), PublishMode.DELTA, EXEC_IDENTITY,
                List.of(14, 151, 6, 38, 103, 58), EXEC_TOPICS, List.of("sow/fix42/orders"),
                new Fix42Properties.Projection(List.of(11, 41, 39, 150, 60, 14, 151, 6),
                        Map.of(), terminalSetTags()));
    }

    /** The shipped restatement rule: adopts the restated terms, touches no pending tag. */
    private static Fix42Properties.Route restatedRoute() {
        return new Fix42Properties.Route("exec-restated", List.of("8"), List.of("D"), List.of(),
                PublishMode.DELTA, EXEC_IDENTITY, List.of(38, 44, 14, 151, 6, 378, 58),
                EXEC_TOPICS, List.of("sow/fix42/orders"),
                new Fix42Properties.Projection(List.of(11, 41, 39, 150, 60, 38, 44, 14, 151, 6),
                        Map.of(), Map.of()));
    }

    /** The shipped catch-all: the exec topics only, no blotter projection. */
    private static Fix42Properties.Route execOtherRoute() {
        return execRoute("exec-other", List.of(), List.of(14, 151, 6, 38, 44, 103, 58));
    }

    private static FixMessage blotterPayload(List<PublishInstruction> plan) {
        return plan.stream()
                .filter(instruction -> instruction.topic().equals("sow/fix42/orders"))
                .findFirst().orElseThrow().payload();
    }

    @Test
    @DisplayName("35=D publishes the whole message to both order topics")
    void newOrderGoesOutWhole() {
        FixMessage order = FixMessage.ofType("D")
                .set(FixTags.CL_ORD_ID, "C1")
                .set(FixTags.SYMBOL, "AAPL")
                .set(FixTags.ORDER_QTY, 1000)
                .setDecimal(FixTags.PRICE, 50.25)
                .build();

        List<PublishInstruction> plan = planner(newOrderRoute()).plan(order);

        assertThat(plan).extracting(PublishInstruction::topic)
                .containsExactly("sow/fix42/orders", "sow/fix42/orders_audit");
        assertThat(plan).allSatisfy(instruction -> {
            assertThat(instruction.mode()).isEqualTo(PublishMode.FULL);
            // FULL means the record is created complete; nothing is stripped.
            assertThat(instruction.payload().printable()).isEqualTo(order.printable());
        });
    }

    @Test
    @DisplayName("on the shipped topics a child slice goes where its parent does, tag 9000 and all")
    void childSliceSharesTheOrderTopics() {
        FixMessage childOrder = FixMessage.ofType("D")
                .set(FixTags.CL_ORD_ID, "CHILD-1")
                .set(FixTags.PARENT_ORDER_ID, "PARENT-1")
                .set(FixTags.SYMBOL, "TSLA")
                .build();

        List<PublishInstruction> plan = planner(newOrderRoute()).plan(childOrder);

        assertThat(plan).extracting(PublishInstruction::topic)
                .containsExactly("sow/fix42/orders", "sow/fix42/orders_audit");
        assertThat(plan).allSatisfy(instruction ->
                assertThat(instruction.payload().value(FixTags.PARENT_ORDER_ID))
                        .as("the parent link rides along for a filter to find")
                        .isEqualTo("PARENT-1"));
    }

    @Test
    @DisplayName("a {scope} topic pattern still resolves to parent or child from tag 9000")
    void scopePlaceholderSelectsTopicFamily() {
        PublishPlanner scoped = planner(SCOPED_TOPIC_KEYS,
                newOrderRoute("sow/{scope}/orders", "sow/{scope}/orders_audit"));
        FixMessage parentOrder = FixMessage.ofType("D")
                .set(FixTags.CL_ORD_ID, "PARENT-1")
                .set(FixTags.SYMBOL, "TSLA")
                .build();
        FixMessage childOrder = FixMessage.ofType("D")
                .set(FixTags.CL_ORD_ID, "CHILD-1")
                .set(FixTags.PARENT_ORDER_ID, "PARENT-1")
                .set(FixTags.SYMBOL, "TSLA")
                .build();

        assertThat(scoped.plan(parentOrder)).extracting(PublishInstruction::topic)
                .containsExactly("sow/parent/orders", "sow/parent/orders_audit");
        assertThat(scoped.plan(childOrder)).extracting(PublishInstruction::topic)
                .containsExactly("sow/child/orders", "sow/child/orders_audit");
    }

    @Test
    @DisplayName("35=G sends only the configured identity and changeable tags")
    void amendSendsOnlySelectedTags() {
        FixMessage amend = FixMessage.ofType("G")
                .set(FixTags.CL_ORD_ID, "C2")
                .set(FixTags.ORIG_CL_ORD_ID, "C1")
                .set(FixTags.ACCOUNT, "ACC-1")
                .set(FixTags.SYMBOL, "AAPL")
                .set(FixTags.SIDE, "1")
                .set(FixTags.ORDER_QTY, 1500)
                .setDecimal(FixTags.PRICE, 50.75)
                .set(FixTags.TIME_IN_FORCE, "0")
                .set(FixTags.TRANSACT_TIME, "20260821-13:31:00.000")
                .build();

        List<PublishInstruction> plan = planner(amendRoute()).plan(amend);

        assertThat(plan).hasSize(2);
        PublishInstruction first = plan.getFirst();
        assertThat(first.mode()).isEqualTo(PublishMode.DELTA);
        // Identity and timestamp, then the changeable business fields -- and
        // nothing else: symbol, side and account stay in the stored record
        // because the delta never mentions them.
        assertThat(first.payload().printable())
                .isEqualTo("35=G|11=C2|41=C1|60=20260821-13:31:00.000|38=1500|44=50.75|59=0|");
        assertThat(first.payload().has(FixTags.SYMBOL)).isFalse();
        assertThat(first.payload().has(FixTags.ACCOUNT)).isFalse();
    }

    @Test
    @DisplayName("execution reports split by ExecType into different field sets")
    void executionReportsRouteByExecType() {
        PublishPlanner planner = planner(
                execRoute("exec-new-ack", List.of("0"), List.of()),
                execRoute("exec-partial-fill", List.of("1"), List.of(31, 32, 14, 151, 6)));

        FixMessage ack = execution(FixTags.ExecType.NEW, FixTags.OrdStatus.NEW).build();
        FixMessage partial = execution(FixTags.ExecType.PARTIAL_FILL,
                FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.LAST_SHARES, 200)
                .setDecimal(FixTags.LAST_PX, 50.10)
                .set(FixTags.CUM_QTY, 200)
                .set(FixTags.LEAVES_QTY, 800)
                .setDecimal(FixTags.AVG_PX, 50.10)
                .build();

        assertThat(planner.plan(ack)).allSatisfy(instruction -> {
            assertThat(instruction.routeName()).isEqualTo("exec-new-ack");
            // An ack reports no economics, so it carries none.
            assertThat(instruction.payload().has(FixTags.LAST_SHARES)).isFalse();
            assertThat(instruction.payload().has(FixTags.CUM_QTY)).isFalse();
        });
        assertThat(planner.plan(partial)).allSatisfy(instruction -> {
            assertThat(instruction.routeName()).isEqualTo("exec-partial-fill");
            assertThat(instruction.payload().value(FixTags.LAST_SHARES)).isEqualTo("200");
            assertThat(instruction.payload().value(FixTags.CUM_QTY)).isEqualTo("200");
            assertThat(instruction.payload().value(FixTags.LEAVES_QTY)).isEqualTo("800");
        });
    }

    @Test
    @DisplayName("routes are matched in order, so a catch-all can sit last")
    void firstMatchingRouteWins() {
        PublishPlanner planner = planner(
                execRoute("exec-fill", List.of("2"), List.of(31, 32)),
                execRoute("exec-other", List.of(), List.of(14, 151)));

        FixMessage replaced = execution(FixTags.ExecType.REPLACED,
                FixTags.OrdStatus.PARTIALLY_FILLED).build();

        assertThat(planner.plan(replaced)).allSatisfy(instruction ->
                assertThat(instruction.routeName()).isEqualTo("exec-other"));
    }

    @Test
    @DisplayName("tag 20 routes a bust ahead of the fill rule its tag 150 would match")
    void bustRoutesByExecTransTypeBeforeFillRoutes() {
        // A 4.2 bust carries an ordinary 150=1/2 mirroring the restated
        // status; declared first and matched on tag 20, its rule wins, while
        // a genuine fill's 20=0 falls through to the fill rule.
        PublishPlanner planner = planner(
                bustRoute(),
                execRoute("exec-partial-fill", List.of("1"), List.of(31, 32, 14, 151, 6)));

        FixMessage bust = execution(FixTags.ExecType.PARTIAL_FILL,
                FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.EXEC_TRANS_TYPE, FixTags.ExecTransType.CANCEL)
                .set(FixTags.EXEC_REF_ID, "EXEC-0")
                .build();
        FixMessage fill = execution(FixTags.ExecType.PARTIAL_FILL,
                FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.EXEC_TRANS_TYPE, FixTags.ExecTransType.NEW)
                .build();

        assertThat(planner.plan(bust)).allSatisfy(instruction ->
                assertThat(instruction.routeName()).isEqualTo("exec-bust"));
        assertThat(planner.plan(fill)).allSatisfy(instruction ->
                assertThat(instruction.routeName()).isEqualTo("exec-partial-fill"));
    }

    @Test
    @DisplayName("a bust payload keeps tags 19 and 20, or no reader could tell what was undone")
    void bustPayloadKeepsReferenceTags() {
        FixMessage bust = execution(FixTags.ExecType.PARTIAL_FILL,
                FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.EXEC_TRANS_TYPE, FixTags.ExecTransType.CANCEL)
                .set(FixTags.EXEC_REF_ID, "EXEC-REF-7")
                .set(FixTags.CUM_QTY, 300)
                .set(FixTags.LEAVES_QTY, 700)
                .build();

        assertThat(planner(bustRoute()).plan(bust)).isNotEmpty().allSatisfy(instruction -> {
            assertThat(instruction.payload().value(FixTags.EXEC_REF_ID)).isEqualTo("EXEC-REF-7");
            assertThat(instruction.payload().value(FixTags.EXEC_TRANS_TYPE))
                    .isEqualTo(FixTags.ExecTransType.CANCEL);
        });
    }

    @Test
    @DisplayName("expired, rejected and restated reports route ahead of the catch-all")
    void expiredRejectedAndRestatedRouteAheadOfTheCatchAll() {
        // The gap this pins: with only exec-other declared, a 150=C/8/D reached
        // the exec topics and never the blotter, which kept the last working
        // LeavesQty -- and the exposure views summed it as live exposure.
        PublishPlanner planner = planner(expiredOrRejectedRoute(), restatedRoute(),
                execOtherRoute());

        FixMessage expired = execution(FixTags.ExecType.EXPIRED, FixTags.OrdStatus.EXPIRED)
                .set(FixTags.LEAVES_QTY, 0).build();
        FixMessage rejected = execution(FixTags.ExecType.REJECTED, FixTags.OrdStatus.REJECTED)
                .set(FixTags.LEAVES_QTY, 0).build();
        FixMessage restated = execution(FixTags.ExecType.RESTATED,
                FixTags.OrdStatus.PARTIALLY_FILLED).build();

        for (FixMessage terminal : List.of(expired, rejected)) {
            List<PublishInstruction> plan = planner.plan(terminal);
            assertThat(plan).isNotEmpty().allSatisfy(instruction ->
                    assertThat(instruction.routeName()).isEqualTo("exec-expired-or-rejected"));
            assertThat(plan).extracting(PublishInstruction::topic)
                    .as("150=%s reaches the blotter as well as the exec topics",
                            terminal.value(FixTags.EXEC_TYPE))
                    .containsExactly("sow/fix42/execs", "sow/fix42/execs_audit",
                            "sow/fix42/orders");
        }
        assertThat(planner.plan(restated)).isNotEmpty().allSatisfy(instruction ->
                assertThat(instruction.routeName()).isEqualTo("exec-restated"));

        // The pending acknowledgements still fall through to the catch-all,
        // and the catch-all still stays off the blotter.
        for (String pending : List.of(FixTags.ExecType.PENDING_NEW,
                FixTags.ExecType.PENDING_CANCEL, FixTags.ExecType.PENDING_REPLACE)) {
            List<PublishInstruction> plan = planner.plan(
                    execution(pending, pending).build());
            assertThat(plan).isNotEmpty().allSatisfy(instruction -> {
                assertThat(instruction.routeName()).isEqualTo("exec-other");
                assertThat(instruction.topic()).doesNotEndWith("/orders");
            });
        }
    }

    @Test
    @DisplayName("an expiry projects the terminal state onto the blotter and clears pending")
    void expiryProjectsTerminalStateOntoTheBlotter() {
        // An expired GTD order on an amended chain: 11/41 name the chain, the
        // venue reports what was done (14/6) and that nothing is working.
        FixMessage expired = execution(FixTags.ExecType.EXPIRED, FixTags.OrdStatus.EXPIRED)
                .set(FixTags.ORIG_CL_ORD_ID, "C0")
                .set(FixTags.ORDER_QTY, 2_000)
                .set(FixTags.CUM_QTY, 1_000)
                .set(FixTags.LEAVES_QTY, 0)
                .setDecimal(FixTags.AVG_PX, 1_199.50)
                .set(FixTags.TEXT, "GTD expired")
                .build();

        List<PublishInstruction> plan = planner(expiredOrRejectedRoute()).plan(expired);

        // The blotter gets identity, status, the cumulative totals, a forced
        // zero balance and a cleared pending family -- and nothing else: no
        // exec keys, no 38 (the acked quantity is not what expired), no text.
        assertThat(blotterPayload(plan).printable()).isEqualTo(
                "35=8|11=C1|41=C0|39=C|150=C|60=20260821-13:30:00.000|14=1000|151=0|6=1199.5|"
                        + "9013=NONE|9010=0|9011=0|9012=NONE|");

        // The exec topics get the report as selected, reason and text included.
        plan.stream()
                .filter(instruction -> !instruction.topic().endsWith("/orders"))
                .forEach(instruction -> {
                    FixMessage payload = instruction.payload();
                    assertThat(payload.value(FixTags.ORDER_ID)).isEqualTo("ORD-1");
                    assertThat(payload.value(FixTags.EXEC_ID)).isEqualTo("EXEC-1");
                    assertThat(payload.value(FixTags.ORDER_QTY)).isEqualTo("2000");
                    assertThat(payload.value(FixTags.TEXT)).isEqualTo("GTD expired");
                    assertThat(payload.has(FixTags.PENDING_ACTION)).isFalse();
                });
    }

    @Test
    @DisplayName("a reject carries its reason to the exec topics and 39=8 to the blotter")
    void rejectProjectsTerminalStatusAndKeepsReasonOnExecTopics() {
        // A 35=D the venue refused: the first and last report of the chain,
        // no 41, nothing filled. 103 is why, and belongs on the exec topics.
        FixMessage rejected = execution(FixTags.ExecType.REJECTED, FixTags.OrdStatus.REJECTED)
                .set(FixTags.ORDER_QTY, 500)
                .set(FixTags.CUM_QTY, 0)
                .set(FixTags.LEAVES_QTY, 0)
                .set(FixTags.ORD_REJ_REASON, "1")
                .set(FixTags.TEXT, "unknown symbol")
                .build();

        List<PublishInstruction> plan = planner(expiredOrRejectedRoute()).plan(rejected);

        FixMessage blotter = blotterPayload(plan);
        assertThat(blotter.value(FixTags.ORD_STATUS)).isEqualTo(FixTags.OrdStatus.REJECTED);
        assertThat(blotter.value(FixTags.EXEC_TYPE)).isEqualTo(FixTags.ExecType.REJECTED);
        assertThat(blotter.value(FixTags.LEAVES_QTY)).isEqualTo("0");
        assertThat(blotter.value(FixTags.PENDING_ACTION))
                .as("the 35=D's pending NEW is resolved, by refusal")
                .isEqualTo(FixTags.PendingAction.NONE);
        assertThat(blotter.has(FixTags.ORD_REJ_REASON)).isFalse();
        assertThat(blotter.has(FixTags.TEXT)).isFalse();
        assertThat(blotter.has(FixTags.WORKING_CL_ORD_ID))
                .as("a reject confirms no id")
                .isFalse();

        plan.stream()
                .filter(instruction -> !instruction.topic().endsWith("/orders"))
                .forEach(instruction -> assertThat(instruction.payload().value(FixTags.ORD_REJ_REASON))
                        .isEqualTo("1"));
    }

    @Test
    @DisplayName("a terminal projection forces 151 to zero whatever the venue sent, or omitted")
    void terminalProjectionForcesLeavesQtyToZero() {
        PublishPlanner planner = planner(cancelOrDoneRoute(), expiredOrRejectedRoute());

        // A venue that echoes the last working balance on the expiry. The exec
        // topics record what it said; the blotter is not allowed to believe it.
        FixMessage staleBalance = execution(FixTags.ExecType.EXPIRED, FixTags.OrdStatus.EXPIRED)
                .set(FixTags.CUM_QTY, 200)
                .set(FixTags.LEAVES_QTY, 800)
                .build();
        List<PublishInstruction> expired = planner.plan(staleBalance);
        assertThat(blotterPayload(expired).value(FixTags.LEAVES_QTY)).isEqualTo("0");
        assertThat(blotterPayload(expired).value(FixTags.CUM_QTY))
                .as("the stamp is on 151 alone; the fills' total is the venue's")
                .isEqualTo("200");
        expired.stream()
                .filter(instruction -> !instruction.topic().endsWith("/orders"))
                .forEach(instruction -> assertThat(instruction.payload().value(FixTags.LEAVES_QTY))
                        .as("the audit trail keeps the report as sent")
                        .isEqualTo("800"));

        // A venue that omits LeavesQty on a cancel altogether. Without the
        // stamp the delta would carry no 151 and the record would keep the
        // last fill's -- the exposure views would still count it as working.
        FixMessage noBalance = execution(FixTags.ExecType.CANCELED, FixTags.OrdStatus.CANCELED)
                .set(FixTags.CUM_QTY, 200)
                .build();
        assertThat(noBalance.has(FixTags.LEAVES_QTY)).isFalse();
        List<PublishInstruction> cancelled = planner.plan(noBalance);
        assertThat(cancelled).isNotEmpty().allSatisfy(instruction ->
                assertThat(instruction.routeName()).isEqualTo("exec-cancel-or-done"));
        assertThat(blotterPayload(cancelled).value(FixTags.LEAVES_QTY)).isEqualTo("0");
        cancelled.stream()
                .filter(instruction -> !instruction.topic().endsWith("/orders"))
                .forEach(instruction -> assertThat(instruction.payload().has(FixTags.LEAVES_QTY))
                        .as("nothing is invented for the exec topics")
                        .isFalse());
    }

    @Test
    @DisplayName("a restatement adopts the venue's new terms and leaves the pending family alone")
    void restatementProjectsRestatedTermsWithoutTouchingPending() {
        // An exchange-initiated partial decline (378=5): OrderQty 2500 -> 2000
        // with 1000 already done, so 1000 is still working.
        FixMessage restated = execution(FixTags.ExecType.RESTATED,
                FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.ORIG_CL_ORD_ID, "C0")
                .set(FixTags.ORDER_QTY, 2_000)
                .setDecimal(FixTags.PRICE, 1_200.00)
                .set(FixTags.CUM_QTY, 1_000)
                .set(FixTags.LEAVES_QTY, 1_000)
                .setDecimal(FixTags.AVG_PX, 1_199.50)
                .set(FixTags.EXEC_RESTATEMENT_REASON, "5")
                .set(FixTags.TEXT, "partial decline")
                .build();

        List<PublishInstruction> plan = planner(restatedRoute()).plan(restated);

        assertThat(plan).extracting(PublishInstruction::topic)
                .containsExactly("sow/fix42/execs", "sow/fix42/execs_audit", "sow/fix42/orders");
        FixMessage blotter = blotterPayload(plan);
        // The restated absolutes, as sent: this is not a terminal report, so
        // 151 is the venue's number, not a forced zero.
        assertThat(blotter.value(FixTags.ORDER_QTY)).isEqualTo("2000");
        assertThat(blotter.value(FixTags.PRICE)).isEqualTo("1200");
        assertThat(blotter.value(FixTags.CUM_QTY)).isEqualTo("1000");
        assertThat(blotter.value(FixTags.LEAVES_QTY)).isEqualTo("1000");
        assertThat(blotter.value(FixTags.AVG_PX)).isEqualTo("1199.5");
        assertThat(blotter.value(FixTags.ORD_STATUS))
                .isEqualTo(FixTags.OrdStatus.PARTIALLY_FILLED);
        // A restatement answers no request: an amend may be in flight at the
        // same time, and its proposal must survive this.
        assertThat(blotter.has(FixTags.PENDING_ACTION)).isFalse();
        assertThat(blotter.has(FixTags.PENDING_ORDER_QTY)).isFalse();
        assertThat(blotter.has(FixTags.PENDING_PRICE)).isFalse();
        assertThat(blotter.has(FixTags.PENDING_CL_ORD_ID)).isFalse();
        assertThat(blotter.has(FixTags.WORKING_CL_ORD_ID)).isFalse();
        // The reason describes THIS report, so it stays off the merged record.
        assertThat(blotter.has(FixTags.EXEC_RESTATEMENT_REASON)).isFalse();
        assertThat(blotter.has(FixTags.TEXT)).isFalse();

        plan.stream()
                .filter(instruction -> !instruction.topic().endsWith("/orders"))
                .forEach(instruction -> assertThat(instruction.payload()
                        .value(FixTags.EXEC_RESTATEMENT_REASON)).isEqualTo("5"));
    }

    @Test
    @DisplayName("the new routes pass tags 11 and 41 through untouched on every payload")
    void expiryRejectAndRestatementKeepChainingTagsIntact() {
        // 11/41 are the chaining key generator's inputs. A projection that
        // rewrote either would open a second chain, and the terminal report
        // would land on a record of its own instead of closing the order's.
        PublishPlanner planner = planner(expiredOrRejectedRoute(), restatedRoute());

        for (String execType : List.of(FixTags.ExecType.EXPIRED, FixTags.ExecType.REJECTED,
                FixTags.ExecType.RESTATED)) {
            FixMessage report = execution(execType, FixTags.OrdStatus.PARTIALLY_FILLED)
                    .set(FixTags.CL_ORD_ID, "C3")
                    .set(FixTags.ORIG_CL_ORD_ID, "C2")
                    .set(FixTags.LEAVES_QTY, 0)
                    .build();

            assertThat(planner.plan(report)).hasSize(3).allSatisfy(instruction -> {
                assertThat(instruction.payload().value(FixTags.CL_ORD_ID))
                        .as("150=%s -> %s", execType, instruction.topic()).isEqualTo("C3");
                assertThat(instruction.payload().value(FixTags.ORIG_CL_ORD_ID))
                        .as("150=%s -> %s", execType, instruction.topic()).isEqualTo("C2");
            });
        }
    }

    @Test
    @DisplayName("an execution report always keeps tags 37 and 17, its two topic keys")
    void executionKeepsBothTopicKeys() {
        FixMessage fill = execution(FixTags.ExecType.FILL, FixTags.OrdStatus.FILLED)
                .set(FixTags.CUM_QTY, 1000)
                .set(FixTags.LEAVES_QTY, 0)
                .build();

        List<PublishInstruction> plan =
                planner(execRoute("exec-fill", List.of("2"), List.of(14, 151))).plan(fill);

        assertThat(plan).extracting(PublishInstruction::topic)
                .containsExactly("sow/fix42/execs", "sow/fix42/execs_audit");
        assertThat(plan).allSatisfy(instruction -> {
            assertThat(instruction.payload().has(FixTags.ORDER_ID)).isTrue();
            assertThat(instruction.payload().has(FixTags.EXEC_ID)).isTrue();
        });
    }

    @Test
    @DisplayName("an unroutable message raises rather than disappearing")
    void unroutableMessageFails() {
        FixMessage dontKnow = FixMessage.ofType("Q").set(FixTags.EXEC_ID, "E1").build();

        assertThatThrownBy(() -> planner(newOrderRoute()).plan(dontKnow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no fix42 route matches 35=Q");
    }

    @Test
    @DisplayName("a message missing the topic's key is skipped, not sent to be rejected")
    void messageWithoutTopicKeyIsSkipped() {
        // A route may legitimately select tag 41, which the first message of a
        // chain does not carry -- but tag 11 keys the topic, and without it
        // AMPS would reject the publish. Dropping it here keeps the error on
        // this side of the wire, where the log explains it.
        FixMessage noClOrdId = FixMessage.ofType("G")
                .set(FixTags.ORIG_CL_ORD_ID, "C1")
                .set(FixTags.ORDER_QTY, 1500)
                .build();

        assertThat(planner(amendRoute()).plan(noClOrdId)).isEmpty();
    }

    @Test
    @DisplayName("scope comes from tag 9000 alone, with no chain memory")
    void scopeIsDerivedPerMessage() {
        PublishPlanner planner = planner(newOrderRoute());

        assertThat(planner.scopeOf(FixMessage.ofType("F").set(FixTags.CL_ORD_ID, "C2").build()))
                .isEqualTo(OrderScope.PARENT);
        assertThat(planner.scopeOf(FixMessage.ofType("F")
                .set(FixTags.CL_ORD_ID, "C2")
                .set(FixTags.PARENT_ORDER_ID, "P1")
                .build()))
                .isEqualTo(OrderScope.CHILD);
    }

    private static FixMessage.Builder execution(String execType, String ordStatus) {
        return FixMessage.ofType("8")
                .set(FixTags.ORDER_ID, "ORD-1")
                .set(FixTags.CL_ORD_ID, "C1")
                .set(FixTags.EXEC_ID, "EXEC-1")
                .set(FixTags.EXEC_TYPE, execType)
                .set(FixTags.ORD_STATUS, ordStatus)
                .set(FixTags.SYMBOL, "AAPL")
                .set(FixTags.TRANSACT_TIME, "20260821-13:30:00.000");
    }
}
