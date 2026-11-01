package com.demo.amps.fix42.state.it;

import static com.demo.amps.fix42.state.it.OrderStateFixture.BLOTTER;
import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.mock.EdgeCaseFlow;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.mock.MockFixFlow;
import com.demo.amps.fix42.state.mock.OrderChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * AMPS goes away mid-flow and comes back (06 E71); then the daily reset's
 * {@code sow_delete}, across another restart (docs/04 §9, S13).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestartIT {

    private OrderStateFixture fixture;
    private List<OrderChain> chains;

    @BeforeAll
    void start() throws Exception {
        fixture = OrderStateFixture.start("fix42os-restart", "demo");
        chains = new ArrayList<>(MockFixFlow.chains());
        chains.addAll(EdgeCaseFlow.chains());
    }

    @AfterAll
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("E71: AMPS restarted mid-flow; the HAClient reconnects, the store replays, the end state is unchanged")
    void restartMidFlow() throws Exception {
        List<FixEvent> events = new ArrayList<>();
        chains.forEach(chain -> events.addAll(chain.events()));
        int half = events.size() / 2;
        fixture.publish(events.subList(0, half));
        // No flush: whatever the store holds unacknowledged is the client's to replay.
        fixture.server.restart();
        fixture.publish(events.subList(half, events.size()));
        fixture.flush();
        fixture.reconnectReaders();

        Map<String, FixMessage> blotter = fixture.sow.recordsBy(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID);
        assertThat(blotter).hasSize(fixture.publisher.machine().size());
        List<String> wrong = new ArrayList<>();
        for (OrderChain chain : chains) {
            FixMessage record = blotter.get(fixture.publisher.machine().keyForClOrdId(chain.rootClOrdId()));
            if (record == null) {
                wrong.add(chain + ": no record");
                continue;
            }
            if (!chain.ordStatus().equals(record.value(FixTags.ORD_STATUS))
                    || !Long.toString(chain.cumQty()).equals(record.value(FixTags.CUM_QTY))
                    || !Long.toString(chain.leavesQty()).equals(record.value(FixTags.LEAVES_QTY))) {
                wrong.add(chain + ": " + record.value(39) + "/" + record.value(14) + "/" + record.value(151));
            }
            if (record.value(FixTags.Record.MSG_COUNT).isEmpty()) {
                wrong.add(chain + ": no 9023");
            }
        }
        assertThat(wrong).isEmpty();
        assertThat(fixture.publisher.publishStoreDepth()).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("S13: the reset's sow_delete of terminal records is durable across a restart; open orders stay")
    void resetIsDurable() throws Exception {
        int before = fixture.sow.records(BLOTTER).size();
        int open = fixture.sow.records(BLOTTER, "/9017 = 'N'").size();
        assertThat(open).isPositive().isLessThan(before);
        fixture.reader.sowDelete(BLOTTER, "/9017 = 'Y'", fixture.properties.amps().timeoutMs());
        assertThat(fixture.sow.records(BLOTTER)).hasSize(open);

        fixture.server.restart();
        fixture.reconnectReaders();
        assertThat(fixture.sow.records(BLOTTER)).as("the journalled topic did not bring them back").hasSize(open);
        assertThat(fixture.sow.records(OrderStateFixture.ORDERS_AUDIT)).as("the audit topics are untouched")
                .isNotEmpty();
    }
}
