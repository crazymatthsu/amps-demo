package com.demo.amps.fix42.state.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.config.OrderStateProperties;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.it.OrderStateConfigurations;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.Outcome;
import com.demo.amps.fix42.state.machine.Projection;
import com.demo.amps.fix42.state.machine.RecordWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * docs/07 §5: the routes' {@code tags}/{@code changeable-tags} and
 * {@code projection}, with fix42-publisher's keys, over the order-state
 * machine. The routes are {@code projection-routes.yml}'s.
 */
class ProjectionTest {

    private static final String URI = "tcp://127.0.0.1:9007/amps/fix";
    private static final String BLOTTER = "sow/fix42/order_state";

    private final RecordingSink sink = new RecordingSink();

    private OrderStatePublisher publisher(OrderStateProperties properties, RoutingOptions routing) {
        properties.validate();
        return new OrderStatePublisher(new OrderStateMachine(properties.machineOptions()),
                new RecordWriter(properties.machineOptions()), new AuditRouter(routing), sink,
                new RepairQueue(), List.of(), properties.publisherOptions());
    }

    private OrderStatePublisher publisher() {
        OrderStateProperties properties = OrderStateConfigurations.withYaml(URI, "demo", "projection-routes.yml");
        return publisher(properties, properties.routingOptions());
    }

    private static Outcome send(OrderStatePublisher publisher, String raw) throws Exception {
        return publisher.send(FixMessage.parse(raw.replace('|', '\u0001')));
    }

    /** The blotter payload the last message produced. */
    private FixMessage blotter() {
        for (int i = sink.sent.size() - 1; i >= 0; i--) {
            if (sink.sent.get(i).topic().equals(BLOTTER)) {
                return sink.sent.get(i).message();
            }
        }
        throw new AssertionError("no blotter publish");
    }

    private FixMessage last(String topic) {
        for (int i = sink.sent.size() - 1; i >= 0; i--) {
            if (sink.sent.get(i).topic().equals(topic)) {
                return sink.sent.get(i).message();
            }
        }
        throw new AssertionError("no publish to " + topic);
    }

    private static final String D =
            "35=D|11=X|1=ACC|21=1|54=1|55=SYM|38=100|44=10|40=2|59=0|58=hello|9500=desk|60=20261031-10:00:00.000|";
    private static final String ACK =
            "35=8|150=0|39=0|11=X|37=O|17=E-1|14=0|151=100|6=0|59=0|58=acked|60=20261031-10:00:01.000|";

