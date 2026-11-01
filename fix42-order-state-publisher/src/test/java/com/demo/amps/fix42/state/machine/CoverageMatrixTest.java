package com.demo.amps.fix42.state.machine;

import static com.demo.amps.fix42.state.machine.MachineTester.t;
import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.config.CancelRejectStatusPolicy;
import com.demo.amps.fix42.state.config.FillUnderPendingIdPolicy;
import com.demo.amps.fix42.state.config.LateFillPolicy;
import com.demo.amps.fix42.state.config.NonFillAfterTerminalPolicy;
import com.demo.amps.fix42.state.spi.IgnoreReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The rows of the docs/06 coverage matrix that the walkthroughs do not
 * already walk, one test each, named by row id. Each <b>policy</b> row runs
 * once per policy value.
 */
class CoverageMatrixTest {

    private static MachineTester acked(String id, String qty, MachineOptions options) {
        MachineTester m = new MachineTester(options);
        m.send("D 11=" + id + " 38=" + qty + " 44=50 59=0 60=" + t(0));
        m.send("8 150=0 39=0 11=" + id + " 37=ORD-" + id + " 17=" + id + "-E1 38=" + qty + " 44=50 14=0 151="
                + qty + " 6=0 60=" + t(1));
        return m;
    }

    private static MachineTester acked(String id, String qty) {
        return acked(id, qty, MachineOptions.defaults());
    }

    private static void partial(MachineTester m, String id, String clOrdId, String cum, String leaves, String exec,
                                int second) {
        m.send("8 150=1 39=1 11=" + clOrdId + " 37=ORD-" + id + " 17=" + exec + " 14=" + cum + " 151=" + leaves
                + " 6=50 32=100 31=50 60=" + t(second));
    }

    // ---- normal lifecycles -------------------------------------------------------

    @Test
    @DisplayName("N1 D, ack, partial, full fill")
    void n1() {
        MachineTester m = acked("N1", "1000");
        partial(m, "N1", "N1", "400", "600", "N1-E2", 2);
        m.send("8 150=2 39=2 11=N1 37=ORD-N1 17=N1-E3 14=1000 151=0 6=50.1 32=600 31=50.2 60=" + t(3));
        m.expect("N1", "39=2 14=1000 151=0 6=50.1 9017=Y 9016=0");
    }

    @Test
    @DisplayName("N2 D, ack, partial, F, 150=6, 150=4: 39 reads 6 between the ack and the confirm")
    void n2() {
        MachineTester m = acked("N2", "1000");
        partial(m, "N2", "N2", "300", "700", "N2-E2", 2);
        m.send("F 11=N2-2 41=N2 60=" + t(3));
        m.send("8 150=6 39=6 11=N2-2 41=N2 37=ORD-N2 17=N2-E3 14=300 151=700 6=50 60=" + t(4));
        m.expect("N2", "39=6 9016=1");
        m.send("8 150=4 39=4 11=N2-2 41=N2 37=ORD-N2 17=N2-E4 14=300 151=0 6=50 60=" + t(5));
        m.expect("N2", "39=4 151=0 9017=Y 9016=0");
    }

    @Test
    @DisplayName("N3 G, 150=E, 150=5, fills: 38/44 move only on the 150=5; 9014 rotates")
    void n3() {
        MachineTester m = acked("N3", "1000");
        m.send("G 11=N3-2 41=N3 38=1200 44=50.5 60=" + t(2));
        m.send("8 150=E 39=E 11=N3-2 41=N3 37=ORD-N3 17=N3-E2 38=1200 44=50.5 14=0 151=1000 6=0 60=" + t(3));
        // A pending ack may echo the proposed terms; they are not the acked terms.
        m.expect("N3", "39=E 38=1000 44=50 15038=1200 15044=50.5 9014=N3");
        m.send("8 150=5 39=0 11=N3-2 41=N3 37=ORD-N3 17=N3-E3 38=1200 44=50.5 14=0 151=1200 6=0 60=" + t(4));
        m.expect("N3", "39=0 38=1200 44=50.5 15038=0 15044=0 9014=N3-2 9016=0");
    }

