package com.demo.amps.fix42;

import static org.assertj.core.api.Assertions.assertThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.publish.PublishInstruction;
import com.demo.amps.fix42.publish.PublishPlanner;
import com.demo.amps.fix42.mock.FixEvent;
import com.demo.amps.fix42.mock.MockFixFlow;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The shipped {@code application.yml}, bound and validated for real.
 *
 * <p>This is the test that catches a typo in the rulebook. The unit tests
 * around {@link Fix42Properties} prove the validation logic works on
 * hand-built inputs; this proves the configuration this module actually ships
 * passes it, and that every message the mock flow generates has somewhere to go.
 *
 * <p>The AMPS connection is mocked out: nothing here needs a server, and a
 * config test that silently required one would be skipped exactly when it is
 * most useful.
 */
@SpringBootTest
@ActiveProfiles("test")
class Fix42PublisherContextTest {

    @MockitoBean
    private Client ampsClient;

    @Autowired
    private Fix42Properties properties;

    @Autowired
    private PublishPlanner planner;

    @Test
    @DisplayName("the shipped application.yml binds and passes startup validation")
    void shippedConfigurationIsValid() {
        // The context starting at all means validateFix42Properties ran; this
        // pins the shape so an accidental deletion is visible.
        assertThat(properties.routes()).isNotEmpty();
        assertThat(properties.topicKeys())
                .containsKeys("sow/parent/orders", "sow/parent/orders_audit",
                        "sow/child/orders", "sow/child/orders_audit",
                        "sow/parent/execs", "sow/parent/execs_audit", "sow/parent/rejects");
        assertThat(properties.amps().uri()).endsWith("/amps/fix");
    }

