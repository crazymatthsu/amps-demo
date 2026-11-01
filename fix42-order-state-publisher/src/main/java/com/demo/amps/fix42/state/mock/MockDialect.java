package com.demo.amps.fix42.state.mock;

import java.util.List;

/**
 * How a mock venue speaks FIX 4.2 where real venues differ.
 *
 * <p>The round-1 review (docs/08) showed that the venue's <i>dialect</i> is
 * part of the state machine's specification: three of the first design's
 * rules broke on message shapes real drop copies produce. So the mock venue
 * can speak more than one, and the machine is tested against each
 * (docs/05 phase 1). {@link #DEFAULT} is what {@link OrderChain} spoke before
 * the switches existed -- the shape the FIX state matrices prescribe -- and the
 * scripted flow renders byte for byte under it (the pin test).
 *
 * @param fillsEchoPendingId        fills reported while a request is pending
 *                                  carry the request's ClOrdID (on by default;
 *                                  off: they carry the working id)
 * @param confirmsEchoOriginalId    {@code 150=4} and {@code 150=5} carry the
 *                                  request's OrigClOrdID in tag 11 instead of
 *                                  its own ClOrdID (06 E9, E10)
 * @param pendingAcksEchoWorkingId  {@code 150=6} and {@code 150=E} carry the
 *                                  working id rather than the request's (06 E17a)
 * @param replaceAsCancelThenNew    a replace is answered by a {@code 150=4} for
 *                                  the old leg and a {@code 150=0} for the new
 *                                  one, never a {@code 150=5} (06 E35)
 * @param rejectReplaceWithExecReport a rejected amend is answered by a
 *                                  {@code 150=8} echoing the proposed terms,
 *                                  with this tag 39 ({@code "8"} or
 *                                  {@code "1"}), instead of a {@code 35=9};
 *                                  {@code null} for a 35=9 (06 E16)
 * @param rejectStatusAtRequestTime every {@code 35=9} carries this tag 39 -- the
 *                                  status when the request was received -- rather
 *                                  than the order's status now; {@code null} for now
 * @param statusReportExecId        the tag 17 on a {@code 20=3} status report:
 *                                  {@code null} for a real ExecID, {@code "0"}, or
 *                                  {@code ""} for none at all (06 E76a)
 * @param rejectWithoutOrderId      an order reject ({@code 150=8} for the D)
 *                                  carries {@code 37=NONE}
 * @param transactTimeInSeconds     tag 60 without milliseconds (06 E37)
 * @param orderIdEqualsClOrdId      the venue's OrderID is the D's own ClOrdID
 * @param rotateOrderIdOnReplace    every replace confirm carries a new OrderID (06 E44)
 * @param bustAfterCloseOut         busts and corrects are allowed after the order
 *                                  closed out (06 E26, E27)
 * @param restateWhilePending       a {@code 150=D} is allowed while an amend is in flight
 */
public record MockDialect(boolean fillsEchoPendingId,
                          boolean confirmsEchoOriginalId,
                          boolean pendingAcksEchoWorkingId,
                          boolean replaceAsCancelThenNew,
                          String rejectReplaceWithExecReport,
                          String rejectStatusAtRequestTime,
                          String statusReportExecId,
                          boolean rejectWithoutOrderId,
                          boolean transactTimeInSeconds,
                          boolean orderIdEqualsClOrdId,
                          boolean rotateOrderIdOnReplace,
                          boolean bustAfterCloseOut,
                          boolean restateWhilePending) {

    /** What the chain spoke before the switches existed. */
    public static final MockDialect DEFAULT = new MockDialect(true, false, false, false, null,
            null, null, false, false, false, false, false, false);

    /**
     * {@link #DEFAULT} with one switch flipped each, named: the set the
     * property tests run every edge case under.
     */
    public static List<Named> variants() {
        return List.of(
                new Named("default", DEFAULT),
                new Named("fills-under-working-id", DEFAULT.fillsEchoPendingId(false)),
                new Named("confirms-echo-original-id", DEFAULT.confirmsEchoOriginalId(true)),
                new Named("pending-acks-echo-working-id", DEFAULT.pendingAcksEchoWorkingId(true)),
                new Named("replace-as-cancel-then-new", DEFAULT.replaceAsCancelThenNew(true)),
                new Named("reject-replace-with-8-39=8", DEFAULT.rejectReplaceWithExecReport("8")),
                new Named("reject-replace-with-8-39=1", DEFAULT.rejectReplaceWithExecReport("1")),
                new Named("reject-status-at-request-time-6", DEFAULT.rejectStatusAtRequestTime("6")),
                new Named("status-reports-17=0", DEFAULT.statusReportExecId("0")),
                new Named("status-reports-without-17", DEFAULT.statusReportExecId("")),
                new Named("reject-without-order-id", DEFAULT.rejectWithoutOrderId(true)),
                new Named("transact-time-in-seconds", DEFAULT.transactTimeInSeconds(true)),
                new Named("order-id-equals-clordid", DEFAULT.orderIdEqualsClOrdId(true)),
                new Named("rotate-order-id-on-replace", DEFAULT.rotateOrderIdOnReplace(true)));
    }

    /** A dialect with a name, for parameterised test display. */
    public record Named(String name, MockDialect dialect) {
        @Override
        public String toString() {
            return name;
        }
    }

    public MockDialect fillsEchoPendingId(boolean value) {
        return new MockDialect(value, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect confirmsEchoOriginalId(boolean value) {
        return new MockDialect(fillsEchoPendingId, value, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect pendingAcksEchoWorkingId(boolean value) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, value,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect replaceAsCancelThenNew(boolean value) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                value, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect rejectReplaceWithExecReport(String ordStatus) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, ordStatus, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect rejectStatusAtRequestTime(String ordStatus) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, ordStatus,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect statusReportExecId(String execId) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                execId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect rejectWithoutOrderId(boolean value) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, value, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect transactTimeInSeconds(boolean value) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, value,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect orderIdEqualsClOrdId(boolean value) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                value, rotateOrderIdOnReplace, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect rotateOrderIdOnReplace(boolean value) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, value, bustAfterCloseOut, restateWhilePending);
    }

    public MockDialect bustAfterCloseOut(boolean value) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, value, restateWhilePending);
    }

    public MockDialect restateWhilePending(boolean value) {
        return new MockDialect(fillsEchoPendingId, confirmsEchoOriginalId, pendingAcksEchoWorkingId,
                replaceAsCancelThenNew, rejectReplaceWithExecReport, rejectStatusAtRequestTime,
                statusReportExecId, rejectWithoutOrderId, transactTimeInSeconds,
                orderIdEqualsClOrdId, rotateOrderIdOnReplace, bustAfterCloseOut, value);
    }
}