    @ParameterizedTest(name = "N4/N5 {0}")
    @EnumSource(CancelRejectStatusPolicy.class)
    @DisplayName("N4/N5 a 35=9's 39 under each cancel-reject-status policy")
    void n4n5(CancelRejectStatusPolicy policy) {
        MachineTester m = acked("N4", "1000", MachineOptions.defaults().withCancelRejectStatus(policy));
        partial(m, "N4", "N4", "300", "700", "N4-E2", 2);
        m.send("F 11=N4-2 41=N4 60=" + t(3));
        m.send("8 150=6 39=6 11=N4-2 41=N4 37=ORD-N4 17=N4-E3 14=300 151=700 6=50 60=" + t(4));
        m.send("9 11=N4-2 41=N4 434=1 39=6 60=" + t(5));
        // 39=6 on the 9 is not adopted once no cancel is pending -- except ALWAYS.
        // And under every policy the record's own 6 from the pending ack stays (docs/11 F2).
        m.expect("N4", "39=6 9016=0");
        m.send("G 11=N4-3 41=N4 38=900 60=" + t(6));
        m.send("9 11=N4-3 41=N4 434=2 39=1 60=" + t(7));
        m.expect("N4", "39=" + (policy == CancelRejectStatusPolicy.NEVER ? "6" : "1") + " 38=1000 9016=0");
        m.send("F 11=N4-4 41=N4 60=" + t(8));
        m.send("9 11=N4-4 41=N4 434=1 39=8 60=" + t(9));
        m.expect("N4", policy == CancelRejectStatusPolicy.ALWAYS
                ? "39=8 151=0 9017=Y" : "39=" + (policy == CancelRejectStatusPolicy.NEVER ? "6" : "1") + " 9017=N");
    }

    @Test
    @DisplayName("N7 done for day; N8 expired: the terminal path, 151 forced to 0")
    void n7n8() {
        for (String execType : new String[] {"3", "C"}) {
            MachineTester m = acked("N7", "1000");
            partial(m, "N7", "N7", "400", "600", "N7-E2", 2);
            m.send("8 150=" + execType + " 39=" + execType + " 11=N7 37=ORD-N7 17=N7-E3 14=400 6=50 60=" + t(3));
            m.expect("N7", "39=" + execType + " 14=400 151=0 9017=Y");
        }
    }

    @Test
    @DisplayName("N9 a restatement (150=D) cuts OrderQty; the pending set is untouched")
    void n9() {
        MachineTester m = acked("N9", "2500");
        partial(m, "N9", "N9", "1000", "1500", "N9-E2", 2);
        m.send("F 11=N9-2 41=N9 60=" + t(3));
        m.send("8 150=D 39=1 378=5 11=N9 37=ORD-N9 17=N9-E3 38=2000 14=1000 151=1000 6=50 60=" + t(4));
        m.expect("N9", "38=2000 151=1000 39=1 9016=1 15035=CANCEL 378=-");
    }

    @Test
    @DisplayName("N10 a bust of the first of two fills: 32/31 untouched (not the last fill)")
    void n10() {
        MachineTester m = acked("N10", "1000");
        partial(m, "N10", "N10", "200", "800", "N10-E2", 2);
        m.send("8 150=1 39=1 11=N10 37=ORD-N10 17=N10-E3 14=300 151=700 6=50.03 32=100 31=50.1 60=" + t(3));
        m.send("8 20=1 19=N10-E2 17=N10-E4 150=1 39=1 11=N10 37=ORD-N10 14=100 151=900 6=50.1 60=" + t(4));
        m.expect("N10", "14=100 151=900 6=50.1 32=100 31=50.1 9028=N10-E3 9031=N10-E2");
    }

    @Test
    @DisplayName("N11 a correct: restated 14/151/6, the correcting 32/31; 9028 = the correct's 17")
    void n11() {
        MachineTester m = acked("N11", "1000");
        partial(m, "N11", "N11", "100", "900", "N11-E2", 2);
        m.send("8 20=2 19=N11-E2 17=N11-E3 150=1 39=1 11=N11 37=ORD-N11 14=100 151=900 6=49.9 32=100 31=49.9 60="
                + t(3));
        m.expect("N11", "6=49.9 32=100 31=49.9 9028=N11-E3 9031=N11-E2,N11-E3");
    }