    @Test
    @DisplayName("a D's projection tags replace its default pass-through; the audit copy stays whole")
    void newOrderReplacesPassThrough() throws Exception {
        OrderStatePublisher publisher = publisher();
        send(publisher, D);
        FixMessage record = blotter();
        assertThat(sink.sent.getFirst().command()).isEqualTo("publish");
        for (int tag : new int[] {1, 54, 55, 40, 59, 9500}) {
            assertThat(record.has(tag)).as("tag %d", tag).isTrue();
        }
        assertThat(record.has(21)).as("not listed").isFalse();
        assertThat(record.has(58)).as("not listed").isFalse();
        assertThat(record.value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo("X");
        assertThat(record.value(FixTags.ORDER_QTY)).as("the machine's own").isEqualTo("100");
        assertThat(record.value(FixTags.Record.PENDING_ACTION)).isEqualTo("NEW");
        FixMessage audit = last("sow/fix42/orders_audit");
        assertThat(audit.value(21)).isEqualTo("1");
        assertThat(audit.value(58)).isEqualTo("hello");
    }

    @Test
    @DisplayName("an acknowledgement adopts 59; a literal beats a copy of the same tag")
    void acknowledgement() throws Exception {
        OrderStatePublisher publisher = publisher();
        send(publisher, D);
        send(publisher, ACK);
        FixMessage record = blotter();
        assertThat(record.value(59)).isEqualTo("0");
        assertThat(record.value(58)).isEqualTo("acked");
        assertThat(record.value(9601)).isEqualTo("ACK");
        assertThat(record.value(FixTags.ORD_STATUS)).isEqualTo("0");
    }

    @Test
    @DisplayName("an amend: the audit gets the selection; the record gets 58 and the proposed 59 as 9059, never 59")
    void amend() throws Exception {
        OrderStatePublisher publisher = publisher();
        send(publisher, D);
        send(publisher, ACK);
        send(publisher, "35=G|11=X-2|41=X|38=200|44=10|59=1|58=bigger|21=1|60=20261031-10:00:02.000|");
        FixMessage record = blotter();
        assertThat(record.value(58)).isEqualTo("bigger");
        assertThat(record.value(9059)).isEqualTo("1");
        assertThat(record.has(59)).as("a request's order term is a proposal").isFalse();
        assertThat(record.has(FixTags.ORDER_QTY)).as("38 stays the acked quantity").isFalse();
        assertThat(record.value(FixTags.Record.PENDING_ORDER_QTY)).isEqualTo("200");
        assertThat(last("sow/fix42/orders_audit").printable())
                .isEqualTo("35=G|11=X-2|41=X|60=20261031-10:00:02.000|38=200|44=10|59=1|13011=X|");

        send(publisher, "35=8|150=E|39=E|11=X-2|41=X|37=O|17=E-2|14=0|151=100|6=0|59=1|60=20261031-10:00:03.000|");
        assertThat(blotter().has(59)).as("a pending acknowledgement echoes the proposal").isFalse();
        send(publisher, "35=8|150=5|39=0|11=X-2|41=X|37=O|17=E-3|14=0|151=200|6=0|38=200|44=10|59=1|"
                + "60=20261031-10:00:04.000|");
        assertThat(blotter().value(59)).as("the venue accepted it").isEqualTo("1");
        assertThat(blotter().value(FixTags.ORDER_QTY)).isEqualTo("200");
    }

    @Test
    @DisplayName("a fill: the exec topics get the selection; the record gets tags, copies and literals")
    void fill() throws Exception {
        OrderStatePublisher publisher = publisher();
        send(publisher, D);
        send(publisher, ACK);
        String fill = "35=8|150=1|39=1|11=X|37=O|17=F-1|32=10|31=10|14=10|151=90|6=10|58=part|9500=d2|21=1|"
                + "60=20261031-10:00:05.000|";
        send(publisher, fill);
        assertThat(last("sow/fix42/execs").printable())
                .isEqualTo("35=8|11=X|37=O|17=F-1|39=1|150=1|60=20261031-10:00:05.000|31=10|32=10|14=10|151=90|6=10|"
                        + "13011=X|");
        assertThat(sink.sent).filteredOn(sent -> sent.topic().startsWith("sow/fix42/execs"))
                .extracting(RecordingSink.Sent::command).as("a selection merges, as fix42-publisher's DELTA routes")
                .containsOnly("publish", "delta_publish").last().isEqualTo("delta_publish");
        FixMessage record = blotter();
        assertThat(record.value(58)).isEqualTo("part");
        assertThat(record.value(9500)).isEqualTo("d2");
        assertThat(record.value(9017)).isEqualTo("F-1");
        assertThat(record.value(9600)).isEqualTo("FILL");
        assertThat(record.value(FixTags.LAST_SHARES)).as("the machine's fill detail").isEqualTo("10");
        assertThat(record.has(21)).isFalse();

        int before = sink.sent.size();
        Outcome duplicate = send(publisher, fill.replace("58=part", "58=again"));
        assertThat(duplicate.kind()).isEqualTo(Outcome.Kind.IGNORED);
        assertThat(sink.sent.subList(before, sink.sent.size()))
                .filteredOn(sent -> sent.topic().equals(BLOTTER))
                .allSatisfy(sent -> assertThat(sent.message().has(58)).as("an ignored message projects nothing")
                        .isFalse());
    }

    @Test
    @DisplayName("a projection without tags adds its copies to the default pass-through, and a copy beats it")
    void additive() throws Exception {
        OrderStateProperties properties = OrderStateConfigurations.shipped(URI, "demo");
        RoutingOptions shipped = properties.routingOptions();
        List<Route> routes = new ArrayList<>(shipped.routes());
        Route d = routes.getFirst();
        routes.set(0, new Route(d.name(), d.msgTypes(), d.execTypes(), d.execTransTypes(), d.topics(),
                d.selectedTags(), d.keyGuard(), d.applyState(),
                new Projection(List.of(), Map.of(58, 21), Map.of(9600, "NEW-ORDER"))));
        OrderStatePublisher publisher = publisher(properties,
                new RoutingOptions(shipped.mode(), shipped.keyTag(), shipped.topicKeys(), routes));
        send(publisher, D);
        FixMessage record = blotter();
        assertThat(record.value(55)).as("the default pass-through").isEqualTo("SYM");
        assertThat(record.value(21)).as("the copy, not the D's own 21").isEqualTo("hello");
        assertThat(record.value(9600)).isEqualTo("NEW-ORDER");
        send(publisher, "35=F|11=X-3|41=X|58=stop|60=20261031-10:00:06.000|");
        assertThat(blotter().has(9600)).as("the F's route has no projection").isFalse();
    }

    @Test
    @DisplayName("a cancel request's route copies 58 under 9058 beside the pending set")
    void cancelRequest() throws Exception {
        OrderStatePublisher publisher = publisher();
        send(publisher, D);
        send(publisher, ACK);
        send(publisher, "35=F|11=X-3|41=X|58=stop|60=20261031-10:00:06.000|");
        FixMessage record = blotter();
        assertThat(record.value(9058)).isEqualTo("stop");
        assertThat(record.value(FixTags.Record.PENDING_ACTION)).isEqualTo("CANCEL");
        assertThat(record.has(58)).isFalse();
    }
}
