package com.demo.amps.fix42.state.machine;

import static com.demo.amps.fix42.state.machine.MachineTester.t;
import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.hooks.RejectStatusAtRequestTimeHook;
import com.demo.amps.fix42.state.spi.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * docs/11 F2 and the hook that closes it for the venue that needs it: a
 * {@code 35=9} carrying the request-time {@code 39=6} (or {@code E}) after the
 * venue acknowledged the request as pending. The machine alone leaves the
 * record reading pending with nothing pending; with the hook the record
 * reads the status the cumulative state implies, and every other shape of
 * reject reaches the machine untouched.
 */
class RejectStatusAtRequestTimeHookTest {

    private static final RejectStatusAtRequestTimeHook HOOK = new RejectStatusAtRequestTimeHook();

    /** D, ack, and a fill of {@code filled} (none when 0), for order X-1. */
    private static MachineTester order(MachineTester m, int filled) {
        m.send("D 11=X-1 38=1000 44=50 55=SYM 54=1 40=2 59=0 60=" + t(0));
        m.send("8 150=0 39=0 11=X-1 37=ORD 17=E-1 38=1000 44=50 14=0 151=1000 6=0 60=" + t(1));
        if (filled > 0) {
            m.send("8 150=1 39=1 11=X-1 37=ORD 17=E-2 38=1000 44=50 14=" + filled + " 151=" + (1000 - filled)
                    + " 6=50 32=" + filled + " 31=50 60=" + t(2));
        }
        return m;
    }

    /** A cancel X-2, acknowledged as pending, then rejected with the request-time 39=6. */
    private static void cancelAckedThenRejected(MachineTester m, int filled) {
        m.send("F 11=X-2 41=X-1 60=" + t(3));
        m.send("8 150=6 39=6 11=X-2 41=X-1 37=ORD 17=E-3 14=" + filled + " 151=" + (1000 - filled)
                + " 6=50 60=" + t(4));
        m.expect("X-1", "39=6 15035=CANCEL 9016=1");
        m.send("9 11=X-2 41=X-1 37=ORD 434=1 39=6 102=0 58=too-late 60=" + t(5));
    }

    @Test
    @DisplayName("F2 without the hook: the record reads pending cancel with nothing pending")
    void withoutTheHookTheRecordContradictsItself() {
        MachineTester m = order(new MachineTester(), 300);
        cancelAckedThenRejected(m, 300);
        m.expect("X-1", "39=6 14=300 151=700 15035=NONE 15011=NONE 9015=NONE 9016=0 9017=N");
    }

    @Test
    @DisplayName("with the hook: partly filled, the rejected cancel leaves 39=1")
    void partlyFilledReadsPartiallyFilled() {
        MachineTester m = order(new MachineTester().withHook(HOOK), 300);
        cancelAckedThenRejected(m, 300);
        m.expect("X-1", "39=1 14=300 151=700 15035=NONE 15011=NONE 9015=NONE 9016=0 9017=N 9022=9");
        m.expectWire(39);
    }

    @Test
    @DisplayName("with the hook: nothing filled, the rejected cancel leaves 39=0")
    void nothingFilledReadsNew() {
        MachineTester m = order(new MachineTester().withHook(HOOK), 0);
        cancelAckedThenRejected(m, 0);
        m.expect("X-1", "39=0 14=0 151=1000 15035=NONE 9016=0 9017=N");
    }

    @Test
    @DisplayName("with the hook: a pending replace rejected with 39=E reads 1, and the acked terms stand")
    void rejectedReplace() {
        MachineTester m = order(new MachineTester().withHook(HOOK), 300);
        m.send("G 11=X-2 41=X-1 38=1200 44=50.5 60=" + t(3));
        m.send("8 150=E 39=E 11=X-2 41=X-1 37=ORD 17=E-3 14=300 151=700 6=50 60=" + t(4));
        m.expect("X-1", "39=E 15035=REPLACE 9016=1");
        m.send("9 11=X-2 41=X-1 37=ORD 434=2 39=E 60=" + t(5));
        m.expect("X-1", "39=1 38=1000 44=50 15035=NONE 15038=0 15044=0 9016=0");
    }

