package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.spi.IgnoreReason;
import java.util.List;

/**
 * What {@link OrderStateMachine#plan} decided about one message (docs/03 §2),
 * with the changes staged: the publisher writes what it says, and only then
 * calls {@link OrderStateMachine#commit}.
 */
public final class Outcome {

    public enum Kind {
        /** A new chain: a FULL publish for a D, a delta of every field for a MIDSTREAM chain. */
        CREATED,
        /** An existing chain changed: a delta of the dirty fields. */
        CHANGED,
        /** Nothing applied -- though a newly bound id or {@code publish-unchanged} may still publish. */
        IGNORED
    }

    /** What reaches the blotter. */
    public enum Publish {
        FULL,
        DELTA,
        NONE
    }

    /**
     * Which of the message's own tags are copied onto the record beside the
     * machine's fields.
     */
    public enum PassThrough {
        NONE,
        /** Every tag but the session's, the machine's ranges and the fields the machine owns. */
        DESCRIPTIVE,
        /** {@link #DESCRIPTIVE} less the order-term tags: a request's terms are proposals. */
        DESCRIPTIVE_NO_TERMS,
        /** Only the {@code order-term-tags} the message carries ({@code 150 ∈ {0, 5, D}}). */
        ORDER_TERMS
    }

    record Binding(boolean orderId, String id, String key) {
    }

    final long sequence;
    final Kind kind;
    final IgnoreReason reason;
    final String key;
    /** The entry as it will be after commit; null when the message resolved to no chain. */
    final OrderState staged;
    final long dirty;
    final Publish publish;
    final PassThrough passThrough;
    final boolean copyFillDetail;
    final boolean zeroFillDetail;
    final List<Binding> bindings;
    final int[] counts;
    final String msgType;
    final String execType;

    Outcome(long sequence, Kind kind, IgnoreReason reason, String key, OrderState staged, long dirty,
            Publish publish, PassThrough passThrough, boolean copyFillDetail, boolean zeroFillDetail,
            List<Binding> bindings, int[] counts, String msgType, String execType) {
        this.sequence = sequence;
        this.kind = kind;
        this.reason = reason;
        this.key = key;
        this.staged = staged;
        this.dirty = dirty;
        this.publish = publish;
        this.passThrough = passThrough;
        this.copyFillDetail = copyFillDetail;
        this.zeroFillDetail = zeroFillDetail;
        this.bindings = bindings;
        this.counts = counts;
        this.msgType = msgType;
        this.execType = execType;
    }

    public Kind kind() {
        return kind;
    }

    /** Why it was ignored; null unless {@link #kind()} is IGNORED. */
    public IgnoreReason reason() {
        return reason;
    }

    /** The OrderKey (13011) the message resolved to, or null: what the audit trailer carries. */
    public String key() {
        return key;
    }

    public Publish publish() {
        return publish;
    }

    /** The record fields the blotter publish carries. */
    public long dirty() {
        return dirty;
    }

    /** The entry as commit will leave it; null when nothing resolved. */
    public OrderState state() {
        return staged;
    }

    public PassThrough passThrough() {
        return passThrough;
    }

    public int count(Counter counter) {
        return counts[counter.ordinal()];
    }

    @Override
    public String toString() {
        return kind + (reason == null ? "" : "(" + reason + ")") + " " + key + " " + publish;
    }
}
