package com.demo.amps.fix42.state.machine;

import static com.demo.amps.fix42.state.machine.MachineTester.t;
import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.spi.IgnoreReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The walkthroughs of docs/03 §7, one nested class each, asserting the
 * {@code sow/fix42/order_state} record after every message as the tables
 * state it. The record is the one a subscriber would read: the merge of the
 * payloads the machine published, not the cache.
 */
class WalkthroughTest {

    /** D, ack, a partial fill of 300: the order 7.1-7.3 and 7.12 start from. */
    private static MachineTester partiallyFilledX() {
        MachineTester m = new MachineTester();
        m.send("D 11=X-1 38=1000 44=50 1=ACC 55=SYM 54=1 40=2 59=0 60=" + t(0));
        m.send("8 150=0 39=0 11=X-1 37=ORD 17=E-1 38=1000 44=50 14=0 151=1000 6=0 60=" + t(1));
        m.send("8 150=1 39=1 11=X-1 37=ORD 17=E-2 38=1000 44=50 14=300 151=700 6=50 32=300 31=50 30=XNAS 60="
                + t(2));
        return m;
    }

    @Nested
    @DisplayName("7.1 two cancels: confirm, then reject (the motivating case)")
    class TwoCancelsConfirmThenReject {

        @Test
        void recordAfterEveryMessage() {
            MachineTester m = new MachineTester();
            Outcome created = m.send("D 11=X-1 38=1000 44=50 1=ACC 55=SYM 54=1 40=2 59=0 60=" + t(0));
            assertThat(created.kind()).isEqualTo(Outcome.Kind.CREATED);
            assertThat(created.publish()).isEqualTo(Outcome.Publish.FULL);
            m.expect("X-1", "13011=X-1 39=A 151=1000 12017=N 15035=NEW 15011=X-1 12016=1 12015=NEW:R:::X-1 "
                    + "12014=X-1 15038=0 15044=0 12018=D 12019=C 12021=X-1 12022=D 12023=1 12024=2 12025=0 12026=0 "
                    + "12030=1 14=0 6=0 1=ACC 55=SYM 54=1 38=1000 44=50 35=-");

            m.send("8 150=0 39=0 11=X-1 37=ORD 17=E-1 38=1000 44=50 14=0 151=1000 6=0 60=" + t(1));
            m.expect("X-1", "39=0 151=1000 12017=N 15035=NONE 15011=NONE 12016=0 12015=NONE 12014=X-1 37=ORD");
            m.expectWire(37, 17, 150, 39, 38, 44, 14, 151, 6, 12014, 12015, 12016, 12029, 13011);

            m.send("8 150=1 39=1 11=X-1 37=ORD 17=E-2 38=1000 44=50 14=300 151=700 6=50 32=300 31=50 30=XNAS 60="
                    + t(2));
            m.expect("X-1", "39=1 151=700 12017=N 15035=NONE 12016=0 32=300 31=50 30=XNAS 12028=E-2");
            m.expectWire(17, 150, 39, 38, 44, 14, 151, 6, 32, 31, 30, 12028, 12029);

            m.send("F 11=X-2 41=X-1 60=" + t(3));
            m.expect("X-1", "39=1 151=700 12017=N 15035=CANCEL 15011=X-2 12016=1 12015=CANCEL:R:::X-2 "
                    + "12021=X-1,X-2 12030=2");
            m.expectWire(12021, 12030, 15011, 15035, 12015, 12016, -39, -38, -44);

            m.send("F 11=X-3 41=X-1 60=" + t(4));
            m.expect("X-1", "39=1 151=700 15035=CANCEL 15011=X-3 12016=2 "
                    + "12015=CANCEL:R:::X-2;CANCEL:R:::X-3");

            m.send("8 150=4 39=4 11=X-2 41=X-1 37=ORD 17=E-3 38=1000 44=50 14=300 151=0 6=50 60=" + t(5));
            m.expect("X-1", "39=4 151=0 12017=Y 15035=CANCEL 15011=X-3 12016=1 12015=CANCEL:R:::X-3");

            m.send("9 11=X-3 41=X-1 37=ORD 434=1 39=6 60=" + t(6));
            m.expect("X-1", "39=4 150=4 151=0 12017=Y 15035=NONE 15011=NONE 12016=0 12015=NONE 12022=9");
            m.expectWire(15035, 12015, 12016, 12029, -39);
        }
    }

    @Nested
    @DisplayName("7.2 two cancels: reject, then confirm")
    class TwoCancelsRejectThenConfirm {

