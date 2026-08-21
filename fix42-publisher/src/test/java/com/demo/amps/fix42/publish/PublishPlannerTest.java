package com.demo.amps.fix42.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.mock.OrderScope;
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
            "sow/parent/orders", List.of(11),
            "sow/parent/orders_audit", List.of(11),
            "sow/child/orders", List.of(11),
            "sow/child/orders_audit", List.of(11),
            "sow/parent/execs", List.of(37),
            "sow/parent/execs_audit", List.of(17),
            "sow/parent/rejects", List.of(11));

    private static PublishPlanner planner(Fix42Properties.Route... routes) {
        return new PublishPlanner(new Fix42Properties(
                new Fix42Properties.Amps("tcp://127.0.0.1:9007/amps/fix", "test", 10_000),
                TOPIC_KEYS, List.of(routes)));
    }

    private static Fix42Properties.Route newOrderRoute() {
        return new Fix42Properties.Route("new-order", List.of("D"), List.of(), PublishMode.FULL,
                List.of(), List.of(),
                List.of("sow/{scope}/orders", "sow/{scope}/orders_audit"), List.of(), null);
    }

    private static Fix42Properties.Route amendRoute() {
        return new Fix42Properties.Route("amend", List.of("G"), List.of(), PublishMode.DELTA,
                List.of(35, 11, 41, 60), List.of(38, 44, 59),
                List.of("sow/{scope}/orders", "sow/{scope}/orders_audit"), List.of(), null);
    }

    private static Fix42Properties.Route execRoute(String name, List<String> execTypes,
                                                   List<Integer> changeable) {
        return new Fix42Properties.Route(name, List.of("8"), execTypes, PublishMode.DELTA,
                List.of(35, 11, 41, 37, 17, 39, 150, 60), changeable,
                List.of("sow/parent/execs", "sow/parent/execs_audit"), List.of(), null);
    }

    @Test
    @DisplayName("35=D publishes the whole message to both parent order topics")
    void newOrderGoesOutWhole() {
        FixMessage order = FixMessage.ofType("D")
                .set(FixTags.CL_ORD_ID, "C1")
                .set(FixTags.SYMBOL, "AAPL")
                .set(FixTags.ORDER_QTY, 1000)
                .setDecimal(FixTags.PRICE, 50.25)
                .build();

        List<PublishInstruction> plan = planner(newOrderRoute()).plan(order);

        assertThat(plan).extracting(PublishInstruction::topic)
                .containsExactly("sow/parent/orders", "sow/parent/orders_audit");
        assertThat(plan).allSatisfy(instruction -> {
            assertThat(instruction.mode()).isEqualTo(PublishMode.FULL);
            // FULL means the record is created complete; nothing is stripped.
            assertThat(instruction.payload().printable()).isEqualTo(order.printable());
        });
    }

    @Test
    @DisplayName("tag 9000 routes a request to the child topics instead")
    void parentOrderIdSelectsChildTopics() {
        FixMessage childOrder = FixMessage.ofType("D")
                .set(FixTags.CL_ORD_ID, "CHILD-1")
                .set(FixTags.PARENT_ORDER_ID, "PARENT-1")
                .set(FixTags.SYMBOL, "TSLA")
                .build();

        List<PublishInstruction> plan = planner(newOrderRoute()).plan(childOrder);

        assertThat(plan).extracting(PublishInstruction::topic)
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
    @DisplayName("an execution report always keeps tags 37 and 17, its two topic keys")
    void executionKeepsBothTopicKeys() {
        FixMessage fill = execution(FixTags.ExecType.FILL, FixTags.OrdStatus.FILLED)
                .set(FixTags.CUM_QTY, 1000)
                .set(FixTags.LEAVES_QTY, 0)
                .build();

        List<PublishInstruction> plan =
                planner(execRoute("exec-fill", List.of("2"), List.of(14, 151))).plan(fill);

        assertThat(plan).extracting(PublishInstruction::topic)
                .containsExactly("sow/parent/execs", "sow/parent/execs_audit");
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
