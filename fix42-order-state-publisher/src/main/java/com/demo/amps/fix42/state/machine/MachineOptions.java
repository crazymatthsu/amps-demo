package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.config.CancelRejectStatusPolicy;
import com.demo.amps.fix42.state.config.FillUnderPendingIdPolicy;
import com.demo.amps.fix42.state.config.LateFillPolicy;
import com.demo.amps.fix42.state.config.NonFillAfterTerminalPolicy;
import java.util.Set;

/**
 * The machine's constructor options: tier one of docs/07, the policy knobs a
 * venue dialect bundles, plus the encoding limits. Bound from
 * {@code application.yml} in phase 3; {@link #defaults()} is what the shipped
 * configuration says.
 *
 * @param cancelRejectStatus   what a 35=9's tag 39 may do (docs/03 §4)
 * @param lateFillAfterTerminal a fill with higher CumQty after a terminal report
 * @param nonFillAfterTerminal a non-fill report with a non-terminal 39 after a terminal one
 * @param fillUnderPendingId   whether a fill under a pending amend's id answers it
 * @param staleGuard           drop a non-reopening report whose CumQty went backwards
 * @param transactTimeGuard    drop a non-reopening report with equal CumQty and an older 60
 * @param execIdHistory        depth of the per-order ExecID ring
 * @param orderTermTags        order terms a {@code 150=0/5/D} report refreshes
 * @param stripTags            session header and trailer tags never copied onto the record
 * @param chainIdsPublished    how many ClOrdIDs 12021 carries
 * @param schemaVersion        12024 on a new record
 * @param publishUnchanged     publish 12025 at once for an ignored message
 */
public record MachineOptions(CancelRejectStatusPolicy cancelRejectStatus,
                             LateFillPolicy lateFillAfterTerminal,
                             NonFillAfterTerminalPolicy nonFillAfterTerminal,
                             FillUnderPendingIdPolicy fillUnderPendingId,
                             boolean staleGuard,
                             boolean transactTimeGuard,
                             int execIdHistory,
                             Set<Integer> orderTermTags,
                             Set<Integer> stripTags,
                             int chainIdsPublished,
                             int schemaVersion,
                             boolean publishUnchanged) {

    public static final Set<Integer> DEFAULT_ORDER_TERM_TAGS = Set.of(18, 40, 44, 59, 99, 100, 110, 111, 126);
    public static final Set<Integer> DEFAULT_STRIP_TAGS =
            Set.of(8, 9, 10, 34, 43, 49, 50, 52, 56, 57, 97, 115, 116, 122, 128, 129);

    public MachineOptions {
        orderTermTags = Set.copyOf(orderTermTags);
        stripTags = Set.copyOf(stripTags);
        if (execIdHistory < 0 || chainIdsPublished < 1) {
            throw new IllegalArgumentException("exec-id-history must be >= 0 and chain-ids-published >= 1");
        }
    }

    public static MachineOptions defaults() {
        return new MachineOptions(CancelRejectStatusPolicy.WHEN_NOT_TERMINAL,
                LateFillPolicy.APPLY_ECONOMICS, NonFillAfterTerminalPolicy.ADOPT_VENUE_IF_LATER,
                FillUnderPendingIdPolicy.IGNORE, true, true, 8, DEFAULT_ORDER_TERM_TAGS,
                DEFAULT_STRIP_TAGS, 50, 2, false);
    }

    public MachineOptions withCancelRejectStatus(CancelRejectStatusPolicy value) {
        return new MachineOptions(value, lateFillAfterTerminal, nonFillAfterTerminal, fillUnderPendingId,
                staleGuard, transactTimeGuard, execIdHistory, orderTermTags, stripTags, chainIdsPublished,
                schemaVersion, publishUnchanged);
    }

    public MachineOptions withLateFillAfterTerminal(LateFillPolicy value) {
        return new MachineOptions(cancelRejectStatus, value, nonFillAfterTerminal, fillUnderPendingId,
                staleGuard, transactTimeGuard, execIdHistory, orderTermTags, stripTags, chainIdsPublished,
                schemaVersion, publishUnchanged);
    }

    public MachineOptions withNonFillAfterTerminal(NonFillAfterTerminalPolicy value) {
        return new MachineOptions(cancelRejectStatus, lateFillAfterTerminal, value, fillUnderPendingId,
                staleGuard, transactTimeGuard, execIdHistory, orderTermTags, stripTags, chainIdsPublished,
                schemaVersion, publishUnchanged);
    }

    public MachineOptions withFillUnderPendingId(FillUnderPendingIdPolicy value) {
        return new MachineOptions(cancelRejectStatus, lateFillAfterTerminal, nonFillAfterTerminal, value,
                staleGuard, transactTimeGuard, execIdHistory, orderTermTags, stripTags, chainIdsPublished,
                schemaVersion, publishUnchanged);
    }

    public MachineOptions withGuards(boolean stale, boolean transactTime) {
        return new MachineOptions(cancelRejectStatus, lateFillAfterTerminal, nonFillAfterTerminal,
                fillUnderPendingId, stale, transactTime, execIdHistory, orderTermTags, stripTags,
                chainIdsPublished, schemaVersion, publishUnchanged);
    }

    public MachineOptions withExecIdHistory(int value) {
        return new MachineOptions(cancelRejectStatus, lateFillAfterTerminal, nonFillAfterTerminal,
                fillUnderPendingId, staleGuard, transactTimeGuard, value, orderTermTags, stripTags,
                chainIdsPublished, schemaVersion, publishUnchanged);
    }

    public MachineOptions withChainIdsPublished(int value) {
        return new MachineOptions(cancelRejectStatus, lateFillAfterTerminal, nonFillAfterTerminal,
                fillUnderPendingId, staleGuard, transactTimeGuard, execIdHistory, orderTermTags, stripTags,
                value, schemaVersion, publishUnchanged);
    }

    public MachineOptions withPublishUnchanged(boolean value) {
        return new MachineOptions(cancelRejectStatus, lateFillAfterTerminal, nonFillAfterTerminal,
                fillUnderPendingId, staleGuard, transactTimeGuard, execIdHistory, orderTermTags, stripTags,
                chainIdsPublished, schemaVersion, value);
    }
}
