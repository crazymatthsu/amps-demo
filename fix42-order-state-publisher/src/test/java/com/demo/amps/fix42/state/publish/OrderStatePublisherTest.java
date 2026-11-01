package com.demo.amps.fix42.state.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crankuptheamps.client.Message;
import com.demo.amps.fix42.state.config.OrderStateProperties;
import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.hooks.DeskStatusHook;
import com.demo.amps.fix42.state.hooks.RejectStatusAtRequestTimeHook;
import com.demo.amps.fix42.state.it.OrderStateConfigurations;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.Outcome;
import com.demo.amps.fix42.state.machine.RecordWriter;
import com.demo.amps.fix42.state.spi.Decision;
import com.demo.amps.fix42.state.spi.IgnoreReason;
import com.demo.amps.fix42.state.spi.OrderStateHook;
import com.demo.amps.fix42.state.spi.OrderStateView;
import com.demo.amps.fix42.state.spi.RecordFields;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The publish path without a server: order of operations, two-phase apply, repairs and hooks. */
class OrderStatePublisherTest {

    private static final String URI = "tcp://127.0.0.1:9007/amps/fix";

    private final RecordingSink sink = new RecordingSink();
    private final RepairQueue repairs = new RepairQueue();

    private OrderStatePublisher publisher(String profile, OrderStateHook... hooks) {
        OrderStateProperties properties = OrderStateConfigurations.shipped(URI, profile);
        return new OrderStatePublisher(new OrderStateMachine(properties.machineOptions()),
                new RecordWriter(properties.machineOptions()), new AuditRouter(properties), sink, repairs,
                List.of(hooks), properties);
    }

    private static Outcome send(OrderStatePublisher publisher, String raw) throws Exception {
        return publisher.send(FixMessage.parse(raw.replace('|', '\u0001')));
    }