    @Test
    @DisplayName("every message the mock flow generates has a route")
    void everyMockMessageIsRoutable() {
        for (FixEvent event : MockFixFlow.events()) {
            List<PublishInstruction> plan = planner.plan(event.message());
            assertThat(plan)
                    .as("%s %s (35=%s) must publish somewhere",
                            event.chainId(), event.description(), event.msgType())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("35=D is the only message type published whole")
    void onlyNewOrdersArePublishedInFull() {
        for (FixEvent event : MockFixFlow.events()) {
            PublishMode expected = FixTags.MsgType.NEW_ORDER_SINGLE.equals(event.msgType())
                    ? PublishMode.FULL
                    : PublishMode.DELTA;
            assertThat(planner.plan(event.message()))
                    .as("%s %s", event.chainId(), event.description())
                    .allSatisfy(instruction -> assertThat(instruction.mode()).isEqualTo(expected));
        }
    }

    @Test
    @DisplayName("every planned publish carries its destination topic's key field")
    void everyPlannedPublishCarriesItsKey() {
        for (FixEvent event : MockFixFlow.events()) {
            for (PublishInstruction instruction : planner.plan(event.message())) {
                List<Integer> keys = properties.topicKeys().get(instruction.topic());
                assertThat(keys)
                        .as("topic %s should be declared in topic-keys", instruction.topic())
                        .isNotNull();
                for (Integer key : keys) {
                    assertThat(instruction.payload().has(key))
                            .as("%s -> %s must carry tag %d: %s", event.description(),
                                    instruction.topic(), key, instruction.payload().printable())
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("an amend never publishes tag 38 or 44 to the blotter")
    void amendKeepsProposedTermsOutOfTheAckedFields() {
        FixMessage amend = FixMessage.ofType("G")
                .set(FixTags.CL_ORD_ID, "C2")
                .set(FixTags.ORIG_CL_ORD_ID, "C1")
                .set(FixTags.ORDER_QTY, 6000)
                .setDecimal(FixTags.PRICE, 122.10)
                .set(FixTags.TRANSACT_TIME, "20260821-14:01:00.000")
                .build();

        for (PublishInstruction instruction : planner.plan(amend)) {
            if (instruction.topic().endsWith("_audit")) {
                // The audit trail records the message as sent.
                assertThat(instruction.payload().value(FixTags.ORDER_QTY)).isEqualTo("6000");
                continue;
            }
            // The blotter must not see them: writing tag 38 here would destroy
            // the acked quantity, and a merge cannot put it back.
            assertThat(instruction.payload().has(FixTags.ORDER_QTY)).isFalse();
            assertThat(instruction.payload().has(FixTags.PRICE)).isFalse();
            assertThat(instruction.payload().value(FixTags.PENDING_ORDER_QTY)).isEqualTo("6000");
            assertThat(instruction.payload().value(FixTags.PENDING_PRICE)).isEqualTo("122.1");
            assertThat(instruction.payload().value(FixTags.PENDING_ACTION))
                    .isEqualTo(FixTags.PendingAction.REPLACE);
            assertThat(instruction.payload().value(FixTags.PENDING_CL_ORD_ID)).isEqualTo("C2");
            // 11 and 41 pass through untouched -- they are the chaining
            // module's inputs, and rewriting either splits the order in two.
            assertThat(instruction.payload().value(FixTags.CL_ORD_ID)).isEqualTo("C2");
            assertThat(instruction.payload().value(FixTags.ORIG_CL_ORD_ID)).isEqualTo("C1");
            // The working id is NOT published, so the merge leaves it alone.
            assertThat(instruction.payload().has(FixTags.WORKING_CL_ORD_ID)).isFalse();
        }
    }

    @Test
    @DisplayName("a cancel reject clears the pending family and restores the working ClOrdID")
    void cancelRejectClearsPendingWithoutReverting() {
        FixMessage reject = FixMessage.ofType("9")
                .set(FixTags.CL_ORD_ID, "C2")
                .set(FixTags.ORIG_CL_ORD_ID, "C1")
                .set(FixTags.ORDER_ID, "ORD-1")
                .set(FixTags.ORD_STATUS, FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.CXL_REJ_RESPONSE_TO, "2")
                .set(FixTags.TRANSACT_TIME, "20260821-14:01:30.000")
                .build();

        List<PublishInstruction> blotter = planner.plan(reject).stream()
                .filter(instruction -> instruction.topic().endsWith("/orders"))
                .toList();

        assertThat(blotter).hasSize(1);
        FixMessage payload = blotter.getFirst().payload();
        assertThat(payload.value(FixTags.PENDING_ACTION)).isEqualTo(FixTags.PendingAction.NONE);
        assertThat(payload.value(FixTags.PENDING_ORDER_QTY)).isEqualTo("0");
        assertThat(payload.has(FixTags.WORKING_CL_ORD_ID))
                .as("a reject does not move the working id, so it does not publish one")
                .isFalse();
        // Nothing to revert: the acked terms were never written, so the clear
        // carries no 38/44 at all.
        assertThat(payload.has(FixTags.ORDER_QTY)).isFalse();
        assertThat(payload.has(FixTags.PRICE)).isFalse();
    }

    @Test
    @DisplayName("a replace confirm adopts the venue's own terms and clears pending")
    void replaceConfirmAdoptsVenueTerms() {
        FixMessage confirm = FixMessage.ofType("8")
                .set(FixTags.ORDER_ID, "ORD-1")
                .set(FixTags.CL_ORD_ID, "C2")
                .set(FixTags.ORIG_CL_ORD_ID, "C1")
                .set(FixTags.EXEC_ID, "E9")
                .set(FixTags.EXEC_TYPE, FixTags.ExecType.REPLACED)
                .set(FixTags.ORD_STATUS, FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.ORDER_QTY, 5000)
                .setDecimal(FixTags.PRICE, 121.95)
                .set(FixTags.TRANSACT_TIME, "20260821-14:02:30.000")
                .build();

        FixMessage payload = planner.plan(confirm).stream()
                .filter(instruction -> instruction.topic().endsWith("/orders"))
                .findFirst().orElseThrow().payload();

        // Acked terms come from the venue's report -- we never have to remember
        // what we asked for.
        assertThat(payload.value(FixTags.ORDER_QTY)).isEqualTo("5000");
        assertThat(payload.value(FixTags.PRICE)).isEqualTo("121.95");
        assertThat(payload.value(FixTags.PENDING_ACTION)).isEqualTo(FixTags.PendingAction.NONE);
        assertThat(payload.value(FixTags.PENDING_ORDER_QTY)).isEqualTo("0");
        // And the confirmed request's ClOrdID becomes the working one.
        assertThat(payload.value(FixTags.WORKING_CL_ORD_ID)).isEqualTo("C2");
    }

    @Test
    @DisplayName("a fill reaches the blotter with quantities but does not clear pending")
    void fillsCarryQuantitiesWithoutResolvingPending() {
        FixMessage fill = FixMessage.ofType("8")
                .set(FixTags.ORDER_ID, "ORD-1")
                .set(FixTags.CL_ORD_ID, "C1")
                .set(FixTags.EXEC_ID, "E1")
                .set(FixTags.EXEC_TYPE, FixTags.ExecType.PARTIAL_FILL)
                .set(FixTags.ORD_STATUS, FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.ORDER_QTY, 10_000)
                .set(FixTags.LAST_SHARES, 3_000)
                .setDecimal(FixTags.LAST_PX, 150.00)
                .set(FixTags.CUM_QTY, 3_000)
                .set(FixTags.LEAVES_QTY, 7_000)
                .setDecimal(FixTags.AVG_PX, 150.00)
                .set(FixTags.TRANSACT_TIME, "20260821-14:03:00.000")
                .build();

        FixMessage blotter = planner.plan(fill).stream()
                .filter(instruction -> instruction.topic().endsWith("/orders"))
                .findFirst().orElseThrow().payload();

        // The economics reach the blotter...
        assertThat(blotter.value(FixTags.CUM_QTY)).isEqualTo("3000");
        assertThat(blotter.value(FixTags.LEAVES_QTY)).isEqualTo("7000");
        assertThat(blotter.value(FixTags.ORDER_QTY)).isEqualTo("10000");
        assertThat(blotter.value(FixTags.LAST_SHARES)).isEqualTo("3000");
        assertThat(blotter.value(FixTags.LAST_PX)).isEqualTo("150");
        assertThat(blotter.value(FixTags.AVG_PX)).isEqualTo("150");
        assertThat(blotter.value(FixTags.ORD_STATUS))
                .isEqualTo(FixTags.OrdStatus.PARTIALLY_FILLED);

        // ...but a fill answers no outstanding request, so it must not touch
        // the pending family. An order can fill while a cancel is in flight.
        assertThat(blotter.has(FixTags.PENDING_ACTION)).isFalse();
        assertThat(blotter.has(FixTags.PENDING_ORDER_QTY)).isFalse();
        assertThat(blotter.has(FixTags.PENDING_CL_ORD_ID)).isFalse();
        // Nor the working id: only a confirming report moves that.
        assertThat(blotter.has(FixTags.WORKING_CL_ORD_ID)).isFalse();
    }

    @Test
    @DisplayName("a bust projects the restated totals to the blotter without trade fields")
    void bustProjectsRestatedTotalsWithoutTradeFields() {
        FixMessage bust = FixMessage.ofType("8")
                .set(FixTags.ORDER_ID, "ORD-1")
                .set(FixTags.CL_ORD_ID, "C1")
                .set(FixTags.EXEC_ID, "E5")
                .set(FixTags.EXEC_REF_ID, "E2")
                .set(FixTags.EXEC_TRANS_TYPE, FixTags.ExecTransType.CANCEL)
                .set(FixTags.EXEC_TYPE, FixTags.ExecType.PARTIAL_FILL)
                .set(FixTags.ORD_STATUS, FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.ORDER_QTY, 6_000)
                .set(FixTags.CUM_QTY, 1_000)
                .set(FixTags.LEAVES_QTY, 5_000)
                .setDecimal(FixTags.AVG_PX, 210.05)
                .set(FixTags.TRANSACT_TIME, "20260821-14:04:00.000")
                .build();

        List<PublishInstruction> plan = planner.plan(bust);
        assertThat(plan).isNotEmpty().allSatisfy(instruction ->
                assertThat(instruction.routeName()).isEqualTo("exec-bust"));

        FixMessage blotter = plan.stream()
                .filter(instruction -> instruction.topic().endsWith("/orders"))
                .findFirst().orElseThrow().payload();
        // The restated absolutes are adopted wholesale...
        assertThat(blotter.value(FixTags.CUM_QTY)).isEqualTo("1000");
        assertThat(blotter.value(FixTags.LEAVES_QTY)).isEqualTo("5000");
        assertThat(blotter.value(FixTags.AVG_PX)).isEqualTo("210.05");
        assertThat(blotter.value(FixTags.ORDER_QTY)).isEqualTo("6000");
        // ...but not the reference pair: 19/20 describe a PRIOR execution and
        // would go stale on the merged record. No trade fields either, so the
        // blotter keeps the last real fill's 32/31.
        assertThat(blotter.has(FixTags.EXEC_REF_ID)).isFalse();
        assertThat(blotter.has(FixTags.EXEC_TRANS_TYPE)).isFalse();
        assertThat(blotter.has(FixTags.LAST_SHARES)).isFalse();
        // A bust answers no request: the pending family is untouched.
        assertThat(blotter.has(FixTags.PENDING_ACTION)).isFalse();

        // The exec topics DO keep 19/20 -- there the reference is the point.
        plan.stream()
                .filter(instruction -> !instruction.topic().endsWith("/orders"))
                .forEach(instruction -> {
                    assertThat(instruction.payload().value(FixTags.EXEC_REF_ID)).isEqualTo("E2");
                    assertThat(instruction.payload().value(FixTags.EXEC_TRANS_TYPE))
                            .isEqualTo(FixTags.ExecTransType.CANCEL);
                });
    }

    @Test
    @DisplayName("a correct projects the replacement 32/31 alongside the restated snapshot")
    void correctProjectsReplacementFillValues() {
        FixMessage correct = FixMessage.ofType("8")
                .set(FixTags.ORDER_ID, "ORD-1")
                .set(FixTags.CL_ORD_ID, "C1")
                .set(FixTags.EXEC_ID, "E6")
                .set(FixTags.EXEC_REF_ID, "E2")
                .set(FixTags.EXEC_TRANS_TYPE, FixTags.ExecTransType.CORRECT)
                .set(FixTags.EXEC_TYPE, FixTags.ExecType.PARTIAL_FILL)
                .set(FixTags.ORD_STATUS, FixTags.OrdStatus.PARTIALLY_FILLED)
                .set(FixTags.ORDER_QTY, 3_000)
                .set(FixTags.LAST_SHARES, 1_200)
                .setDecimal(FixTags.LAST_PX, 511.95)
                .set(FixTags.LAST_MKT, "XNAS")
                .set(FixTags.CUM_QTY, 1_200)
                .set(FixTags.LEAVES_QTY, 1_800)
                .setDecimal(FixTags.AVG_PX, 511.95)
                .set(FixTags.TRANSACT_TIME, "20260821-14:05:00.000")
                .build();

        List<PublishInstruction> plan = planner.plan(correct);
        assertThat(plan).isNotEmpty().allSatisfy(instruction ->
                assertThat(instruction.routeName()).isEqualTo("exec-correct"));

        FixMessage blotter = plan.stream()
                .filter(instruction -> instruction.topic().endsWith("/orders"))
                .findFirst().orElseThrow().payload();
        // The corrected execution's replacement values, not the originals.
        assertThat(blotter.value(FixTags.LAST_SHARES)).isEqualTo("1200");
        assertThat(blotter.value(FixTags.LAST_PX)).isEqualTo("511.95");
        assertThat(blotter.value(FixTags.CUM_QTY)).isEqualTo("1200");
        assertThat(blotter.value(FixTags.LEAVES_QTY)).isEqualTo("1800");
        assertThat(blotter.value(FixTags.AVG_PX)).isEqualTo("511.95");
        assertThat(blotter.has(FixTags.EXEC_REF_ID)).isFalse();
        assertThat(blotter.has(FixTags.EXEC_TRANS_TYPE)).isFalse();
    }

    @Test
    @DisplayName("a pending acknowledgement (150=6 / 150=E) never touches the blotter")
    void pendingAcknowledgementsDoNotReachTheBlotter() {
        for (String execType : List.of(FixTags.ExecType.PENDING_CANCEL,
                FixTags.ExecType.PENDING_REPLACE)) {
            FixMessage report = FixMessage.ofType("8")
                    .set(FixTags.ORDER_ID, "ORD-1")
                    .set(FixTags.CL_ORD_ID, "C1")
                    .set(FixTags.EXEC_ID, "E-" + execType)
                    .set(FixTags.EXEC_TYPE, execType)
                    .set(FixTags.ORD_STATUS, FixTags.OrdStatus.PARTIALLY_FILLED)
                    .set(FixTags.TRANSACT_TIME, "20260821-14:03:00.000")
                    .build();

            // These say "I have your request", not "I have applied it".
            // Projecting one would clear a proposal the venue is still deciding.
            assertThat(planner.plan(report))
                    .as("150=%s acknowledges a request; it resolves nothing", execType)
                    .noneSatisfy(instruction ->
                            assertThat(instruction.topic()).endsWith("/orders"));
        }
    }

    @Test
    @DisplayName("child orders reach the child topics and parents the parent ones")
    void mockFlowReachesBothTopicFamilies() {
        List<String> topics = MockFixFlow.events().stream()
                .flatMap(event -> planner.plan(event.message()).stream())
                .map(PublishInstruction::topic)
                .distinct()
                .toList();

        assertThat(topics).contains(
                "sow/parent/orders", "sow/parent/orders_audit",
                "sow/child/orders", "sow/child/orders_audit",
                "sow/parent/execs", "sow/parent/execs_audit",
                "sow/parent/rejects");
    }
}
