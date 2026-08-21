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
    @DisplayName("fills and pending acknowledgements never touch the blotter")
    void onlyResolvingReportsProjectOntoTheBlotter() {
        for (String execType : List.of(FixTags.ExecType.PARTIAL_FILL, FixTags.ExecType.FILL,
                FixTags.ExecType.PENDING_CANCEL, FixTags.ExecType.PENDING_REPLACE)) {
            FixMessage report = FixMessage.ofType("8")
                    .set(FixTags.ORDER_ID, "ORD-1")
                    .set(FixTags.CL_ORD_ID, "C1")
                    .set(FixTags.EXEC_ID, "E-" + execType)
                    .set(FixTags.EXEC_TYPE, execType)
                    .set(FixTags.ORD_STATUS, FixTags.OrdStatus.PARTIALLY_FILLED)
                    .set(FixTags.TRANSACT_TIME, "20260821-14:03:00.000")
                    .build();

            assertThat(planner.plan(report))
                    .as("150=%s acknowledges or fills; it must not clear a pending request",
                            execType)
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