        @Test
        void bothOrdersConverge() {
            MachineTester m = partiallyFilledX();
            m.send("F 11=X-2 41=X-1 60=" + t(3));
            m.send("F 11=X-3 41=X-1 60=" + t(4));

            // 39=6 adopted: not terminal, not 8, and a CANCEL is still pending.
            m.send("9 11=X-3 41=X-1 37=ORD 434=1 39=6 60=" + t(5));
            m.expect("X-1", "39=6 151=700 12017=N 15035=CANCEL 15011=X-2 12016=1");

            m.send("8 150=4 39=4 11=X-2 41=X-1 37=ORD 17=E-3 38=1000 44=50 14=300 151=0 6=50 60=" + t(6));
            m.expect("X-1", "39=4 151=0 12017=Y 15035=NONE 15011=NONE 12016=0");
        }
    }

    @Nested
    @DisplayName("docs/11 F4: a confirm overtaken by a later-stamped reject, on a tap merging sessions")
    class ConfirmOvertakenByReject {

        @Test
        void theConfirmIsDroppedAsLateAndTheOrderStaysLive() {
            // The venue confirmed X-2 at T5 and rejected X-3 at T6; the tap
            // delivered the reject first. The reject's 60 moved 12029 (S19), so
            // the confirm -- equal CumQty, older 60 -- is IGNORED(late) at §5
            // step 4, before the after-terminal table could see it.
            MachineTester m = partiallyFilledX();
            m.send("F 11=X-2 41=X-1 60=" + t(3));
            m.send("F 11=X-3 41=X-1 60=" + t(4));
            m.send("9 11=X-3 41=X-1 37=ORD 434=1 39=6 60=" + t(6));
            assertThat(m.send("8 150=4 39=4 11=X-2 41=X-1 37=ORD 17=E-3 14=300 151=0 6=50 60=" + t(5)).reason())
                    .isEqualTo(IgnoreReason.LATE);
            m.expect("X-1", "39=6 151=700 12017=N 12016=1 15011=X-2");
        }
    }

    @Nested
    @DisplayName("7.3 a third cancel after the order is canceled")
    class ThirdCancel {

        @Test
        void aRequestNeverMovesTheStatus() {
            MachineTester m = partiallyFilledX();
            m.send("F 11=X-2 41=X-1 60=" + t(3));
            m.send("8 150=4 39=4 11=X-2 41=X-1 37=ORD 17=E-3 14=300 151=0 6=50 60=" + t(4));
            m.send("F 11=X-4 41=X-1 60=" + t(5));
            m.expect("X-1", "39=4 12017=Y 15035=CANCEL 15011=X-4 12016=1");
            m.send("9 11=X-4 41=X-1 434=1 39=4 60=" + t(6));
            m.expect("X-1", "39=4 12017=Y 15035=NONE 12016=0");
        }
    }

    @Nested
    @DisplayName("7.4 amend and cancel in flight, the cancel rejected")
    class AmendAndCancel {

        @Test
        void pendingAmendSurvivesTheCancelsReject() {
            MachineTester m = new MachineTester();
            m.send("D 11=A-1 38=10000 44=185.5 55=AAPL 60=" + t(0));
            m.send("8 150=0 39=0 11=A-1 37=ORD 17=E-1 38=10000 44=185.5 14=0 151=10000 6=0 60=" + t(1));
            m.expect("A-1", "39=0 38=10000 44=185.5 15035=NONE 15011=NONE 15038=0 15044=0");

            m.send("G 11=A-2 41=A-1 38=12000 44=185.75 60=" + t(2));
            m.expect("A-1", "39=0 38=10000 44=185.5 15035=REPLACE 15011=A-2 15038=12000 15044=185.75");

            m.send("F 11=A-3 41=A-2 60=" + t(3));
            m.expect("A-1", "39=0 38=10000 44=185.5 15035=CANCEL 15011=A-3 15038=12000 15044=185.75 "
                    + "12015=REPLACE:R:12000:185.75:A-2;CANCEL:R:::A-3");

            m.send("9 11=A-3 41=A-2 434=1 39=0 60=" + t(4));
            m.expect("A-1", "39=0 38=10000 44=185.5 15035=REPLACE 15011=A-2 15038=12000 15044=185.75");

            m.send("8 150=5 39=0 11=A-2 41=A-1 37=ORD 17=E-2 38=12000 44=185.75 14=0 151=12000 6=0 60=" + t(5));
            m.expect("A-1", "39=0 38=12000 44=185.75 15035=NONE 15011=NONE 15038=0 15044=0 12014=A-2");
        }
    }