    @Test
    @DisplayName("with the hook: 39=6 is left alone while a second cancel is still in flight")
    void secondCancelStillPending() {
        MachineTester m = order(new MachineTester().withHook(HOOK), 300);
        m.send("F 11=X-2 41=X-1 60=" + t(3));
        m.send("F 11=X-3 41=X-1 60=" + t(4));
        m.send("8 150=6 39=6 11=X-2 41=X-1 37=ORD 17=E-3 14=300 151=700 6=50 60=" + t(5));
        m.send("9 11=X-2 41=X-1 37=ORD 434=1 39=6 60=" + t(6));
        m.expect("X-1", "39=6 15035=CANCEL 15011=X-3 9016=1");
        // The last cancel's reject is the one that retires the 6.
        m.send("9 11=X-3 41=X-1 37=ORD 434=1 39=6 60=" + t(7));
        m.expect("X-1", "39=1 15035=NONE 9016=0");
    }

    @Test
    @DisplayName("with the hook: a cancel reject while the record reads E keeps E (the replace is still pending)")
    void recordReadsAnotherPendingStatus() {
        MachineTester m = order(new MachineTester().withHook(HOOK), 300);
        m.send("G 11=X-2 41=X-1 38=1200 44=50.5 60=" + t(3));
        m.send("8 150=E 39=E 11=X-2 41=X-1 37=ORD 17=E-3 14=300 151=700 6=50 60=" + t(4));
        m.send("F 11=X-3 41=X-2 60=" + t(5));
        m.send("9 11=X-3 41=X-2 37=ORD 434=1 39=6 60=" + t(6));
        m.expect("X-1", "39=E 15035=REPLACE 15011=X-2 9016=1");
    }

    @Test
    @DisplayName("the hook hands the machine the original for every other shape of reject")
    void otherShapesAreUntouched() {
        MachineTester m = order(new MachineTester(), 300);
        m.send("F 11=X-2 41=X-1 60=" + t(3));
        m.send("8 150=6 39=6 11=X-2 41=X-1 37=ORD 17=E-3 14=300 151=700 6=50 60=" + t(4));
        OrderState pending = m.state("X-1");
        // A venue whose reject carries the status after the reject.
        assertThat(decide("9 11=X-2 41=X-1 434=1 39=1", pending).replacement()).isNull();
        // No 39 at all.
        assertThat(decide("9 11=X-2 41=X-1 434=1", pending).replacement()).isNull();
        // Not a reject.
        assertThat(decide("8 150=6 39=6 11=X-2 41=X-1 17=E-9 14=300", pending).replacement()).isNull();
        // An order the cache does not hold.
        assertThat(decide("9 11=X-2 41=X-1 434=1 39=6", null).replacement()).isNull();
        // The F2 shape itself: rewritten, every other field kept in place.
        FixFrame rewritten = decide("9 11=X-2 41=X-1 434=1 39=6 58=too-late", pending).replacement();
        assertThat(rewritten.asString().replace('\u0001', '|')).isEqualTo("35=9|11=X-2|41=X-1|434=1|39=1|58=too-late|");

        // Terminal: the machine's terminal guard owns it, whatever the 39.
        m.send("8 150=4 39=4 11=X-2 41=X-1 37=ORD 17=E-4 14=300 151=0 6=50 60=" + t(5));
        assertThat(decide("9 11=X-2 41=X-1 434=1 39=6", m.state("X-1")).replacement()).isNull();
    }

    private static Decision decide(String spec, OrderState state) {
        FixWriter writer = new FixWriter(256);
        MachineTester.parse(spec).writeTo(writer);
        return HOOK.beforeApply(new FixFrame().wrap(writer), state);
    }
}
