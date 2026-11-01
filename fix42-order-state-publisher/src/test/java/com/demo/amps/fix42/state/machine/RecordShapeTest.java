package com.demo.amps.fix42.state.machine;

import static com.demo.amps.fix42.state.machine.MachineTester.t;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.mock.EdgeCaseFlow;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.mock.MockDialect;
import com.demo.amps.fix42.state.mock.MockFixFlow;
import com.demo.amps.fix42.state.mock.OrderChain;
import com.demo.amps.fix42.state.spi.IgnoreReason;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The record as an encoding: round trip, payload shapes, codecs, and the two-phase contract. */
class RecordShapeTest {

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        static List<MockDialect.Named> dialects() {
            return MockDialect.variants();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dialects")
        @DisplayName("every state the flows reach, written whole and parsed back, is the same record")
        void everyReachableState(MockDialect.Named dialect) {
            List<OrderChain> chains = new ArrayList<>(MockFixFlow.chains());
            chains.addAll(EdgeCaseFlow.chains(dialect.dialect()));
            MachineTester m = new MachineTester();
            int checked = 0;
            for (OrderChain chain : chains) {
                for (FixEvent event : chain.events()) {
                    Outcome outcome = m.send(event.message());
                    if (outcome.key() == null || m.state(outcome.key()) == null) {
                        continue;
                    }
                    OrderState state = m.state(outcome.key());
                    RecordReader.Result read = RecordReader.read(m.cached(outcome.key()), 2);
                    assertThat(read.unrecovered()).as("%s %s", chain, event.description()).isFalse();
                    OrderState rebuilt = read.state();
                    if (state.clOrdIds().size() > m.machine.options().chainIdsPublished()) {
                        // 12021 is capped; the rest are on orders_audit under 13011.
                        assertThat(rebuilt.clOrdIds()).hasSize(m.machine.options().chainIdsPublished());
                        continue;
                    }
                    assertThat(rebuilt.sameRecordAs(state))
                            .as("%s after %s:%n cached  %s%n rebuilt %s", chain, event.description(),
                                    m.cached(outcome.key()).printable(), rebuilt)
                            .isTrue();
                    checked++;
                }
            }
            assertThat(checked).isGreaterThan(400);
        }

        @Test
        @DisplayName("a record whose 12015 does not parse loads empty, UNRECOVERED, and counted as such")
        void unparseablePendingSet() {
            FixMessage record = FixMessage.parse("13011=K\u000112015=CANCEL:R:X-2\u000112024=2\u0001");
            RecordReader.Result read = RecordReader.read(record, 2);
            assertThat(read.unrecovered()).isTrue();
            assertThat(read.state().origin()).isEqualTo(Origin.UNRECOVERED);
            assertThat(read.state().pending()).isEmpty();
        }

        @Test
        @DisplayName("a record without 13011, or of a newer schema, refuses the load (R17)")
        void refusals() {
            assertThatThrownBy(() -> RecordReader.read(FixMessage.parse("11=X\u0001"), 2))
                    .hasMessageContaining("13011");
            assertThatThrownBy(() -> RecordReader.read(FixMessage.parse("13011=K\u000112024=3\u0001"), 2))
                    .hasMessageContaining("schema 3");
        }

        @Test
        @DisplayName("docs/11 §6: a record of the 9xxx scheme (schema 1) refuses the load instead of losing its state")
        void legacyTagScheme() {
            FixMessage legacy = FixMessage.parse(("13011=K|9024=1|9015=CANCEL:R:::K-2|9016=1|9021=K,K-2|9030=2"
                    + "|9023=4|39=6|").replace('|', '\u0001'));
            assertThatThrownBy(() -> RecordReader.read(legacy, 2))
                    .hasMessageContaining("9xxx tags of schema 1")
                    .hasMessageContaining("9024=1")
                    .hasMessageContaining("docs/11 §6");
        }

        @Test
        @DisplayName("a record missing 12026 takes the default and says so")
        void defaults() {
            RecordReader.Result read = RecordReader.read(
                    FixMessage.parse("13011=K\u000112024=2\u000112015=NONE\u000112023=3\u000112025=0\u000112018=D\u0001"
                            + "12019=C\u0001"), 2);
            assertThat(read.defaulted()).isTrue();
            assertThat(read.state().lateFills()).isZero();
        }
    }