    @Nested
    @DisplayName("7.5 an amend rejected (the NVDA scenario)")
    class AmendRejected {

        @Test
        void ackedTermsWereNeverTouched() {
            MachineTester m = new MachineTester();
            m.send("D 11=N-1 38=4000 44=121.8 60=" + t(0));
            m.send("8 150=0 39=0 11=N-1 37=ORD 17=E-1 38=4000 44=121.8 14=0 151=4000 6=0 60=" + t(1));
            m.send("8 150=1 39=1 11=N-1 37=ORD 17=E-2 38=4000 44=121.8 14=1000 151=3000 6=121.75 32=1000 "
                    + "31=121.75 60=" + t(2));
            m.send("G 11=N-2 41=N-1 38=6000 44=122.1 60=" + t(3));
            m.expect("N-1", "39=1 38=4000 44=121.8 15035=REPLACE 15038=6000 15044=122.1");
            m.send("9 11=N-2 41=N-1 434=2 39=1 60=" + t(4));
            m.expect("N-1", "39=1 38=4000 44=121.8 15035=NONE 15038=0 15044=0");
        }
    }

    @Nested
    @DisplayName("7.6 duplicate, stale and late reports")
    class DuplicateStaleLate {

        @Test
        void eachGuardInTurn() {
            MachineTester m = new MachineTester(MachineOptions.defaults().withExecIdHistory(1));
            m.send("D 11=X-1 38=1000 44=50 60=" + t(0));
            m.send("8 150=0 39=0 11=X-1 37=ORD 17=E-1 14=0 151=1000 6=0 60=" + t(1));
            m.send("8 150=1 39=1 11=X-1 37=ORD 17=E-3 14=800 151=200 6=50 32=800 31=50 60=" + t(3));
            m.expect("X-1", "39=1 14=800 12023=3 12025=0");

            assertThat(m.send("8 150=1 39=1 11=X-1 37=ORD 17=E-3 14=800 151=200 43=Y 60=" + t(3)).reason())
                    .isEqualTo(IgnoreReason.DUPLICATE);
            assertThat(m.send("8 150=1 39=1 11=X-1 37=ORD 17=E-2 14=500 151=500 60=" + t(2)).reason())
                    .isEqualTo(IgnoreReason.STALE);
            // Nothing was published for either: 12025 rides on the next delta.
            assertThat(m.wire()).isNull();
            m.expect("X-1", "39=1 14=800 12023=3 12025=0");

            m.send("8 150=6 39=6 11=X-1 37=ORD 17=E-4 14=800 151=200 60=" + t(4));
            m.expect("X-1", "39=6 14=800 12023=4 12025=2");

            // An old ack outside the ring (depth 1 here): CumQty catches it.
            assertThat(m.send("8 150=0 39=0 11=X-1 37=ORD 17=E-1 14=0 151=1000 60=" + t(1)).reason())
                    .isEqualTo(IgnoreReason.STALE);
            // An old status snapshot with no 17 and equal CumQty: its time catches it.
            assertThat(m.send("8 20=3 150=1 39=1 11=X-1 37=ORD 14=800 151=200 60=" + t(2)).reason())
                    .isEqualTo(IgnoreReason.LATE);
            assertThat(m.state("X-1").ignoredCount()).isEqualTo(4);
            m.expect("X-1", "39=6 14=800");

            // A bust of E-3: reopening reports are exempt from every guard but their own.
            m.send("8 20=1 19=E-3 17=E-5 150=1 39=1 11=X-1 37=ORD 14=500 151=500 6=50 60=" + t(5));
            m.expect("X-1", "39=1 14=500 12023=5 12025=4");
        }
    }

    @Nested
    @DisplayName("7.7 a report before its request, then the late D")
    class MidstreamThenLateD {

