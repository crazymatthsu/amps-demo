package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.spi.OrderStateView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One order's cache entry (docs/02 §3.1): what conditional apply needs, and
 * nothing a subscriber could get from the record instead.
 *
 * <p>Quantities and prices are kept as the venue's text, so the record shows
 * what was sent; the rules compare them numerically through
 * {@link Quantities}. Every field here is on the record (docs/04 §1) except
 * the ExecID ring behind the last ExecID and the carried dirty mask, which is
 * what makes the record a sufficient checkpoint.
 *
 * <p>Mutable, and owned by the machine's thread. {@code plan} works on a
 * {@link #copy()} so the cached entry is untouched until {@code commit}.
 */
public final class OrderState implements OrderStateView {

    String orderKey;
    char keyKind = 'C';
    /** Every ClOrdID bound to this order, oldest first -- after a rebuild, the last 9021 held. */
    List<String> clOrdIds = new ArrayList<>(3);
    /** How many ClOrdIDs the chain has used: 9030, which outlives 9021's cap across a rebuild. */
    long clOrdIdCount;
    String workingClOrdId;
    /** Every OrderID seen, latest first; the record shows the first. */
    List<String> orderIds = new ArrayList<>(1);
    String parentOrderId;
    String linkedClOrdId;
    String ackedQty;
    String ackedPx;
    /** Tag 59: the after-terminal rules never reopen an IOC or FOK that answers nothing. */
    String timeInForce;
    String venueStatus;
    String lastExecType;
    String cumQty;
    String leavesQty;
    String avgPx;
    String lastExecId;
    /** The last {@code exec-id-history} ExecIDs; not on the record, so depth one after a rebuild. */
    String[] execIdRing = new String[0];
    int ringNext;
    String lastFillExecId;
    String lastFillCorrectionExecId;
    List<String> bustedExecIds = new ArrayList<>(0);
    String lastReportTime;
    List<PendingRequest> pending = new ArrayList<>(0);
    long msgCount;
    long ignoredCount;
    long lateFills;
    Origin origin = Origin.D;
    int schemaVersion = 1;
    /** Bookkeeping of the last applied message: 11, 41, 60, 9022. */
    String lastClOrdId;
    String lastOrigClOrdId;
    String lastTransactTime;
    String lastMsgType;
    /** Fields an ignored message touched, carried to the next delta. */
    long carriedDirty;

    OrderState() {
    }

    /** A deep copy: plan applies its rules to one of these. */
    OrderState copy() {
        OrderState copy = new OrderState();
        copy.orderKey = orderKey;
        copy.keyKind = keyKind;
        copy.clOrdIds = new ArrayList<>(clOrdIds);
        copy.clOrdIdCount = clOrdIdCount;
        copy.workingClOrdId = workingClOrdId;
        copy.orderIds = new ArrayList<>(orderIds);
        copy.parentOrderId = parentOrderId;
        copy.linkedClOrdId = linkedClOrdId;
        copy.ackedQty = ackedQty;
        copy.ackedPx = ackedPx;
        copy.timeInForce = timeInForce;
        copy.venueStatus = venueStatus;
        copy.lastExecType = lastExecType;
        copy.cumQty = cumQty;
        copy.leavesQty = leavesQty;
        copy.avgPx = avgPx;
        copy.lastExecId = lastExecId;
        copy.execIdRing = execIdRing.clone();
        copy.ringNext = ringNext;
        copy.lastFillExecId = lastFillExecId;
        copy.lastFillCorrectionExecId = lastFillCorrectionExecId;
        copy.bustedExecIds = new ArrayList<>(bustedExecIds);
        copy.lastReportTime = lastReportTime;
        copy.pending = new ArrayList<>(pending);
        copy.msgCount = msgCount;
        copy.ignoredCount = ignoredCount;
        copy.lateFills = lateFills;
        copy.origin = origin;
        copy.schemaVersion = schemaVersion;
        copy.lastClOrdId = lastClOrdId;
        copy.lastOrigClOrdId = lastOrigClOrdId;
        copy.lastTransactTime = lastTransactTime;
        copy.lastMsgType = lastMsgType;
        copy.carriedDirty = carriedDirty;
        return copy;
    }

    // ---- derived --------------------------------------------------------------

    /** 39 ∈ {2, 3, 4, 8, C}. */
    public boolean terminal() {
        return isTerminal(venueStatus);
    }

    static boolean isTerminal(String ordStatus) {
        return ordStatus != null && switch (ordStatus) {
            case FixTags.OrdStatus.FILLED, FixTags.OrdStatus.DONE_FOR_DAY, FixTags.OrdStatus.CANCELED,
                 FixTags.OrdStatus.REJECTED, FixTags.OrdStatus.EXPIRED -> true;
            default -> false;
        };
    }

    PendingRequest pendingRequest(String clOrdId) {
        for (PendingRequest request : pending) {
            if (request.clOrdId().equals(clOrdId)) {
                return request;
            }
        }
        return null;
    }

    boolean hasPending(PendingAction action) {
        for (PendingRequest request : pending) {
            if (request.action() == action) {
                return true;
            }
        }
        return false;
    }

    /** The newest in-flight request, or null. */
    PendingRequest newestPending() {
        return pending.isEmpty() ? null : pending.getLast();
    }

    /** The newest pending REPLACE, or null: what 15038/15044 describe. */
    PendingRequest newestReplace() {
        for (int i = pending.size() - 1; i >= 0; i--) {
            if (pending.get(i).action() == PendingAction.REPLACE) {
                return pending.get(i);
            }
        }
        return null;
    }

    boolean execIdSeen(String execId) {
        if (execId.equals(lastExecId)) {
            return true;
        }
        for (String seen : execIdRing) {
            if (execId.equals(seen)) {
                return true;
            }
        }
        return false;
    }

    void rememberExecId(String execId, int depth) {
        lastExecId = execId;
        if (depth <= 0) {
            return;
        }
        if (execIdRing.length != depth) {
            execIdRing = Arrays.copyOf(execIdRing, depth);
            ringNext = ringNext % depth;
        }
        execIdRing[ringNext] = execId;
        ringNext = (ringNext + 1) % depth;
    }

    // ---- read access, for tests, hooks and the loader --------------------------

    public String orderKey() {
        return orderKey;
    }

    public char keyKind() {
        return keyKind;
    }

    public List<String> clOrdIds() {
        return List.copyOf(clOrdIds);
    }

    public long clOrdIdCount() {
        return clOrdIdCount;
    }

    public String workingClOrdId() {
        return workingClOrdId;
    }

    public List<String> orderIds() {
        return List.copyOf(orderIds);
    }

    public String parentOrderId() {
        return parentOrderId;
    }

    public String linkedClOrdId() {
        return linkedClOrdId;
    }

    public String ackedQty() {
        return ackedQty;
    }

    public String ackedPx() {
        return ackedPx;
    }

    public String timeInForce() {
        return timeInForce;
    }

    public String venueStatus() {
        return venueStatus;
    }

    public String lastExecType() {
        return lastExecType;
    }

    public String cumQty() {
        return cumQty;
    }

    public String leavesQty() {
        return leavesQty;
    }

    public String avgPx() {
        return avgPx;
    }

    public String lastExecId() {
        return lastExecId;
    }

    public String lastFillExecId() {
        return lastFillExecId;
    }

    public List<String> bustedExecIds() {
        return List.copyOf(bustedExecIds);
    }

    public String lastReportTime() {
        return lastReportTime;
    }

    public List<PendingRequest> pending() {
        return List.copyOf(pending);
    }

    public long msgCount() {
        return msgCount;
    }

    public long ignoredCount() {
        return ignoredCount;
    }

    public long lateFills() {
        return lateFills;
    }

    public Origin origin() {
        return origin;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String lastMsgType() {
        return lastMsgType;
    }

    /**
     * Equality of everything the record carries: the round-trip test's
     * notion of "rebuilt equal", which excludes the ExecID ring behind the
     * last ExecID and the carried dirty mask (neither is on the record), and
     * keeps only the latest OrderID (the record holds one).
     */
    public boolean sameRecordAs(OrderState other) {
        return Objects.equals(orderKey, other.orderKey)
                && keyKind == other.keyKind
                && clOrdIds.equals(other.clOrdIds)
                && clOrdIdCount == other.clOrdIdCount
                && Objects.equals(workingClOrdId, other.workingClOrdId)
                && Objects.equals(latestOrderId(), other.latestOrderId())
                && Objects.equals(parentOrderId, other.parentOrderId)
                && Objects.equals(linkedClOrdId, other.linkedClOrdId)
                && Objects.equals(ackedQty, other.ackedQty)
                && Objects.equals(ackedPx, other.ackedPx)
                && Objects.equals(venueStatus, other.venueStatus)
                && Objects.equals(lastExecType, other.lastExecType)
                && Objects.equals(cumQty, other.cumQty)
                && Objects.equals(leavesQty, other.leavesQty)
                && Objects.equals(avgPx, other.avgPx)
                && Objects.equals(lastExecId, other.lastExecId)
                && Objects.equals(lastFillRecorded(), other.lastFillRecorded())
                && bustedExecIds.equals(other.bustedExecIds)
                && Objects.equals(lastReportTime, other.lastReportTime)
                && pending.equals(other.pending)
                && msgCount == other.msgCount
                && ignoredCount == other.ignoredCount
                && lateFills == other.lateFills
                && origin == other.origin
                && schemaVersion == other.schemaVersion
                && Objects.equals(lastClOrdId, other.lastClOrdId)
                && Objects.equals(lastOrigClOrdId, other.lastOrigClOrdId)
                && Objects.equals(lastTransactTime, other.lastTransactTime)
                && Objects.equals(lastMsgType, other.lastMsgType);
    }

    String latestOrderId() {
        return orderIds.isEmpty() ? null : orderIds.getFirst();
    }

    /** What 9028 shows: the latest correction of the last fill, else the fill. */
    String lastFillRecorded() {
        return lastFillCorrectionExecId != null ? lastFillCorrectionExecId : lastFillExecId;
    }

    @Override
    public String toString() {
        return "OrderState[" + orderKey + " 39=" + venueStatus + " 14=" + cumQty + " 151=" + leavesQty
                + " 6=" + avgPx + " pending=" + RecordCodec.encodePending(pending) + " " + origin + "]";
    }
}
