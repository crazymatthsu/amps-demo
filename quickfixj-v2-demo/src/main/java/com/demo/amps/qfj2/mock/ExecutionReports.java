package com.demo.amps.qfj2.mock;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecTransType;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastShares;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix42.ExecutionReport;

/** Invented but well-formed FIX 4.2 execution reports, for the mock feed and the tests. */
public final class ExecutionReports {

    private ExecutionReports() {
    }

    /**
     * Report number {@code n} of run {@code runId}: every third one a full
     * fill, the rest partial, quantities and prices varying with {@code n}.
     * ExecID is {@code EXEC-<runId>-<n>}, unique across runs.
     */
    public static ExecutionReport sample(String runId, long n, String symbol) {
        boolean fill = n % 3 == 0;
        double qty = 1000 + (n % 5) * 500;
        double last = fill ? qty : Math.floor(qty / 2);
        double px = 100 + (n % 50) + 0.25 * (n % 4);

        ExecutionReport report = new ExecutionReport();
        report.set(new OrderID("ORD-" + runId + "-" + n));
        report.set(new ClOrdID("CL-" + runId + "-" + n));
        report.set(new ExecID("EXEC-" + runId + "-" + n));
        report.set(new ExecTransType(ExecTransType.NEW));
        report.set(new ExecType(fill ? ExecType.FILL : ExecType.PARTIAL_FILL));
        report.set(new OrdStatus(fill ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED));
        report.set(new Symbol(symbol));
        report.set(new Side(n % 2 == 0 ? Side.BUY : Side.SELL));
        report.set(new OrderQty(qty));
        report.set(new LastShares(last));
        report.set(new LastPx(px));
        report.set(new CumQty(last));
        report.set(new LeavesQty(qty - last));
        report.set(new AvgPx(px));
        report.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        return report;
    }
}