    @Test
    @DisplayName("N12 a child with 9000 beside its parent: two records, 9000 on the child's")
    void n12() {
        MachineTester m = new MachineTester();
        m.send("D 11=P-1 38=1000 44=50 60=" + t(0));
        m.send("D 11=C-1 38=400 44=50 9000=P-1 60=" + t(1));
        m.expect("P-1", "9000=-");
        m.expect("C-1", "9000=P-1");
    }

    @Test
    @DisplayName("N13 an unsolicited cancel: no pending CANCEL to remove")
    void n13() {
        MachineTester m = acked("N13", "1000");
        m.send("8 150=4 39=4 11=N13 37=ORD-N13 17=N13-E2 14=0 151=0 6=0 60=" + t(2));
        m.expect("N13", "39=4 151=0 9017=Y 9016=0");
    }

    @Test
    @DisplayName("N16 150=A, then 150=0: the NEW stays pending until the ack")
    void n16() {
        MachineTester m = new MachineTester();
        m.send("D 11=N16 38=100 44=10 60=" + t(0));
        m.send("8 150=A 39=A 11=N16 37=ORD 17=E-1 14=0 151=100 6=0 60=" + t(1));
        m.expect("N16", "39=A 9016=1 15035=NEW");
        assertThat(m.count(Counter.INFERRED_REQUESTS)).isZero();
        m.send("8 150=0 39=0 11=N16 37=ORD 17=E-2 14=0 151=100 6=0 60=" + t(2));
        m.expect("N16", "39=0 9016=0");
    }

    @Test
    @DisplayName("N18 a 150=I status response: absolutes adopted, pending and 9014 untouched")
    void n18() {
        MachineTester m = acked("N18", "1000");
        m.send("F 11=N18-2 41=N18 60=" + t(2));
        m.send("8 150=I 39=1 11=N18 37=ORD-N18 17=N18-E2 14=300 151=700 6=50 60=" + t(3));
        m.expect("N18", "39=1 14=300 9016=1 9014=N18");
    }

    @Test
    @DisplayName("N19 an amend to market: 40 and 110 from the confirm, 44=0 written")
    void n19() {
        MachineTester m = acked("N19", "1000");
        m.send("G 11=N19-2 41=N19 38=1000 40=1 110=0 60=" + t(2));
        m.expect("N19", "40=-");
        m.send("8 150=5 39=0 11=N19-2 41=N19 37=ORD-N19 17=N19-E2 38=1000 40=1 110=0 14=0 151=1000 6=0 60="
                + t(3));
        m.expect("N19", "40=1 110=0 44=0");
    }

    // ---- requests racing their answers ---------------------------------------------

    @Test
    @DisplayName("E5 two amends in flight, each answered; with only the second confirm, the first stays")
    void e5() {
        MachineTester m = acked("E5", "1000");
        m.send("G 11=E5-2 41=E5 38=1100 44=51 60=" + t(2));
        m.send("G 11=E5-3 41=E5-2 38=1200 44=52 60=" + t(3));
        m.send("8 150=5 39=0 11=E5-3 41=E5-2 37=ORD-E5 17=E5-E2 38=1200 44=52 14=0 151=1200 6=0 60=" + t(4));
        m.expect("E5", "38=1200 44=52 9014=E5-3 9016=1 15011=E5-2 15038=1100");
        m.send("8 150=5 39=0 11=E5-2 41=E5 37=ORD-E5 17=E5-E3 38=1100 44=51 14=0 151=1100 6=0 60=" + t(5));
        m.expect("E5", "9016=0");
    }