        @Test
        void theDJoinsThroughTheSameId() {
            MachineTester m = new MachineTester();
            Outcome created = m.send("8 150=0 39=0 11=M-1 37=ORD-M 17=E-1 38=500 44=10 1=ACC 55=SYM 54=1 "
                    + "14=0 151=500 6=0 60=" + t(1));
            assertThat(created.kind()).isEqualTo(Outcome.Kind.CREATED);
            assertThat(created.publish()).isEqualTo(Outcome.Publish.DELTA);
            m.expect("M-1", "13011=M-1 12019=C 12018=MIDSTREAM 39=0 38=500 44=10 1=ACC 55=SYM 54=1 21=- 40=- "
                    + "59=- 100=- 12016=0 12014=M-1");

            Outcome lateD = m.send("D 11=M-1 38=500 44=10 21=1 40=2 59=0 100=XNAS 1=ACC 55=SYM 54=1 60=" + t(0));
            assertThat(lateD.kind()).isEqualTo(Outcome.Kind.CHANGED);
            assertThat(lateD.publish()).isEqualTo(Outcome.Publish.DELTA);
            m.expect("M-1", "12018=D_LATE 39=0 38=500 21=1 40=2 59=0 100=XNAS 12016=0");
            // The D's 38/44 are not applied: a report has already stated them.
            m.expectWire(-38, -44, 21, 40, 59, 100);
            assertThat(m.count(Counter.LATE_DS)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("7.8 rejected before ever working")
    class RejectedBeforeWorking {

        @Test
        void anOrderReject() {
            MachineTester m = new MachineTester();
            m.send("D 11=R-1 38=200 44=10 60=" + t(0));
            m.expect("R-1", "39=A 151=200 12017=N 12016=1");
            m.send("8 150=8 39=8 11=R-1 37=NONE 17=E-1 103=1 58=unknown 14=0 151=0 60=" + t(1));
            m.expect("R-1", "39=8 151=0 12017=Y 12016=0 37=- 103=- 58=-");
        }
    }

    @Nested
    @DisplayName("7.9 a bust after a full fill reopens the order")
    class BustReopens {

        @Test
        void backToPartiallyFilled() {
            MachineTester m = new MachineTester();
            m.send("D 11=B-1 38=3000 44=50 60=" + t(0));
            m.send("8 150=0 39=0 11=B-1 37=ORD 17=E-1 14=0 151=3000 6=0 60=" + t(1));
            m.send("8 150=1 39=1 11=B-1 37=ORD 17=E-2 14=1200 151=1800 6=50 32=1200 31=50 60=" + t(2));
            m.send("8 150=2 39=2 11=B-1 37=ORD 17=E-3 14=3000 151=0 6=50 32=1800 31=50 60=" + t(3));
            m.expect("B-1", "39=2 14=3000 151=0 12017=Y");
            m.send("8 20=1 19=E-2 17=E-4 150=1 39=1 11=B-1 37=ORD 14=1800 151=1200 6=50 60=" + t(4));
            m.expect("B-1", "39=1 14=1800 151=1200 12017=N 12031=E-2");
        }
    }

    @Nested
    @DisplayName("7.10 a report on a child chain without tag 9000")
    class ChildWithout9000 {

        @Test
        void scopeComesFromTheCache() {
            MachineTester m = new MachineTester();
            m.send("D 11=C-1 38=100 44=10 9000=PARENT-1 60=" + t(0));
            m.send("8 150=0 39=0 11=C-1 37=ORD-C 17=E-1 14=0 151=100 6=0 60=" + t(1));
            m.expect("C-1", "9000=PARENT-1 39=0");
            assertThat(m.state("C-1").parentOrderId()).isEqualTo("PARENT-1");
        }
    }

    @Nested
    @DisplayName("7.11 a cancel reject with 37=NONE")
    class RejectWithNoOrderId {

        @Test
        void resolvesThroughTheRequestsOwnId() {
            MachineTester m = new MachineTester();
            m.send("D 11=U-1 38=100 44=10 60=" + t(0));
            m.send("F 11=U-2 41=U-1 60=" + t(1));
            m.send("9 11=U-2 41=U-1 37=NONE 434=1 60=" + t(2));
            m.expect("U-1", "39=A 12016=1 15035=NEW 15011=U-1 12015=NEW:R:::U-1");
        }
    }

    @Nested
    @DisplayName("7.12 cancel confirmed, then a late execution")
    class LateExecution {

        @Test
        void economicsLandStatusStays() {
            MachineTester m = partiallyFilledX();
            m.send("F 11=X-2 41=X-1 60=" + t(3));
            m.send("8 150=4 39=4 11=X-2 41=X-1 37=ORD 17=E-3 14=300 151=0 6=49.9 60=" + t(9));
            m.expect("X-1", "39=4 14=300 151=0 6=49.9 12017=Y 12026=0 12029=" + t(9));

            m.send("8 150=1 39=1 11=X-2 41=X-1 37=ORD 17=E-9 14=500 151=500 6=49.94 32=200 31=50 60=" + t(10));
            m.expect("X-1", "39=4 14=500 151=0 6=49.94 32=200 12017=Y 12026=1");

            assertThat(m.send("8 150=1 39=1 11=X-2 37=ORD 17=E-9 14=500 151=500 6=49.94 32=200 31=50 60="
                    + t(10)).reason()).isEqualTo(IgnoreReason.DUPLICATE);
            assertThat(m.send("8 150=1 39=1 11=X-1 37=ORD 17=E-8 14=300 151=700 6=49.9 32=300 31=49.9 60="
                    + t(8)).reason()).isEqualTo(IgnoreReason.STALE);
            m.expect("X-1", "39=4 14=500 151=0 6=49.94 32=200 12026=1");
        }

        @Test
        void aCountedFillArrivingLateAdoptsOnlyItsDetail() {
            // Stamped no earlier than the cancel: §4's detail-only row.
            MachineTester m = partiallyFilledX();
            m.send("8 150=4 39=4 11=X-1 37=ORD 17=E-3 14=500 151=0 6=49.94 60=" + t(9));
            m.send("8 150=1 39=1 11=X-1 37=ORD 17=E-8 14=500 151=500 6=49.94 32=200 31=50 60=" + t(9));
            m.expect("X-1", "39=4 14=500 151=0 32=200 31=50 17=E-8 12028=E-8 12026=0");
        }

        @Test
        void aCountedFillWithAnOlderTimeIsCaughtByTheLateGuardFirst() {
            // §5 runs before §4: equal CumQty and an older 60 is IGNORED(late)
            // before the after-terminal table sees it. Economics are equal
            // either way; only 17/32/31/30/12028 stay the previous fill's.
            MachineTester m = partiallyFilledX();
            m.send("8 150=4 39=4 11=X-1 37=ORD 17=E-3 14=500 151=0 6=49.94 60=" + t(9));
            assertThat(m.send("8 150=1 39=1 11=X-1 37=ORD 17=E-8 14=500 151=500 6=49.94 32=200 31=50 60="
                    + t(8)).reason()).isEqualTo(IgnoreReason.LATE);
            m.expect("X-1", "39=4 14=500 151=0 32=300 12028=E-2");
        }
    }

    @Nested
    @DisplayName("7.13 bust and correct after the order is closed out")
    class BustAndCorrectAfterClose {

        @Test
        void restatedOnTheSameRecord() {
            MachineTester m = new MachineTester();
            m.send("D 11=K-1 38=1000 44=50 60=" + t(0));
            m.send("8 150=0 39=0 11=K-1 37=ORD 17=E-0 14=0 151=1000 6=0 60=" + t(1));
            m.send("8 150=1 39=1 11=K-1 37=ORD 17=E-1 14=500 151=500 6=49.95 32=500 31=49.95 60=" + t(2));
            m.send("8 150=1 39=1 11=K-1 37=ORD 17=E-2 14=800 151=200 6=50.1 32=300 31=50.2 60=" + t(3));
            m.send("8 150=4 39=4 11=K-1 37=ORD 17=E-3 14=800 151=0 6=50.1 60=" + t(4));
            m.expect("K-1", "39=4 14=800 151=0 6=50.1 32=300 12017=Y 12028=E-2");

            // A bust of the LAST fill: 32=0 31=0, and 12028 keeps the busted id (decision 6).
            m.send("8 20=1 19=E-2 17=E-4 150=4 39=4 11=K-1 37=ORD 14=500 151=0 6=50.0 60=" + t(5));
            m.expect("K-1", "39=4 14=500 151=0 6=50.0 32=0 31=0 12017=Y 12028=E-2 12031=E-2");

            // The surviving fill repriced.
            m.send("8 20=2 19=E-1 17=E-5 150=4 39=4 11=K-1 37=ORD 14=500 151=0 6=49.95 32=500 31=49.95 60="
                    + t(6));
            m.expect("K-1", "39=4 14=500 6=49.95 32=500 12031=E-2,E-1,E-5 12028=E-5");
        }
    }

    @Nested
    @DisplayName("7.14 a fill while a cancel is pending, under the cancel's id")
    class FillUnderPendingCancel {

        @Test
        void aFillIsNotAnAnswer() {
            MachineTester m = new MachineTester();
            m.send("D 11=X-1 38=1000 44=50 60=" + t(0));
            m.send("8 150=0 39=0 11=X-1 37=ORD 17=E-1 14=0 151=1000 6=0 60=" + t(1));
            m.send("F 11=X-2 41=X-1 60=" + t(2));
            m.expect("X-1", "39=0 15035=CANCEL 15011=X-2");
            m.send("8 150=6 39=6 11=X-2 41=X-1 37=ORD 17=E-2 14=0 151=1000 6=0 60=" + t(3));
            m.expect("X-1", "39=6 15035=CANCEL 15011=X-2 12016=1");
            m.send("8 150=1 39=6 11=X-2 41=X-1 37=ORD 17=E-3 14=300 151=700 6=50 32=300 31=50 60=" + t(4));
            m.expect("X-1", "39=6 14=300 15035=CANCEL 15011=X-2 12016=1");
            m.send("9 11=X-2 41=X-1 37=ORD 434=1 39=1 60=" + t(5));
            m.expect("X-1", "39=1 14=300 15035=NONE 15011=NONE 12016=0");
        }
    }

    @Nested
    @DisplayName("7.15 an amend rejected by an execution report")
    class AmendRejectedByReport {

        @Test
        void theRejectedTermsAreNotAdopted() {
            MachineTester m = new MachineTester();
            m.send("D 11=A-1 38=10000 44=185.5 60=" + t(0));
            m.send("8 150=0 39=0 11=A-1 37=ORD 17=E-1 14=0 151=10000 6=0 38=10000 44=185.5 60=" + t(1));
            m.send("8 150=1 39=1 11=A-1 37=ORD 17=E-2 14=2000 151=8000 6=185.5 38=10000 44=185.5 32=2000 31=185.5 "
                    + "60=" + t(2));
            m.send("G 11=A-2 41=A-1 38=12000 44=185.75 60=" + t(3));
            m.expect("A-1", "39=1 38=10000 44=185.5 12014=A-1 15035=REPLACE");
            m.send("8 150=8 39=8 11=A-2 41=A-1 37=ORD 17=E-3 38=12000 44=185.75 14=2000 151=8000 6=185.5 103=0 "
                    + "60=" + t(4));
            m.expect("A-1", "39=1 38=10000 44=185.5 12014=A-1 12017=N 12016=0 15035=NONE 151=8000");
            assertThat(m.count(Counter.REQUEST_REJECTS_AS_REPORTS)).isEqualTo(1);
        }

        @Test
        void theMildFormEndsTheSame() {
            MachineTester m = new MachineTester();
            m.send("D 11=A-1 38=10000 44=185.5 60=" + t(0));
            m.send("8 150=1 39=1 11=A-1 37=ORD 17=E-2 14=2000 151=8000 6=185.5 38=10000 44=185.5 32=2000 "
                    + "31=185.5 60=" + t(2));
            m.send("G 11=A-2 41=A-1 38=12000 44=185.75 60=" + t(3));
            m.send("8 150=8 39=1 11=A-2 41=A-1 37=ORD 17=E-3 38=12000 44=185.75 14=2000 151=8000 6=185.5 60="
                    + t(4));
            m.expect("A-1", "39=1 38=10000 44=185.5 12014=A-1 12017=N 12016=0");
        }
    }

    @Nested
    @DisplayName("7.16 a replace reported as cancel-then-new")
    class CancelThenNew {

        @Test
        void aVenueAssertedReopen() {
            MachineTester m = new MachineTester();
            m.send("D 11=A-1 38=10000 44=50 60=" + t(0));
            m.send("8 150=0 39=0 11=A-1 37=ORD 17=E-1 14=0 151=10000 6=0 60=" + t(1));
            m.send("8 150=1 39=1 11=A-1 37=ORD 17=E-2 14=2000 151=8000 6=50 32=2000 31=50 60=" + t(2));
            m.send("G 11=A-2 41=A-1 38=12000 44=50 60=" + t(3));
            m.send("8 150=4 39=4 11=A-1 37=ORD 17=E-3 14=2000 151=0 6=50 60=" + t(5));
            m.expect("A-1", "39=4 38=10000 151=0 12017=Y 12014=A-1 15035=REPLACE 12016=1");

            m.send("8 150=0 39=0 11=A-2 41=A-1 37=ORD 17=E-9 14=2000 151=10000 38=12000 44=50 6=50 60=" + t(6));
            m.expect("A-1", "39=0 38=12000 151=10000 12017=N 12014=A-2 12016=0 15035=NONE");
            assertThat(m.count(Counter.REOPENED_BY_VENUE)).isEqualTo(1);

            m.send("8 150=1 39=1 11=A-2 41=A-1 37=ORD 17=E-10 14=3000 151=9000 6=50 32=1000 31=50 60=" + t(7));
            m.expect("A-1", "39=1 38=12000 151=9000 12017=N 12014=A-2");
        }

        @Test
        void answeringTheReplaceNeedsNoTimeAtAll() {
            // Whole seconds, T5 = T6: still reopened, because it answers the REPLACE (S4).
            MachineTester m = new MachineTester();
            m.send("D 11=A-1 38=10000 44=50 60=20261031-10:00:00");
            m.send("8 150=0 39=0 11=A-1 37=ORD 17=E-1 14=0 151=10000 6=0 60=20261031-10:00:01");
            m.send("G 11=A-2 41=A-1 38=12000 44=50 60=20261031-10:00:02");
            m.send("8 150=4 39=4 11=A-1 37=ORD 17=E-3 14=0 151=0 6=0 60=20261031-10:00:05");
            m.send("8 150=0 39=0 11=A-2 41=A-1 37=ORD 17=E-4 14=0 151=12000 38=12000 6=0 60=20261031-10:00:05");
            m.expect("A-1", "39=0 38=12000 12017=N 12014=A-2 12016=0");
        }
    }

    @Nested
    @DisplayName("7.17 a replace confirm that echoes the original id")
    class ConfirmEchoesOriginalId {

        @Test
        void exactlyOnePendingReplaceIsTheOneConfirmed() {
            MachineTester m = new MachineTester();
            m.send("D 11=A-1 38=10000 44=50 60=" + t(0));
            m.send("8 150=0 39=0 11=A-1 37=ORD 17=E-1 14=0 151=10000 6=0 60=" + t(1));
            m.send("G 11=A-2 41=A-1 38=12000 60=" + t(2));
            m.expect("A-1", "38=10000 12014=A-1 15035=REPLACE");
            m.send("8 150=5 39=0 11=A-1 37=ORD 17=E-2 38=12000 14=0 151=12000 6=0 60=" + t(3));
            m.expect("A-1", "38=12000 12014=A-2 12016=0");
            assertThat(m.count(Counter.INFERRED_REPLACE_ANSWERS)).isEqualTo(1);
        }

        @Test
        void twoPendingRemovesNothingButAdoptsTheTerms() {
            MachineTester m = new MachineTester();
            m.send("D 11=A-1 38=10000 44=50 60=" + t(0));
            m.send("8 150=0 39=0 11=A-1 37=ORD 17=E-1 14=0 151=10000 6=0 60=" + t(1));
            m.send("G 11=A-2 41=A-1 38=12000 60=" + t(2));
            m.send("G 11=A-3 41=A-2 38=13000 60=" + t(3));
            m.send("8 150=5 39=0 11=A-1 37=ORD 17=E-2 38=12000 14=0 151=12000 6=0 60=" + t(4));
            m.expect("A-1", "38=12000 12014=A-1 12016=2");
            assertThat(m.count(Counter.UNMATCHED_CONFIRMS)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("7.18 a pending acknowledgement that overtakes its request")
    class AckOvertakesRequest {

        @Test
        void anInferredEntryTheRequestFillsIn() {
            MachineTester m = new MachineTester();
            m.send("D 11=X-1 38=1000 44=50 60=" + t(0));
            m.send("8 150=0 39=0 11=X-1 37=ORD 17=E-1 14=0 151=1000 6=0 60=" + t(1));
            m.send("8 150=6 39=6 11=X-2 41=X-1 37=ORD 17=E-2 14=0 151=1000 6=0 60=" + t(3));
            m.expect("X-1", "39=6 12015=CANCEL:I:::X-2 12016=1 15035=CANCEL");
            assertThat(m.count(Counter.INFERRED_REQUESTS)).isEqualTo(1);

            Outcome request = m.send("F 11=X-2 41=X-1 60=" + t(2));
            assertThat(request.kind()).isEqualTo(Outcome.Kind.CHANGED);
            m.expect("X-1", "39=6 12015=CANCEL:R:::X-2 12016=1");

            m.send("8 150=4 39=4 11=X-2 41=X-1 37=ORD 17=E-3 14=0 151=0 6=0 60=" + t(4));
            m.expect("X-1", "39=4 12016=0");
        }
    }

    @Nested
    @DisplayName("7.19 a cancel reject that carries a terminal status")
    class RejectCarriesTerminal {

        @Test
        void leavesForcedToZero() {
            MachineTester m = partiallyFilledX();
            m.send("F 11=X-2 41=X-1 60=" + t(3));
            m.send("9 11=X-2 41=X-1 37=ORD 434=1 39=4 102=0 60=" + t(4));
            m.expect("X-1", "39=4 151=0 12017=Y 12016=0");
            assertThat(m.count(Counter.TERMINAL_FROM_REJECT)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("7.20 a fill as the first report (IOC filled at once)")
    class IocFilledAtOnce {

        @Test
        void anyReportAnswersTheNew() {
            MachineTester m = new MachineTester();
            m.send("D 11=I-1 38=500 44=10 59=3 60=" + t(0));
            m.expect("I-1", "39=A 12014=I-1 12016=1");
            m.send("8 150=2 39=2 11=I-1 37=ORD 17=E-1 14=500 151=0 6=10 32=500 31=10 60=" + t(1));
            m.expect("I-1", "39=2 14=500 12014=I-1 12016=0 15035=NONE");
        }

        @Test
        void anIocThatMisses() {
            MachineTester m = new MachineTester();
            m.send("D 11=I-1 38=500 44=10 59=3 60=" + t(0));
            m.send("8 150=4 39=4 11=I-1 37=ORD 17=E-1 14=0 151=0 6=0 60=" + t(1));
            m.expect("I-1", "39=4 151=0 12017=Y 12016=0");
        }
    }

    @Nested
    @DisplayName("7.21 an OrderID that equals another order's ClOrdID")
    class OrderIdEqualsAnotherClOrdId {

        @Test
        void typedMapsKeepTheOrdersApart() {
            MachineTester m = new MachineTester();
            m.send("D 11=123456 38=100 44=10 60=" + t(0));
            Outcome b = m.send("8 150=0 39=0 11=B-1 37=123456 17=E-B1 14=0 151=500 6=0 60=" + t(1));
            assertThat(b.kind()).isEqualTo(Outcome.Kind.CREATED);
            assertThat(b.key()).isEqualTo("B-1");
            m.expect("B-1", "12019=C 151=500 37=123456");
            m.expect("123456", "39=A 151=100 12023=1");
            assertThat(m.count(Counter.KEY_COLLISIONS)).isZero();
        }

        @Test
        void withoutAClOrdIdTheCollidingCandidateIsSkipped() {
            MachineTester m = new MachineTester();
            m.send("D 11=123456 38=100 44=10 60=" + t(0));
            Outcome b = m.send("8 150=0 39=0 37=123456 17=E-B1 14=0 151=500 6=0 60=" + t(1));
            assertThat(b.reason()).isEqualTo(IgnoreReason.KEY_CONFLICT);
            assertThat(m.count(Counter.KEY_COLLISIONS)).isEqualTo(1);
            m.expect("123456", "39=A 151=100");
        }

        @Test
        void aVenueWhoseOrderIdIsTheClOrdIdIsNotACollision() {
            MachineTester m = new MachineTester();
            m.send("D 11=X-1 38=100 44=10 60=" + t(0));
            m.send("8 150=0 39=0 11=X-1 37=X-1 17=E-1 14=0 151=100 6=0 60=" + t(1));
            m.expect("X-1", "39=0 37=X-1");
            assertThat(m.machine.index().keyForOrderId("X-1")).isEqualTo("X-1");
            assertThat(m.count(Counter.KEY_COLLISIONS)).isZero();
        }
    }

    @Nested
    @DisplayName("7.22 a 150=8 for a request the tap never saw")
    class RejectForUnseenRequest {

        @Test
        void aRequestRejectWhateverItNames() {
            MachineTester m = new MachineTester();
            m.send("D 11=A-1 38=10000 44=185.5 60=" + t(0));
            m.send("8 150=1 39=1 11=A-1 37=ORD 17=E-1 14=2000 151=8000 6=185.5 38=10000 44=185.5 32=2000 "
                    + "31=185.5 60=" + t(1));
            m.send("8 150=8 39=8 11=A-2 41=A-1 37=ORD 17=E-2 38=12000 44=185.75 14=2000 151=8000 6=185.5 60="
                    + t(2));
            m.expect("A-1", "39=1 38=10000 44=185.5 12017=N");
            assertThat(m.count(Counter.REQUEST_REJECTS_AS_REPORTS)).isEqualTo(1);
            assertThat(m.count(Counter.UNMATCHED_REJECTS)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("7.23 a pending acknowledgement that names the working id")
    class AckNamesWorkingId {

        @Test
        void theRequestReKeysTheInferredEntry() {
            MachineTester m = new MachineTester();
            m.send("8 150=E 39=E 11=M-1 37=ORD 17=E-1 14=0 151=10000 6=0 60=" + t(1));
            m.expect("M-1", "12018=MIDSTREAM 12015=REPLACE:I:::M-1 12016=1 15038=0");
            m.send("G 11=M-2 41=M-1 38=12000 44=50 60=" + t(2));
            m.expect("M-1", "12015=REPLACE:R:12000:50:M-2 12016=1 15038=12000 15044=50");
            m.send("8 150=5 39=0 11=M-2 41=M-1 37=ORD 17=E-2 38=12000 44=50 14=0 151=12000 6=0 60=" + t(3));
            m.expect("M-1", "12016=0 12014=M-2 38=12000");
        }
    }
}