    @Test
    @DisplayName("plan, then the blotter publish, then the audit publishes, then commit")
    void orderOfOperations() throws Exception {
        OrderStatePublisher publisher = publisher("demo");
        send(publisher, "35=D|11=X|38=100|44=10|55=SYM|60=20261031-10:00:00.000|");
        send(publisher, "35=8|150=0|39=0|11=X|37=O|17=E-1|14=0|151=100|6=0|60=20261031-10:00:01.000|");
        assertThat(sink.topics()).containsExactly(
                "publish sow/fix42/order_state", "publish sow/fix42/orders_audit",
                "delta_publish sow/fix42/order_state", "publish sow/fix42/execs", "publish sow/fix42/execs_audit");
        assertThat(sink.sent.get(1).message().value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo("X");
        assertThat(sink.sent.get(4).message().value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo("X");
        assertThat(publisher.machine().state("X").venueStatus()).isEqualTo("0");
    }

    @Test
    @DisplayName("E73: a publish that throws before commit leaves the cache unchanged; the redelivery applies")
    void throwingPublishCommitsNothing() throws Exception {
        OrderStatePublisher publisher = publisher("demo");
        send(publisher, "35=D|11=X|38=100|44=10|60=20261031-10:00:00.000|");
        sink.throwOn = 2;  // the blotter delta goes in, the first audit publish throws
        assertThatThrownBy(() -> send(publisher,
                "35=8|150=0|39=0|11=X|37=O|17=E-1|14=0|151=100|6=0|60=20261031-10:00:01.000|"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(publisher.machine().state("X").venueStatus()).isEqualTo("A");
        assertThat(publisher.machine().index().keyForOrderId("O")).isNull();
        Outcome redelivered = send(publisher,
                "35=8|150=0|39=0|11=X|37=O|17=E-1|14=0|151=100|6=0|60=20261031-10:00:01.000|");
        assertThat(redelivered.kind()).isEqualTo(Outcome.Kind.CHANGED);
        assertThat(publisher.machine().state("X").venueStatus()).isEqualTo("0");
    }

    @Test
    @DisplayName("strict mode raises on an unrouted type before anything is published")
    void strictUnrouted() throws Exception {
        OrderStatePublisher publisher = publisher("demo");
        assertThatThrownBy(() -> send(publisher, "35=Q|17=E|")).isInstanceOf(UnroutedMessageException.class);
        assertThat(sink.sent).isEmpty();
    }

    @Test
    @DisplayName("lenient mode: an unrouted type goes to fix42/unrouted; malformed bytes to fix42/malformed")
    void lenientCatchAll() throws Exception {
        OrderStatePublisher publisher = publisher(null);
        send(publisher, "35=D|11=X|38=100|44=10|60=20261031-10:00:00.000|");
        send(publisher, "35=H|11=X|37=O|");
        assertThat(sink.last().topic()).isEqualTo("fix42/unrouted");
        assertThat(sink.last().message().value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo("X");
        byte[] junk = "35=8\u0001=oops\u0001".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        publisher.onMessage(junk, 0, junk.length);
        assertThat(sink.last().topic()).isEqualTo("fix42/malformed");
        assertThat(publisher.counters().malformed()).isEqualTo(1);
        assertThat(publisher.summary()).contains("unrouted[35=H]=1");
    }

    @Test
    @DisplayName("S2: a refused delta is repaired by a delta of every cached field, never a publish")
    void repairRefusedDelta() throws Exception {
        OrderStatePublisher publisher = publisher("demo");
        send(publisher, "35=D|11=X|38=100|44=10|55=SYM|60=20261031-10:00:00.000|");
        send(publisher, "35=8|150=0|39=0|11=X|37=O|17=E-1|14=0|151=100|6=0|60=20261031-10:00:01.000|");
        RecordingSink.Sent delta = sink.sent.get(2);
        repairs.add(new RepairQueue.Failure("sow/fix42/order_state", delta.sequence(), bytes(delta.message()),
                Message.Reason.ParseError, Message.Command.DeltaPublish));
        publisher.drainRepairs();
        RecordingSink.Sent repair = sink.last();
        assertThat(repair.command()).isEqualTo("delta_publish");
        assertThat(repair.message().value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo("X");
        assertThat(repair.message().value(FixTags.ORD_STATUS)).isEqualTo("0");
        assertThat(repair.message().has(FixTags.SYMBOL)).as("no descriptive terms: the D's survive").isFalse();
        assertThat(publisher.counters().repairs()).isEqualTo(1);
    }

    @Test
    @DisplayName("S2: a refused creation is re-sent whole; a keyless refused payload is found by its sequence")
    void repairRefusedCreation() throws Exception {
        OrderStatePublisher publisher = publisher("demo");
        send(publisher, "35=D|11=X|38=100|44=10|55=SYM|60=20261031-10:00:00.000|");
        RecordingSink.Sent creation = sink.sent.getFirst();
        repairs.add(new RepairQueue.Failure("sow/fix42/order_state", creation.sequence(), bytes(creation.message()),
                Message.Reason.SowStoreFailed, Message.Command.Publish));
        publisher.drainRepairs();
        assertThat(sink.last().command()).isEqualTo("publish");
        assertThat(sink.last().message().value(FixTags.SYMBOL)).isEqualTo("SYM");

        byte[] keyless = "39=0\u0001".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        publisher.publishRaw("X", false, keyless);
        repairs.add(new RepairQueue.Failure("sow/fix42/order_state", sink.last().sequence(), keyless,
                Message.Reason.BadSowKey, Message.Command.DeltaPublish));
        publisher.drainRepairs();
        assertThat(sink.last().command()).isEqualTo("delta_publish");
        assertThat(sink.last().message().value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo("X");
    }

    @Test
    @DisplayName("S2: a refused audit publish goes to the catch-all in lenient mode")
    void repairRefusedAudit() throws Exception {
        OrderStatePublisher publisher = publisher(null);
        byte[] payload = "35=D\u000111=X\u0001".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        repairs.add(new RepairQueue.Failure("sow/fix42/orders_audit", 7, payload, Message.Reason.ParseError,
                Message.Command.Publish));
        publisher.drainRepairs();
        assertThat(sink.last().topic()).isEqualTo("fix42/unrouted");
        assertThat(publisher.counters().auditPublishFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("the shipped hook writes a desk status in 9100 on every published change")
    void deskStatusHook() throws Exception {
        OrderStatePublisher publisher = publisher("demo", new DeskStatusHook());
        send(publisher, "35=D|11=X|38=100|44=10|60=20261031-10:00:00.000|");
        assertThat(sink.last().message().value(9100)).as("the audit copy carries no 9100").isEmpty();
        assertThat(sink.sent.getFirst().message().value(9100)).isEqualTo("NEW");
        send(publisher, "35=8|150=0|39=0|11=X|37=O|17=E-1|14=0|151=100|6=0|60=20261031-10:00:01.000|");
        assertThat(sink.sent.get(2).message().value(9100)).isEqualTo("WORKING");
        send(publisher, "35=F|11=X-2|41=X|60=20261031-10:00:02.000|");
        assertThat(sink.sent.get(5).message().value(9100)).isEqualTo("PENDING");
        send(publisher, "35=8|150=4|39=4|11=X-2|41=X|37=O|17=E-2|14=0|151=0|6=0|60=20261031-10:00:03.000|");
        assertThat(sink.sent.get(7).message().value(9100)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("hooks: a veto is IGNORED(HOOK); a normalised frame feeds the machine, the audit gets the original")
    void vetoAndNormalise() throws Exception {
        OrderStateHook hook = new OrderStateHook() {
            @Override
            public Decision beforeApply(FixFrame message, OrderStateView state) {
                if ("TEST".equals(message.value(FixTags.ACCOUNT))) {
                    return Decision.ignore("test account");
                }
                if ("D".equals(message.value(FixTags.EXEC_TYPE))) {
                    return Decision.proceedWith(rewrite(message, "150=D", "150=5"));
                }
                return Decision.proceed();
            }
        };
        OrderStatePublisher publisher = publisher("demo", hook);
        send(publisher, "35=D|11=X|38=100|44=10|60=20261031-10:00:00.000|");
        send(publisher, "35=G|11=X-2|41=X|38=100|44=11|59=1|60=20261031-10:00:01.000|");
        // A venue that answers a TIF-only amend with 150=D: normalised to 150=5.
        send(publisher, "35=8|150=D|39=0|11=X-2|41=X|37=O|17=E-1|38=100|44=11|59=1|14=0|151=100|6=0"
                + "|60=20261031-10:00:02.000|");
        assertThat(publisher.machine().state("X").pending()).isEmpty();
        assertThat(sink.last().message().value(FixTags.EXEC_TYPE)).as("the audit copy is the original").isEqualTo("D");
        Outcome vetoed = send(publisher, "35=8|150=1|39=1|11=X-2|37=O|17=E-2|1=TEST|14=50|151=50|6=11|32=50|31=11"
                + "|60=20261031-10:00:03.000|");
        assertThat(vetoed.reason()).isEqualTo(IgnoreReason.HOOK);
        assertThat(publisher.machine().state("X").cumQty()).isEqualTo("0");
    }

    @Test
    @DisplayName("docs/11 F2: the request-time reject hook feeds the machine 39=1; the rejects topic keeps 39=6")
    void requestTimeRejectHook() throws Exception {
        OrderStatePublisher publisher = publisher("demo", new RejectStatusAtRequestTimeHook());
        send(publisher, "35=D|11=X|38=100|44=10|60=20261031-10:00:00.000|");
        send(publisher, "35=8|150=0|39=0|11=X|37=O|17=E-1|14=0|151=100|6=0|60=20261031-10:00:01.000|");
        send(publisher, "35=8|150=1|39=1|11=X|37=O|17=E-2|14=40|151=60|6=10|32=40|31=10|60=20261031-10:00:02.000|");
        send(publisher, "35=F|11=X-2|41=X|60=20261031-10:00:03.000|");
        send(publisher, "35=8|150=6|39=6|11=X-2|41=X|37=O|17=E-3|14=40|151=60|6=10|60=20261031-10:00:04.000|");
        int before = sink.sent.size();
        send(publisher, "35=9|11=X-2|41=X|37=O|434=1|39=6|60=20261031-10:00:05.000|");
        assertThat(sink.sent).hasSize(before + 2);
        assertThat(sink.sent.get(before).topic()).isEqualTo("sow/fix42/order_state");
        assertThat(sink.sent.get(before).message().value(FixTags.ORD_STATUS)).isEqualTo("1");
        assertThat(sink.last().topic()).isEqualTo("sow/fix42/rejects");
        assertThat(sink.last().message().value(FixTags.ORD_STATUS)).as("the audit copy is the original").isEqualTo("6");
        assertThat(publisher.machine().state("X").venueStatus()).isEqualTo("1");
        assertThat(publisher.machine().state("X").pending()).isEmpty();
    }

    @Test
    @DisplayName("E74: a hook that throws stops the feed at that message with nothing committed")
    void throwingHook() throws Exception {
        OrderStateHook broken = new OrderStateHook() {
            @Override
            public void afterApply(FixFrame message, OrderStateView before, OrderStateView after, Outcome outcome,
                                   RecordFields extra) {
                throw new IllegalStateException("hook bug");
            }
        };
        OrderStatePublisher publisher = publisher("demo", broken);
        send(publisher, "35=D|11=X|38=100|44=10|60=20261031-10:00:00.000|");
        int published = sink.sent.size();
        assertThatThrownBy(() -> send(publisher,
                "35=8|150=0|39=0|11=X|37=O|17=E-1|14=0|151=100|6=0|60=20261031-10:00:01.000|"))
                .hasMessage("hook bug");
        assertThat(sink.sent).hasSize(published);
        assertThat(publisher.machine().state("X").venueStatus()).isEqualTo("A");
    }

    @Test
    @DisplayName("RecordFields refuses the machine's tags")
    void recordFieldsRange() {
        RecordFields fields = new RecordFields();
        assertThatThrownBy(() -> fields.put(39, "4")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fields.put(9017, "Y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fields.put(15035, "NONE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fields.put(9100, "")).isInstanceOf(IllegalArgumentException.class);
        assertThat(fields.put(9100, "OK").get(9100)).isEqualTo("OK");
    }

    private static byte[] bytes(FixMessage message) {
        FixWriter writer = new FixWriter(256);
        message.writeTo(writer);
        return java.util.Arrays.copyOf(writer.buffer(), writer.size());
    }

    private static FixFrame rewrite(FixFrame message, String from, String to) {
        String text = message.asString().replace(from, to);
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return new FixFrame().wrap(bytes, 0, bytes.length);
    }
}