    @Nested
    @DisplayName("payload shapes")
    class Shapes {

        @Test
        @DisplayName("every payload carries 13011, never 35, never an empty value")
        void keyAlwaysNoMsgTypeNoEmpty() {
            MachineTester m = new MachineTester();
            for (FixEvent event : EdgeCaseFlow.events()) {
                m.send(event.message());
                FixMessage wire = m.wire();
                if (wire == null) {
                    continue;
                }
                assertThat(wire.has(FixTags.Record.ROOT_CL_ORD_ID)).as(event.description()).isTrue();
                assertThat(wire.has(FixTags.MSG_TYPE)).as(event.description()).isFalse();
                assertThat(wire.asMap().values()).as(event.description()).doesNotContain("");
            }
        }

        @Test
        @DisplayName("an ignored message publishes nothing; 12025 rides on the next delta")
        void ignoredIsCarried() {
            MachineTester m = new MachineTester();
            m.send("D 11=X 38=100 44=10 60=" + t(0));
            m.send("8 150=0 39=0 11=X 37=O 17=E-1 14=0 151=100 6=0 60=" + t(1));
            assertThat(m.send("8 150=0 39=0 11=X 37=O 17=E-1 14=0 151=100 6=0 60=" + t(1)).publish())
                    .isEqualTo(Outcome.Publish.NONE);
            m.send("F 11=X-2 41=X 60=" + t(2));
            m.expectWire(12025);
            m.expect("X", "12025=1");
        }

        @Test
        @DisplayName("publish-unchanged: 12025 at once, and 12021/12030 when an id was bound; never 12023 or 60")
        void publishUnchanged() {
            MachineTester m = new MachineTester(MachineOptions.defaults().withPublishUnchanged(true));
            m.send("D 11=X 38=100 44=10 60=" + t(0));
            m.send("8 150=0 39=0 11=X 37=O 17=E-1 14=0 151=100 6=0 60=" + t(1));
            m.send("8 150=0 39=0 11=X 37=O 17=E-1 14=0 151=100 6=0 60=" + t(1));
            m.expectWire(13011, 12025, -12023, -60, -12021);
            // A duplicate that binds a new 41 publishes the chain whatever the policy.
            MachineTester plain = new MachineTester();
            plain.send("D 11=Y 38=100 44=10 60=" + t(0));
            plain.send("8 150=0 39=0 11=Y 37=O 17=E-1 14=0 151=100 6=0 60=" + t(1));
            Outcome duplicate = plain.send("8 150=0 39=0 11=Y 41=Y-0 37=O 17=E-1 14=0 151=100 6=0 60=" + t(1));
            assertThat(duplicate.reason()).isEqualTo(IgnoreReason.DUPLICATE);
            plain.expectWire(12021, 12030, 12025, -12023, -60);
        }

        @Test
        @DisplayName("a repair is every cached field as a delta: no descriptive terms, the D's survive the merge")
        void repairIsAllCachedFields() {
            MachineTester m = new MachineTester();
            m.send("D 11=X 38=100 44=10 1=ACC 55=SYM 54=1 60=" + t(0));
            m.send("8 150=0 39=0 11=X 37=O 17=E-1 14=0 151=100 6=0 60=" + t(1));
            FixMessage repair = m.cached("X");
            assertThat(repair.has(FixTags.ACCOUNT)).isFalse();
            assertThat(repair.has(FixTags.SYMBOL)).isFalse();
            assertThat(repair.value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo("X");
            assertThat(repair.value(FixTags.ORD_STATUS)).isEqualTo("0");
            assertThat(repair.value(FixTags.Record.PENDING_REQUESTS)).isEqualTo("NONE");
        }

        @Test
        @DisplayName("a fill puts about a dozen tags on the wire, a pending ack about five beyond bookkeeping")
        void deltasAreSmall() {
            MachineTester m = new MachineTester();
            m.send("D 11=X 38=1000 44=10 1=ACC 55=SYM 54=1 60=" + t(0));
            m.send("8 150=0 39=0 11=X 37=O 17=E-1 14=0 151=1000 6=0 38=1000 44=10 60=" + t(1));
            m.send("8 150=1 39=1 11=X 37=O 17=E-2 14=100 151=900 6=10 38=1000 44=10 32=100 31=10 30=XNAS 60="
                    + t(2));
            assertThat(m.wire().size()).isBetween(15, 24);
            m.send("F 11=X-2 41=X 60=" + t(3));
            m.send("8 150=6 39=6 11=X-2 41=X 37=O 17=E-3 60=" + t(4));
            assertThat(m.wire().size()).isBetween(8, 14);
        }
    }