    @Test
    @DisplayName("E6/E7 an answer before its request: the late request is an answered id")
    void e6e7() {
        MachineTester m = acked("E6", "1000");
        m.send("8 150=5 39=0 11=E6-2 41=E6 37=ORD-E6 17=E6-E2 38=1200 44=51 14=0 151=1200 6=0 60=" + t(3));
        assertThat(m.send("G 11=E6-2 41=E6 38=1200 44=51 60=" + t(2)).reason()).isEqualTo(IgnoreReason.LATE_REQUEST);
        m.expect("E6", "38=1200 9016=0 9014=E6-2");

        MachineTester c = acked("E7", "1000");
        c.send("8 150=4 39=4 11=E7-2 41=E7 37=ORD-E7 17=E7-E2 14=0 151=0 6=0 60=" + t(3));
        assertThat(c.send("F 11=E7-2 41=E7 60=" + t(2)).reason()).isEqualTo(IgnoreReason.LATE_REQUEST);
        c.expect("E7", "39=4 9016=0");
    }

    @ParameterizedTest(name = "E8 {0}")
    @EnumSource(FillUnderPendingIdPolicy.class)
    @DisplayName("E8 the 150=5 missed; a fill under the amended id at the proposed terms")
    void e8(FillUnderPendingIdPolicy policy) {
        MachineTester m = acked("E8", "1000", MachineOptions.defaults().withFillUnderPendingId(policy));
        m.send("G 11=E8-2 41=E8 38=1200 44=51 60=" + t(2));
        m.send("8 150=1 39=1 11=E8-2 41=E8 37=ORD-E8 17=E8-E2 38=1200 44=51 14=100 151=1100 6=51 32=100 31=51 "
                + "60=" + t(3));
        m.expect("E8", policy == FillUnderPendingIdPolicy.IGNORE ? "9016=1 9014=E8" : "9016=0 9014=E8-2");
    }

    @Test
    @DisplayName("E9 a 150=4 echoing the original id: every pending CANCEL removed")
    void e9() {
        MachineTester m = acked("E9", "1000");
        m.send("F 11=E9-2 41=E9 60=" + t(2));
        m.send("F 11=E9-3 41=E9 60=" + t(3));
        m.send("8 150=4 39=4 11=E9 37=ORD-E9 17=E9-E2 14=0 151=0 6=0 60=" + t(4));
        m.expect("E9", "39=4 9016=0");
    }

