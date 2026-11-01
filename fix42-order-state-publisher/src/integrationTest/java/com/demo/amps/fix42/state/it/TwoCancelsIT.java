package com.demo.amps.fix42.state.it;

import static com.demo.amps.fix42.state.it.OrderStateFixture.BLOTTER;
import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
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
 * The headline (docs/05 phase 3): two cancels for one order, the venue
 * confirms one and rejects the other. The stateless publisher's
 * last-writer-wins merge leaves that order reading cancel-rejected; this one
 * leaves it canceled, with nothing pending, <b>whichever answer arrives
 * first</b> -- read back from a real AMPS {@code sow/fix42/order_state}.
 *
 * <p>Both arrival orders run twice: once as docs/03 §7.1-7.2 state the
 * messages, once as the mock venue scripts them (06 E1, E2), and the E1/E2
 * chains also under the dialect whose rejects carry the request-time status
 * {@code 39=6} -- the shape of the 9 in the walkthrough.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoCancelsIT {

    private OrderStateFixture fixture;

    @BeforeAll
    void publish() throws Exception {
        fixture = OrderStateFixture.start("fix42os-two-cancels", "demo");
        for (String message : walkthroughMessages("X", true)) {
            fixture.publisher.send(FixMessage.parse(message.replace('|', '\u0001')));
        }
        for (String message : walkthroughMessages("Y", false)) {
            fixture.publisher.send(FixMessage.parse(message.replace('|', '\u0001')));
        }
        List<FixEvent> events = new ArrayList<>();
        for (String row : List.of("E1", "E2")) {
            events.addAll(edgeCase(row, MockDialect.DEFAULT).events());
        }
        fixture.publish(events);
        fixture.flush();
    }

    @AfterAll
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @DisplayName("7.1 confirm then reject, 7.2 reject then confirm: both canceled, nothing pending")
    void bothArrivalOrdersEndCanceled() throws Exception {
        for (String key : List.of("X-1", "Y-1", "EC-E1-1", "EC-E2-1")) {
            FixMessage record = fixture.sow.record(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID, key);
            assertThat(record).as("a record for %s", key).isNotNull();
            assertThat(record.value(FixTags.ORD_STATUS)).as("%s 39", key).isEqualTo("4");
            assertThat(record.value(FixTags.LEAVES_QTY)).as("%s 151", key).isEqualTo("0");
            assertThat(record.value(FixTags.Record.PENDING_ACTION)).as("%s 15035", key).isEqualTo("NONE");
            assertThat(record.value(FixTags.Record.PENDING_COUNT)).as("%s 9016", key).isEqualTo("0");
            assertThat(record.value(FixTags.Record.TERMINAL)).as("%s 9017", key).isEqualTo("Y");
            assertThat(record.has(FixTags.MSG_TYPE)).as("%s carries no 35", key).isFalse();
        }
    }

    @Test
    @DisplayName("the record says the last thing that happened (9022), and keeps the D's terms")
    void recordIsHonestAboutWhatHappened() throws Exception {
        FixMessage x = fixture.sow.record(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID, "X-1");
        assertThat(x.value(FixTags.Record.LAST_MSG_TYPE)).isEqualTo("9");
        assertThat(x.value(FixTags.EXEC_TYPE)).isEqualTo("4");
        assertThat(x.value(FixTags.SYMBOL)).isEqualTo("SYM");
        assertThat(x.value(FixTags.ACCOUNT)).isEqualTo("ACC");
        assertThat(x.value(FixTags.CUM_QTY)).isEqualTo("300");
        FixMessage y = fixture.sow.record(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID, "Y-1");
        assertThat(y.value(FixTags.Record.LAST_MSG_TYPE)).isEqualTo("8");
    }

    @Test
    @DisplayName("the stateless publisher's failure, for contrast: the reject is on the rejects topic, not the blotter's 39")
    void rejectIsOnTheRejectsTopic() throws Exception {
        FixMessage reject = fixture.sow.record(OrderStateFixture.REJECTS, FixTags.CL_ORD_ID, "X-3");
        assertThat(reject.value(FixTags.ORD_STATUS)).isEqualTo("6");
        assertThat(reject.value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo("X-1");
    }

    @Test
    @DisplayName("a second dialect's E1/E2 on top converge on the same canceled records")
    void secondDialectConverges() throws Exception {
        List<FixEvent> events = new ArrayList<>();
        for (String row : List.of("E1", "E2")) {
            OrderChain chain = edgeCase(row, MockDialect.DEFAULT.rejectStatusAtRequestTime("6"));
            for (FixEvent event : chain.events()) {
                // Under its own ids, so the run is a second pair of orders.
                events.add(new FixEvent(event.chainId(), event.scope(), event.description(),
                        rename(event.message(), "EC-" + row, "EC6-" + row)));
            }
        }
        fixture.publish(events);
        fixture.flush();
        for (String key : List.of("EC6-E1-1", "EC6-E2-1")) {
            FixMessage record = fixture.sow.record(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID, key);
            assertThat(record).as(key).isNotNull();
            assertThat(record.value(FixTags.ORD_STATUS)).as("%s 39", key).isEqualTo("4");
            assertThat(record.value(FixTags.Record.PENDING_COUNT)).as("%s 9016", key).isEqualTo("0");
        }
        assertThat(fixture.publisher.publishStoreDepth()).isZero();
    }

    // ---- the messages ---------------------------------------------------------------

    /**
     * docs/03 §7.1 ({@code confirmFirst}) or §7.2 for order {@code id}: a D for
     * 1000 @ 50, the ack, a fill of 300, two cancels both naming the original
     * id, then the venue's two answers, stamped in the order they arrive --
     * one session, one clock (docs/11 F4 is what happens otherwise).
     */
    private static List<String> walkthroughMessages(String id, boolean confirmFirst) {
        String o = "ORD-" + id;
        List<String> messages = new ArrayList<>();
        messages.add("35=D|11=" + id + "-1|38=1000|44=50|1=ACC|55=SYM|54=1|40=2|59=0|21=1"
                + "|60=20261031-10:00:00.000|");
        messages.add("35=8|150=0|39=0|11=" + id + "-1|37=" + o + "|17=" + id + "-E1|38=1000|44=50|14=0|151=1000"
                + "|6=0|60=20261031-10:00:01.000|");
        messages.add("35=8|150=1|39=1|11=" + id + "-1|37=" + o + "|17=" + id + "-E2|38=1000|44=50|14=300|151=700"
                + "|6=50|32=300|31=50|30=XNAS|60=20261031-10:00:02.000|");
        messages.add("35=F|11=" + id + "-2|41=" + id + "-1|60=20261031-10:00:03.000|");
        messages.add("35=F|11=" + id + "-3|41=" + id + "-1|60=20261031-10:00:04.000|");
        String confirm = "35=8|150=4|39=4|11=" + id + "-2|41=" + id + "-1|37=" + o + "|17=" + id + "-E3|38=1000"
                + "|44=50|14=300|151=0|6=50|60=" + (confirmFirst ? "20261031-10:00:05.000|" : "20261031-10:00:06.000|");
        String reject = "35=9|11=" + id + "-3|41=" + id + "-1|37=" + o + "|434=1|39=6|102=0"
                + "|60=" + (confirmFirst ? "20261031-10:00:06.000|" : "20261031-10:00:05.000|");
        messages.add(confirmFirst ? confirm : reject);
        messages.add(confirmFirst ? reject : confirm);
        return messages;
    }

    private static OrderChain edgeCase(String row, MockDialect dialect) {
        return EdgeCaseFlow.cases().stream().filter(c -> c.id().equals(row)).findFirst().orElseThrow()
                .build(dialect).getFirst();
    }

    private static FixMessage rename(FixMessage message, String from, String to) {
        FixMessage.Builder builder = FixMessage.ofType(message.msgType());
        message.asMap().forEach((tag, value) -> builder.set(tag, value.replace(from, to)));
        return builder.build();
    }
}
