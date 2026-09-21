package com.demo.amps.connectors.amps;

import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.fields.ReasonField;
import com.demo.amps.connectors.runtime.MessageContext;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the AMPS client's persisted acks back into acknowledgments to the sources.
 *
 * <p>In {@code ack-mode: PERSISTED} a batch does not wait. Each command is issued, the
 * sequence the publish store assigned it is written onto its {@link MessageContext}, and the
 * context is parked here under that sequence. When the server's persisted ack arrives --
 * cumulative, "everything up to {@code n}" -- {@link #persistedUpTo(long)} drains every
 * context at or below {@code n} in sequence order and acknowledges it to its source. That is
 * the same contract the flushing mode keeps, kept one record at a time on the receive thread
 * instead of one batch at a time on the publishing thread.
 *
 * <p>The drain coalesces. Consecutive contexts whose records came through the <em>same</em>
 * {@link com.demo.amps.connectors.source.Acknowledger} -- one stream: a Kafka partition, a
 * JDBC poll loop -- become one {@code ackBatch(first, last)} over their in-side positions,
 * and a run of one is a plain {@code ack()}. Identity, not equality, because the
 * acknowledger <em>is</em> the stream: two partitions' acknowledgers are two objects, and a
 * range across them would be meaningless. The acknowledgers are cumulative and cheap by
 * contract; one that throws is contained, logged, and does not stop the rest of the drain.
 *
 * <p>Two things arrive out of the obvious order and are handled here rather than assumed
 * away. The persisted ack for a command can reach the receive thread <em>before</em> the
 * publishing thread has parked the context -- the client answered with the sequence, the
 * server answered the client, and {@link #track} had not run yet -- so a drain that found
 * nothing would leave that context waiting for the next ack, or for ever on a quiet feed.
 * {@link #track} therefore re-checks the high-water mark after parking and drains itself if
 * it is already covered; whoever removes the entry acknowledges it, and only one can. And a
 * <em>failed write</em> -- the server refusing the command, or answering a replayed one as a
 * duplicate -- removes and acknowledges its context too: the client discards the entry
 * either way, and re-reading the record from the source would only fail the same way again.
 * A duplicate is the persisted ack that was lost with a connection; the others are counted
 * as rejected and reported by {@code AlertingAmpsPublisher}.
 *
 * <p>The drain is serialised so the runs it builds are contiguous, and the map is a
 * {@link ConcurrentSkipListMap} so the parking, the counting and the status line need no
 * lock at all. Nothing here blocks: an acknowledgment is a counter, an offset map or an
 * atomic reference on the source's side.
 */
public final class PersistedAckTracker implements PublishListener {

    private static final Logger log = LoggerFactory.getLogger(PersistedAckTracker.class);

    private final String connectorName;
    private final ConcurrentSkipListMap<Long, MessageContext> pending = new ConcurrentSkipListMap<>();

    /** The highest sequence the server has acknowledged as persisted so far. */
    private final AtomicLong highWater = new AtomicLong();

    private final AtomicLong persisted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong unsequenced = new AtomicLong();
    private final AtomicLong ackFailures = new AtomicLong();

    /**
     * @param connectorName the connector's name, for the log lines
     */
    public PersistedAckTracker(String connectorName) {
        this.connectorName = connectorName;
    }

    /**
     * Park a context until the server acknowledges its sequence as persisted.
     *
     * <p>A context with no out-side sequence -- a publisher without a store, which the
     * validator refuses for this mode, or one that assigned none -- has no ack to wait for
     * and is acknowledged now, defensively: better a record acknowledged on the strength of
     * the publish alone than one never acknowledged at all.
     *
     * @param context the context, already issued, with its sequence assigned
     */
    public void track(MessageContext context) {
        long sequence = context.dataOutSeqno();
        if (sequence <= 0) {
            unsequenced.incrementAndGet();
            acknowledge(context, context, 1);
            return;
        }
        pending.put(sequence, context);
        // The ack may already be in: the receive thread can process the server's answer
        // between the client answering with the sequence and this put, and its drain found
        // nothing. Whoever removes the entry acknowledges it, so this cannot double up.
        if (sequence <= highWater.get()) {
            drainUpTo(highWater.get());
        }
    }

    @Override
    public void persistedUpTo(long seqno) {
        if (seqno <= 0) {
            return;
        }
        highWater.accumulateAndGet(seqno, Math::max);
        // Always drain, even for a repeated or a lower number: the map answers "nothing" at
        // once, and the one case where there IS something -- a context parked after the ack
        // that covered it -- is exactly the case that must not be skipped.
        drainUpTo(seqno);
    }

    @Override
    public void failedWrite(long seqno, int reason) {
        boolean duplicate = reason == Message.Reason.Duplicate;
        long count = duplicate ? duplicates.incrementAndGet() : rejected.incrementAndGet();
        MessageContext context = seqno > 0 ? pending.remove(seqno) : null;
        if (context != null) {
            acknowledge(context, context, 1);
        }
        if (!duplicate && (count <= 10 || count % 1_000 == 0)) {
            log.warn("[{}] AMPS refused sequence {} ({}); {} rejected write(s) so far, the "
                            + "record is acknowledged to its source and not retried",
                    connectorName, seqno, ReasonField.encodeReason(reason), count);
        }
    }

    /**
     * Acknowledge, in sequence order, every parked context at or below {@code sequence},
     * coalescing each run that shares an acknowledger into one range.
     */
    private synchronized void drainUpTo(long sequence) {
        ConcurrentNavigableMap<Long, MessageContext> ready = pending.headMap(sequence, true);
        Map.Entry<Long, MessageContext> entry = ready.pollFirstEntry();
        if (entry == null) {
            return;
        }
        MessageContext first = entry.getValue();
        MessageContext last = first;
        int run = 1;
        long drained = 0;
        while ((entry = ready.pollFirstEntry()) != null) {
            MessageContext next = entry.getValue();
            if (next.in().acknowledger() == last.in().acknowledger()) {
                last = next;
                run++;
                continue;
            }
            acknowledge(first, last, run);
            drained += run;
            first = next;
            last = next;
            run = 1;
        }
        acknowledge(first, last, run);
        drained += run;
        persisted.addAndGet(drained);
    }

    /** One run: a range over its in-side positions, or a single ack for a run of one. */
    private void acknowledge(MessageContext first, MessageContext last, int run) {
        try {
            if (run == 1) {
                last.ack();
            } else {
                last.ackBatch(first.dataInSeqno(), last.dataInSeqno());
            }
        } catch (RuntimeException e) {
            // The contract says an acknowledger never throws; one that does must not take
            // the rest of the drain -- or the receive thread -- down with it.
            long count = ackFailures.incrementAndGet();
            if (count <= 10 || count % 1_000 == 0) {
                log.warn("[{}] acknowledging {} record(s) up to in-side position {} threw "
                                + "({} failure(s) so far)",
                        connectorName, run, last.dataInSeqno(), count, e);
            }
        }
    }

    /** Contexts issued and not yet acknowledged as persisted. */
    public long pending() {
        return pending.size();
    }

    /** Contexts the server acknowledged as persisted, and which were acknowledged onward. */
    public long persisted() {
        return persisted.get();
    }

    /** Writes the server refused for any reason but a duplicate; acknowledged, not retried. */
    public long rejected() {
        return rejected.get();
    }

    /** Replayed writes the server already had: the persisted acks a connection drop lost. */
    public long duplicates() {
        return duplicates.get();
    }

    /** Contexts acknowledged at once because they carried no sequence to wait for. */
    public long unsequenced() {
        return unsequenced.get();
    }

    /** The highest sequence the server has acknowledged as persisted; {@code 0} before any. */
    public long highWater() {
        return highWater.get();
    }

    /** The counters in one line, for a log line or a status line. */
    public String status() {
        return String.format("pending=%d persisted=%d rejected=%d duplicates=%d unsequenced=%d",
                pending(), persisted(), rejected(), duplicates(), unsequenced());
    }

    @Override
    public String toString() {
        return "PersistedAckTracker[" + connectorName + " " + status() + "]";
    }
}
