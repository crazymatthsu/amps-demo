package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.source.InboundRecord;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one {@link MessageContext}: two records and a write-once slot for the AMPS sequence.
 *
 * <p>The slot is an {@link AtomicLong} rather than a volatile because it is written on the
 * publishing thread and read on whichever thread later acknowledges by it, and the
 * compare-and-set is what makes "written exactly once" a property rather than a convention.
 */
public final class DefaultMessageContext implements MessageContext {

    private final InboundRecord in;
    private final OutboundRecord out;
    private final AtomicLong outSeqno = new AtomicLong();

    /**
     * @param in the record as the source delivered it
     * @param out the command the pipeline decided on
     */
    public DefaultMessageContext(InboundRecord in, OutboundRecord out) {
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public InboundRecord in() {
        return in;
    }

    @Override
    public OutboundRecord out() {
        return out;
    }

    @Override
    public long dataOutSeqno() {
        return outSeqno.get();
    }

    @Override
    public void assignOutSeqno(long seqno) {
        if (seqno < 0) {
            throw new IllegalArgumentException("an AMPS sequence is never negative: " + seqno);
        }
        if (seqno == 0 || outSeqno.compareAndSet(0, seqno)) {
            return;
        }
        long assigned = outSeqno.get();
        if (assigned != seqno) {
            throw new IllegalStateException("out-side sequence already assigned as " + assigned
                    + "; a context is published once, and " + seqno + " is a second time");
        }
    }

    @Override
    public String toString() {
        return "MessageContext[" + in.action() + " key=" + in.key() + " inSeqno=" + in.seqno()
                + " inType=" + in.type() + " -> " + out.command() + " " + out.topic()
                + " outType=" + out.type() + " outSeqno=" + outSeqno.get() + "]";
    }
}