    @Nested
    @DisplayName("two phases")
    class TwoPhases {

        @Test
        @DisplayName("plan leaves the entry untouched; a second plan without commit returns the same outcome")
        void planIsPure() {
            OrderStateMachine machine = new OrderStateMachine(MachineOptions.defaults());
            RecordWriter writer = new RecordWriter(MachineOptions.defaults());
            commit(machine, "D 11=X 38=100 44=10 60=" + t(0));
            OrderState before = machine.state("X");
            String beforeRecord = whole(writer, before);

            FixFrame fill = frame("8 150=1 39=1 11=X 37=O 17=E-1 14=50 151=50 6=10 32=50 31=10 60=" + t(1));
            Outcome first = machine.plan(fill);
            Outcome second = machine.plan(fill);
            assertThat(machine.state("X")).isSameAs(before);
            assertThat(whole(writer, machine.state("X"))).isEqualTo(beforeRecord);
            assertThat(machine.index().keyForOrderId("O")).isNull();
            assertThat(payload(writer, second, fill)).isEqualTo(payload(writer, first, fill));
            assertThat(machine.counters().get(Counter.CHANGED)).isZero();

            machine.commit(second);
            assertThat(machine.state("X").cumQty()).isEqualTo("50");
            assertThat(machine.index().keyForOrderId("O")).isEqualTo("X");
            assertThat(machine.counters().get(Counter.CHANGED)).isEqualTo(1);
        }

