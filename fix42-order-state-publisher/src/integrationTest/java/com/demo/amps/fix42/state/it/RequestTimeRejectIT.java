package com.demo.amps.fix42.state.it;

import static com.demo.amps.fix42.state.it.OrderStateFixture.BLOTTER;
import static com.demo.amps.fix42.state.it.OrderStateFixture.REJECTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.hooks.RejectStatusAtRequestTimeHook;
import com.demo.amps.fix42.state.mock.EdgeCaseFlow;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.mock.MockDialect;
import com.demo.amps.fix42.state.mock.OrderChain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * docs/11 F2 against a real AMPS: 06 E15 and E31 under the venue dialect
 * whose rejects carry the request-time {@code 39=6}. First through a
 * publisher without the hook, which leaves the record reading pending cancel
 * with nothing pending; then, under their own ids, through the publisher
 * restarted with {@link RejectStatusAtRequestTimeHook}, which leaves it where
 * the venue has it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestTimeRejectIT {

    private static final MockDialect DIALECT = MockDialect.DEFAULT.rejectStatusAtRequestTime("6");
    private static final List<String> ROWS = List.of("E15", "E31");

    private OrderStateFixture fixture;

    @BeforeAll
    void publish() throws Exception {
        fixture = OrderStateFixture.start("fix42os-request-time-reject", "demo");
        fixture.publish(events("EC6-"));
        fixture.flush();
        fixture.disconnectPublisher();
        fixture.connect(List.of(new RejectStatusAtRequestTimeHook()));
        fixture.publish(events("ECH-"));
        fixture.flush();
    }

    @AfterAll
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @DisplayName("without the hook (F2): 39=6 with nothing pending")
    void withoutTheHookTheRecordReadsPendingCancel() throws Exception {
        for (String row : ROWS) {
            FixMessage record = record("EC6-" + row + "-1");
            assertThat(record.value(FixTags.ORD_STATUS)).as("%s 39", row).isEqualTo("6");
            assertThat(record.value(FixTags.Record.PENDING_COUNT)).as("%s 12016", row).isEqualTo("0");
            assertThat(record.value(FixTags.Record.PENDING_ACTION)).as("%s 15035", row).isEqualTo("NONE");
        }
    }

    @Test
    @DisplayName("with the hook: 39, 14 and 151 are the venue's, nothing pending, still live")
    void withTheHookTheRecordIsTheVenues() throws Exception {
        for (String row : ROWS) {
            OrderChain chain = chain(row);
            FixMessage record = record("ECH-" + row + "-1");
            assertThat(record.value(FixTags.ORD_STATUS)).as("%s 39", row).isEqualTo(chain.ordStatus()).isEqualTo("1");
            assertThat(record.value(FixTags.CUM_QTY)).as("%s 14", row).isEqualTo(Long.toString(chain.cumQty()));
            assertThat(record.value(FixTags.LEAVES_QTY)).as("%s 151", row).isEqualTo(Long.toString(chain.leavesQty()));
            assertThat(record.value(FixTags.Record.PENDING_COUNT)).as("%s 12016", row).isEqualTo("0");
            assertThat(record.value(FixTags.Record.TERMINAL)).as("%s 12017", row).isEqualTo("N");
        }
        assertThat(fixture.publisher.publishStoreDepth()).isZero();
    }

    @Test
    @DisplayName("the rejects topic keeps the venue's own 39=6, with the hook as without")
    void theAuditKeepsTheOriginal() throws Exception {
        for (String prefix : List.of("EC6-", "ECH-")) {
            for (String row : ROWS) {
                String rejected = rename(rejectOf(chain(row)), "EC-" + row, prefix + row).value(FixTags.CL_ORD_ID);
                FixMessage reject = fixture.sow.record(REJECTS, FixTags.CL_ORD_ID, rejected);
                assertThat(reject).as("a reject for %s", rejected).isNotNull();
                assertThat(reject.value(FixTags.ORD_STATUS)).as("%s 39", rejected).isEqualTo("6");
                assertThat(reject.value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo(prefix + row + "-1");
            }
        }
    }

    private FixMessage record(String key) throws Exception {
        FixMessage record = fixture.sow.record(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID, key);
        assertThat(record).as("a record for %s", key).isNotNull();
        return record;
    }

    /** E15 and E31 under the dialect, renamed from {@code EC-} to {@code prefix}: a second set of orders. */
    private static List<FixEvent> events(String prefix) {
        List<FixEvent> events = new ArrayList<>();
        for (String row : ROWS) {
            for (FixEvent event : chain(row).events()) {
                events.add(new FixEvent(event.chainId(), event.scope(), event.description(),
                        rename(event.message(), "EC-" + row, prefix + row)));
            }
        }
        return events;
    }

    private static OrderChain chain(String row) {
        return EdgeCaseFlow.cases().stream().filter(c -> c.id().equals(row)).findFirst().orElseThrow()
                .build(DIALECT).getFirst();
    }

    private static FixMessage rejectOf(OrderChain chain) {
        return chain.events().stream().map(FixEvent::message).filter(m -> "9".equals(m.msgType()))
                .reduce((first, second) -> second).orElseThrow();
    }

    private static FixMessage rename(FixMessage message, String from, String to) {
        FixMessage.Builder builder = FixMessage.ofType(message.msgType());
        message.asMap().forEach((tag, value) -> builder.set(tag, value.replace(from, to)));
        return builder.build();
    }
}