    @Test
    @DisplayName("E11 a 35=9 for a request never seen: counted, nothing changes, no chain created")
    void e11() {
        MachineTester m = acked("E11", "1000");
        m.send("9 11=E11-2 41=E11 434=1 39=0 60=" + t(2));
        m.expect("E11", "39=0 9016=0");
        assertThat(m.count(Counter.UNMATCHED_REJECTS)).isEqualTo(1);
        Outcome orphan = m.send("9 11=ZZ-2 41=ZZ-1 434=1 39=0 60=" + t(3));
        assertThat(orphan.reason()).isEqualTo(IgnoreReason.UNMATCHED);
        assertThat(orphan.publish()).isEqualTo(Outcome.Publish.NONE);
        assertThat(m.machine.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("E13/E14 a request on a terminal order joins the set; one never answered stays there")
    void e13e14() {
        MachineTester m = acked("E13", "1000");
        m.send("8 150=2 39=2 11=E13 37=ORD-E13 17=E13-E2 14=1000 151=0 6=50 32=1000 31=50 60=" + t(2));
        m.send("G 11=E13-2 41=E13 38=1200 60=" + t(3));
        m.expect("E13", "39=2 9017=Y 9016=1 15035=REPLACE");
        assertThat(m.machine.pendingOnTerminal()).isEqualTo(1);
        m.send("9 11=E13-2 41=E13 434=2 39=2 60=" + t(4));
        m.expect("E13", "39=2 9016=0");
        assertThat(m.machine.pendingOnTerminal()).isZero();
    }

    // ---- reports racing each other ------------------------------------------------------

    @ParameterizedTest(name = "E20 {0}")
    @EnumSource(LateFillPolicy.class)
    @DisplayName("E20 a late execution after the cancel, under each late-fill-after-terminal policy")
    void e20(LateFillPolicy policy) {
        MachineTester m = acked("E20", "1000", MachineOptions.defaults().withLateFillAfterTerminal(policy));
        partial(m, "E20", "E20", "300", "700", "E20-E2", 2);
        m.send("8 150=4 39=4 11=E20 37=ORD-E20 17=E20-E3 14=300 151=0 6=50 60=" + t(3));
        Outcome late = m.send("8 150=1 39=1 11=E20 37=ORD-E20 17=E20-E4 14=500 151=500 6=50.02 32=200 31=50.05 60="
                + t(4));
        switch (policy) {
            case APPLY_ECONOMICS -> m.expect("E20", "39=4 14=500 151=0 9017=Y 9026=1 32=200");
            case ADOPT_VENUE -> m.expect("E20", "39=1 14=500 151=500 9017=N 9026=0");
            case IGNORE -> {
                assertThat(late.reason()).isEqualTo(IgnoreReason.LATE_FILL);
                m.expect("E20", "39=4 14=300 151=0");
            }
        }
    }

    @Test
    @DisplayName("E23/E38 a pending ack after the terminal report is absorbed, even with a higher 14")
    void e23e38() {
        MachineTester m = acked("E23", "1000");
        m.send("8 150=4 39=4 11=E23 37=ORD-E23 17=E23-E2 14=300 151=0 6=50 60=" + t(3));
        assertThat(m.send("8 150=6 39=6 11=E23-2 41=E23 37=ORD-E23 17=E23-E3 14=300 151=700 60=" + t(4)).reason())
                .isEqualTo(IgnoreReason.ABSORBED);
        assertThat(m.send("8 150=6 39=6 11=E23-3 41=E23 37=ORD-E23 17=E23-E4 14=900 151=100 60=" + t(5)).reason())
                .isEqualTo(IgnoreReason.ABSORBED);
        m.expect("E23", "39=4 14=300 9016=0");
    }

    @Test
    @DisplayName("E25 a correct after the full fill: 14 unchanged, 6 restated, 39=2 stays")
    void e25() {
        MachineTester m = acked("E25", "1000");
        m.send("8 150=2 39=2 11=E25 37=ORD-E25 17=E25-E2 14=1000 151=0 6=50 32=1000 31=50 60=" + t(2));
        m.send("8 20=2 19=E25-E2 17=E25-E3 150=2 39=2 11=E25 37=ORD-E25 14=1000 151=0 6=49.9 32=1000 31=49.9 60="
                + t(3));
        m.expect("E25", "39=2 14=1000 6=49.9 9017=Y");
    }

    @Test
    @DisplayName("E28/E29 terminal, then a 150=D or another terminal report: applied")
    void e28e29() {
        MachineTester m = acked("E28", "1000");
        m.send("8 150=4 39=4 11=E28 37=ORD-E28 17=E28-E2 14=0 151=0 6=0 60=" + t(2));
        m.send("8 150=3 39=3 11=E28 37=ORD-E28 17=E28-E3 14=0 151=0 6=0 60=" + t(3));
        m.expect("E28", "39=3 9017=Y");
        m.send("8 150=D 39=4 11=E28 37=ORD-E28 17=E28-E4 38=900 14=0 151=0 6=0 60=" + t(4));
        m.expect("E28", "39=4 38=900");
    }

    @Test
    @DisplayName("E31a a redelivered bust: older than 9029, or its 19 already in 9031")
    void e31a() {
        MachineTester m = acked("E31a", "1000");
        partial(m, "E31a", "E31a", "100", "900", "B-E2", 2);
        partial(m, "E31a", "E31a", "200", "800", "B-E3", 3);
        m.send("8 20=1 19=B-E2 17=B-E4 150=1 39=1 11=E31a 37=ORD-E31a 14=100 151=900 6=50 60=" + t(4));
        // A redelivery under a NEW ExecID, at a later time: only 9031 can tell.
        assertThat(m.send("8 20=1 19=B-E2 17=B-E9 150=1 39=1 11=E31a 37=ORD-E31a 14=100 151=900 6=50 60=" + t(9))
                .reason()).isEqualTo(IgnoreReason.DUPLICATE);
        // An old bust redelivered past the ring: its time.
        assertThat(m.send("8 20=1 19=B-E1 17=B-E0 150=1 39=1 11=E31a 37=ORD-E31a 14=0 151=1000 6=0 60=" + t(1))
                .reason()).isEqualTo(IgnoreReason.LATE);
        m.expect("E31a", "14=100");
    }

    @Test
    @DisplayName("E33/E76a a 20=3 status report with 17=0 or none: applied, never deduplicated")
    void e33() {
        MachineTester m = acked("E33", "1000");
        m.send("8 20=3 150=0 39=0 11=E33 37=ORD-E33 17=0 14=0 151=1000 6=0 60=" + t(2));
        m.send("8 20=3 150=0 39=0 11=E33 37=ORD-E33 17=0 14=0 151=1000 6=0 60=" + t(3));
        m.send("8 20=3 150=0 39=0 11=E33 37=ORD-E33 14=0 151=1000 6=0 60=" + t(4));
        assertThat(m.count(Counter.IGNORED_DUPLICATE)).isZero();
        m.expect("E33", "9023=5 17=E33-E1");
    }

    @Test
    @DisplayName("E34 a report with 38 != 14 + 151: applied as sent, counted")
    void e34() {
        MachineTester m = acked("E34", "1000");
        m.send("8 150=1 39=1 11=E34 37=ORD-E34 17=E34-E2 38=1000 14=300 151=600 6=50 32=300 31=50 60=" + t(2));
        m.expect("E34", "151=600");
        assertThat(m.count(Counter.INCONSISTENT_REPORTS)).isEqualTo(1);
    }

    @ParameterizedTest(name = "E35/E36 {0}")
    @EnumSource(NonFillAfterTerminalPolicy.class)
    @DisplayName("E36 a newer re-ack after an erroneous cancel, under each non-fill-after-terminal policy")
    void e36(NonFillAfterTerminalPolicy policy) {
        MachineTester m = acked("E36", "1000", MachineOptions.defaults().withNonFillAfterTerminal(policy));
        m.send("8 150=4 39=4 11=E36 37=ORD-E36 17=E36-E2 14=0 151=0 6=0 60=" + t(3));
        m.send("8 150=0 39=0 11=E36 37=ORD-E36 17=E36-E3 14=0 151=1000 6=0 60=" + t(4));
        m.expect("E36", policy == NonFillAfterTerminalPolicy.IGNORE ? "39=4 151=0" : "39=0 151=1000 9017=N");
        // An OLDER re-ack with equal CumQty is caught by §5's late guard before
        // the after-terminal table runs, under every policy (docs/11 F1) ...
        MachineTester old = acked("E36", "1000", MachineOptions.defaults().withNonFillAfterTerminal(policy));
        old.send("8 150=4 39=4 11=E36 37=ORD-E36 17=E36-E2 14=0 151=0 6=0 60=" + t(4));
        old.send("8 150=0 39=0 11=E36 37=ORD-E36 17=E36-E3 14=0 151=1000 6=0 60=" + t(3));
        old.expect("E36", "39=4");
        // ... so ADOPT_VENUE believes it only with transact-time-guard off too.
        MachineTester unguarded = acked("E36", "1000",
                MachineOptions.defaults().withNonFillAfterTerminal(policy).withGuards(true, false));
        unguarded.send("8 150=4 39=4 11=E36 37=ORD-E36 17=E36-E2 14=0 151=0 6=0 60=" + t(4));
        unguarded.send("8 150=0 39=0 11=E36 37=ORD-E36 17=E36-E3 14=0 151=1000 6=0 60=" + t(3));
        unguarded.expect("E36", policy == NonFillAfterTerminalPolicy.ADOPT_VENUE ? "39=0" : "39=4");
    }

    @Test
    @DisplayName("E37 an IOC's overtaken ack, a same-second ack without milliseconds, an undatable ack")
    void e37() {
        MachineTester ioc = new MachineTester();
        ioc.send("D 11=IOC 38=500 44=10 59=3 60=" + t(0));
        ioc.send("8 150=4 39=4 11=IOC 37=ORD 17=E-2 14=0 151=0 6=0 60=" + t(1));
        assertThat(ioc.send("8 150=0 39=0 11=IOC 37=ORD 17=E-1 14=0 151=500 6=0 60=" + t(2)).reason())
                .isEqualTo(IgnoreReason.LATE);
        assertThat(ioc.count(Counter.LATE_IOC_ACK)).isEqualTo(1);

        MachineTester seconds = acked("S", "100");
        seconds.send("8 150=4 39=4 11=S 37=ORD-S 17=S-E2 14=0 151=0 6=0 60=20261031-10:00:05");
        seconds.send("8 150=0 39=0 11=S 37=ORD-S 17=S-E3 14=0 151=100 6=0 60=20261031-10:00:05.900");
        seconds.expect("S", "39=4");

        MachineTester undatable = acked("U", "100");
        undatable.send("8 150=4 39=4 11=U 37=ORD-U 17=U-E2 14=0 151=0 6=0 60=" + t(5));
        undatable.send("8 150=0 39=0 11=U 37=ORD-U 17=U-E3 14=0 151=100 6=0");
        undatable.expect("U", "39=4");
        assertThat(undatable.count(Counter.REOPEN_UNDECIDABLE)).isEqualTo(1);
    }

    @Test
    @DisplayName("E39 a report lacking 14/151/6: what it carries is adopted, the rest left")
    void e39() {
        MachineTester m = acked("E39", "1000");
        partial(m, "E39", "E39", "300", "700", "E39-E2", 2);
        m.send("8 150=6 39=6 11=E39-2 41=E39 37=ORD-E39 17=E39-E3 60=" + t(3));
        m.expect("E39", "39=6 14=300 151=700");
    }

    // ---- mid-stream and identity -----------------------------------------------------------

    @Test
    @DisplayName("E42 a resent D is ignored: a FULL publish would erase every delta since")
    void e42() {
        MachineTester m = acked("E42", "1000");
        Outcome resent = m.send("D 11=E42 38=1000 44=50 43=Y 60=" + t(0));
        assertThat(resent.reason()).isEqualTo(IgnoreReason.DUPLICATE_REQUEST);
        assertThat(resent.publish()).isEqualTo(Outcome.Publish.NONE);
        m.expect("E42", "39=0");
    }

    @Test
    @DisplayName("E43/E44 resolution by 37 heals a missed replace; a rotated 37 resolves by 11 and binds")
    void e43e44() {
        MachineTester m = acked("E43", "1000");
        m.send("8 150=5 39=0 11=E43-3 41=E43-2 37=ORD-E43 17=E43-E2 38=1200 14=0 151=1200 6=0 60=" + t(2));
        m.expect("E43", "38=1200 9021=E43,E43-2,E43-3");

        MachineTester r = acked("E44", "1000");
        r.send("G 11=E44-2 41=E44 38=1200 60=" + t(2));
        r.send("8 150=5 39=0 11=E44-2 41=E44 37=ORD-E44-R1 17=E44-E2 38=1200 14=0 151=1200 6=0 60=" + t(3));
        r.expect("E44", "37=ORD-E44-R1 38=1200");
        assertThat(r.machine.index().keyForOrderId("ORD-E44")).isEqualTo("E44");
        assertThat(r.machine.index().keyForOrderId("ORD-E44-R1")).isEqualTo("E44");
    }

    @Test
    @DisplayName("E58 a G whose 37 names another order resolves by 41; a D's 41 is lineage only")
    void e58() {
        MachineTester m = acked("A", "1000");
        m.send("D 11=B 38=100 44=10 60=" + t(2));
        m.send("8 150=0 39=0 11=B 37=ORD-B 17=B-E1 14=0 151=100 6=0 60=" + t(3));
        m.send("G 11=A-2 41=A 37=ORD-B 38=1200 60=" + t(4));
        m.expect("A", "9016=1 15011=A-2");
        m.expect("B", "9016=0");
        assertThat(m.count(Counter.ID_CONFLICTS)).isEqualTo(1);

        m.send("D 11=C 41=A 38=300 44=10 60=" + t(5));
        m.expect("C", "9027=A 41=-");
        assertThat(m.machine.keyForClOrdId("A")).isEqualTo("A");
    }

    @Test
    @DisplayName("E59 ClOrdIDs with : ; , % are escaped in 9015 and 9021, and parse back")
    void e59() {
        MachineTester m = new MachineTester();
        String root = "20261031:DESK7;A,B%1";
        m.send("D 11=" + root + " 38=100 44=10 60=" + t(0));
        m.send("F 11=" + root + "-2 41=" + root + " 60=" + t(1));
        m.expect(root, "9021=20261031%3ADESK7%3BA%2CB%251,20261031%3ADESK7%3BA%2CB%251-2 "
                + "9015=NEW:R:::20261031%3ADESK7%3BA%2CB%251;CANCEL:R:::20261031%3ADESK7%3BA%2CB%251-2");
        OrderState rebuilt = RecordReader.read(m.cached(root), 1).state();
        assertThat(rebuilt.sameRecordAs(m.state(root))).isTrue();
        assertThat(rebuilt.clOrdIds()).containsExactly(root, root + "-2");
    }

    @Test
    @DisplayName("E60 a D's header tags and tags in the machine's ranges are stripped and counted")
    void e60() {
        MachineTester m = new MachineTester();
        m.send("D 8=FIX.4.2 9=120 34=17 49=OMS 56=VENUE 52=20261031-10:00:00 11=E60 38=100 44=10 55=SYM "
                + "9015=NONE 9050=venue 9000=PARENT 13011=BOGUS 15038=1 9100=hook 10=123 60=" + t(0));
        m.expect("E60", "13011=E60 9015=NEW:R:::E60 15038=0 9000=PARENT 55=SYM 9050=- 8=- 9=- 34=- 49=- 56=- "
                + "52=- 10=- 9100=hook");
        assertThat(m.count(Counter.STRIPPED_TAGS)).isEqualTo(11);
    }

    @Test
    @DisplayName("E61 a thousand amends: 9021 carries the last 50, 9030 the count")
    void e61() {
        MachineTester m = acked("E61", "1000");
        String previous = "E61";
        for (int i = 2; i <= 1001; i++) {
            String id = "E61-" + i;
            m.send("G 11=" + id + " 41=" + previous + " 38=1000 44=" + (50 + i) + " 60=" + t(2));
            m.send("8 150=5 39=0 11=" + id + " 41=" + previous + " 37=ORD-E61 17=E61-X" + i
                    + " 38=1000 14=0 151=1000 6=0 60=" + t(2));
            previous = id;
        }
        m.expect("E61", "9030=1001 9014=E61-1001");
        assertThat(m.value("E61", 9021).split(",")).hasSize(50).startsWith("E61-952").endsWith("E61-1001");
        assertThat(m.machine.keyForClOrdId("E61-500")).isEqualTo("E61");
    }

    @Test
    @DisplayName("E76b an unnamed 150 (a FIX 4.4 F) with 32 is a fill: applied, no pending touched")
    void e76b() {
        MachineTester m = acked("E76b", "1000");
        m.send("F 11=E76b-2 41=E76b 60=" + t(2));
        m.send("8 150=F 39=1 11=E76b-2 41=E76b 37=ORD-E76b 17=E76b-E2 14=100 151=900 6=50 32=100 31=50 60=" + t(3));
        m.expect("E76b", "39=1 14=100 9016=1 32=100");
        assertThat(m.machine.counters().byExecType()).containsEntry("F", 1L);
    }

    @Test
    @DisplayName("an unrouted type is ignored, resolved for the audit trailer, and applied to nothing")
    void unrouted() {
        MachineTester m = acked("H", "1000");
        Outcome status = m.send("H 11=H 37=ORD-H 60=" + t(2));
        assertThat(status.reason()).isEqualTo(IgnoreReason.UNROUTED);
        assertThat(status.key()).isEqualTo("H");
        assertThat(status.publish()).isEqualTo(Outcome.Publish.NONE);
        m.expect("H", "9025=0 9023=2");
    }
}