        @Test
        @DisplayName("committing a stale plan, or the same plan twice, is refused")
        void staleCommit() {
            OrderStateMachine machine = new OrderStateMachine(MachineOptions.defaults());
            FixFrame d = frame("D 11=X 38=100 44=10 60=" + t(0));
            Outcome stale = machine.plan(d);
            Outcome latest = machine.plan(d);
            assertThatThrownBy(() -> machine.commit(stale)).isInstanceOf(IllegalStateException.class);
            machine.commit(latest);
            assertThatThrownBy(() -> machine.commit(latest)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a publish that throws before commit leaves the message redeliverable (E73)")
        void redeliveryAfterAFailedPublish() {
            OrderStateMachine machine = new OrderStateMachine(MachineOptions.defaults());
            commit(machine, "D 11=X 38=100 44=10 60=" + t(0));
            FixFrame ack = frame("8 150=0 39=0 11=X 37=O 17=E-1 14=0 151=100 6=0 60=" + t(1));
            machine.plan(ack);
            // ... the publish threw; the feed redelivers ...
            Outcome again = machine.plan(ack);
            assertThat(again.kind()).isEqualTo(Outcome.Kind.CHANGED);
            machine.commit(again);
            assertThat(machine.state("X").venueStatus()).isEqualTo("0");
        }

        private static void commit(OrderStateMachine machine, String spec) {
            machine.commit(machine.plan(frame(spec)));
        }

        private static FixFrame frame(String spec) {
            FixWriter source = new FixWriter(256);
            MachineTester.parse(spec).writeTo(source);
            return new FixFrame().wrap(java.util.Arrays.copyOf(source.buffer(), source.size()), 0, source.size());
        }

        private static String whole(RecordWriter writer, OrderState state) {
            FixWriter out = new FixWriter(256);
            writer.writeAll(state, out);
            return out.printable();
        }

        private static String payload(RecordWriter writer, Outcome outcome, FixFrame message) {
            FixWriter out = new FixWriter(256);
            writer.write(outcome, message, out);
            return out.printable();
        }
    }

    @Nested
    @DisplayName("codecs and numbers")
    class Codecs {

        @Test
        @DisplayName("hostile ids round-trip through 12015 and 12021")
        void escaping() {
            List<PendingRequest> pending = List.of(
                    PendingRequest.requested("a:b;c,d%e", PendingAction.REPLACE, "12000", null),
                    PendingRequest.inferred("%3A", PendingAction.CANCEL));
            String encoded = RecordCodec.encodePending(pending);
            assertThat(encoded).isEqualTo("REPLACE:R:12000::a%3Ab%3Bc%2Cd%25e;CANCEL:I:::%253A");
            assertThat(RecordCodec.decodePending(encoded)).isEqualTo(pending);
            assertThat(RecordCodec.decodeIds(RecordCodec.encodeIds(List.of("x,y", "z%"), 50)))
                    .containsExactly("x,y", "z%");
            assertThat(RecordCodec.encodePending(List.of())).isEqualTo("NONE");
        }

        @Test
        @DisplayName("a zero price and no proposal never read the same (S17)")
        void zeroIsNotEmpty() {
            List<PendingRequest> pending = List.of(
                    PendingRequest.requested("A", PendingAction.REPLACE, "100", "0"),
                    PendingRequest.requested("B", PendingAction.REPLACE, "100", null));
            assertThat(RecordCodec.encodePending(pending)).isEqualTo("REPLACE:R:100:0:A;REPLACE:R:100::B");
        }

        @Test
        @DisplayName("malformed encodings are refused, not read as empty")
        void malformed() {
            assertThatThrownBy(() -> RecordCodec.decodePending("CANCEL:R:X")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RecordCodec.decodePending("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RecordCodec.unescape("a%ZZ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RecordCodec.decodePending("PAUSE:R:::X")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("quantities compare as numbers: 300, 300.0 and 0300 are equal")
        void numeric() {
            assertThat(Quantities.equal("300", "300.0")).isTrue();
            assertThat(Quantities.equal("0300", "300")).isTrue();
            assertThat(Quantities.lower("299.5", "300")).isTrue();
            assertThat(Quantities.lower("", "300")).isFalse();

            MachineTester m = new MachineTester();
            m.send("D 11=X 38=1000 44=10 60=" + t(0));
            m.send("8 150=1 39=1 11=X 37=O 17=E-1 14=300 151=700 6=10 32=300 31=10 60=" + t(1));
            // Equal CumQty written another way, an older time: late, not a fresh fill.
            assertThat(m.send("8 150=1 39=1 11=X 37=O 17=E-2 14=300.0 151=700 6=10 60=" + t(0)).reason())
                    .isEqualTo(IgnoreReason.LATE);
            assertThat(m.send("8 150=1 39=1 11=X 37=O 17=E-3 14=0299 151=701 6=10 60=" + t(2)).reason())
                    .isEqualTo(IgnoreReason.STALE);
        }

        @Test
        @DisplayName("TransactTime without milliseconds compares on whole seconds")
        void seconds() {
            assertThat(Timestamps.earlier("20261031-10:00:00", "20261031-10:00:00.900")).isFalse();
            assertThat(Timestamps.earlier("20261031-10:00:00.100", "20261031-10:00:00.900")).isTrue();
            assertThat(Timestamps.later("20261031-10:00:01", "20261031-10:00:00.900")).isTrue();
            assertThat(Timestamps.earlier("garbage", "20261031-10:00:00")).isFalse();
            assertThat(Timestamps.max("20261031-10:00:01.000", "20261031-10:00:00.500"))
                    .isEqualTo("20261031-10:00:01.000");
        }
    }
}
