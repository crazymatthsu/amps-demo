package com.demo.amps.connectors.amps;

import com.demo.amps.connectors.config.AmpsTargetProperties;
import com.demo.amps.connectors.config.BatchProperties;
import com.demo.amps.connectors.runtime.MessageContext;
import com.demo.amps.connectors.runtime.OutboundRecord;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes one batch: every command in order, then the acknowledgments -- after <em>one</em>
 * flush, or as the persisted acks arrive.
 *
 * <p>The flush is what costs, not the publish, so batching is about amortising exactly that:
 * a hundred records and one round trip to find out they are all persisted. The commands are
 * issued in the order the pipeline produced them, upserts and deletes together, because
 * regrouping them would resurrect a record that was deleted and re-added inside one batch.
 *
 * <p>In {@link AmpsTargetProperties.AckMode#FLUSH FLUSH} mode, the default, acknowledgment
 * is all-or-nothing and deliberately one-directional. A successful flush acknowledges every
 * record in the batch -- committing a Kafka offset, persisting a JDBC watermark -- and
 * anything else acknowledges none of them. Nothing is lost by that: the client's publish
 * store still replays what AMPS never acknowledged, and the sources re-read from their last
 * committed position, so the failure mode is duplicates rather than gaps. That is the
 * at-least-once contract, and this method is where it is kept.
 *
 * <p>In {@link AmpsTargetProperties.AckMode#PERSISTED PERSISTED} mode the batch does not
 * wait. Each context is parked in the {@link PersistedAckTracker} under the sequence the
 * publish store assigned it, and the server's persisted acks -- cumulative, delivered on the
 * client's receive thread -- acknowledge the records as they land. Same contract, no stall
 * per batch: the source thread issues and moves on. What bounds it is {@code max-pending}:
 * once more than that many records are waiting, the batch that crossed the line flushes on
 * its own thread until they are persisted, which is the same back-pressure the flushing mode
 * applies to every batch, applied only when AMPS falls behind. A flush that times out there
 * is counted and logged; the records stay parked, and the next ack or the next flush covers
 * them.
 *
 * <p>Each command's AMPS client sequence -- the number the publish store keys its replay by
 * -- is written onto its {@link MessageContext} the moment the client answers with it, so a
 * context that has been through here knows both its in-side position and its out-side one.
 * A publisher without a store answers {@code 0}, and nothing is written.
 *
 * <p>The tracker is registered as the publisher's {@link PublishListener} here, in the
 * constructor, in both modes: a batch publisher built over a publisher is then always wired
 * to hear about failed writes, so a publish the server refuses is a counter and an alert
 * rather than a silent loss, and the only way to have a PERSISTED publisher that never
 * acknowledges is not to build one. {@code Connector} builds this before it connects, which
 * is what the publisher's contract asks for.
 *
 * <p>Nothing escapes: this runs on the connector's deadline thread as often as on the
 * source's, and an exception out of a timer-triggered release would reach that scheduler's
 * error handler rather than anyone who knows which batch it was.
 */
public final class BatchPublisher {

    private static final Logger log = LoggerFactory.getLogger(BatchPublisher.class);

    private final AmpsPublisher publisher;
    private final String connectorName;
    private final Duration flushTimeout;
    private final AmpsTargetProperties.AckMode ackMode;
    private final int maxPending;
    private final PersistedAckTracker tracker;

    private final AtomicLong publishedMessages = new AtomicLong();
    private final AtomicLong publishedBatches = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private final AtomicLong backpressureFlushes = new AtomicLong();
    private final AtomicLong flushTimeouts = new AtomicLong();

    /**
     * A flushing batch publisher with the default {@code max-pending}.
     *
     * @param publisher the connector's AMPS connection
     * @param connectorName the connector's name, for the log lines
     * @param flushTimeout how long the batch waits for its publishes to be persisted
     */
    public BatchPublisher(AmpsPublisher publisher, String connectorName, Duration flushTimeout) {
        this(publisher, connectorName, flushTimeout, AmpsTargetProperties.AckMode.FLUSH,
                new BatchProperties().getMaxPending());
    }

    /**
     * @param publisher the connector's AMPS connection; its publish listener becomes the
     *     tracker
     * @param connectorName the connector's name, for the log lines
     * @param flushTimeout how long a flush waits for the publishes to be persisted -- every
     *     batch's in FLUSH mode, a back-pressure flush's in PERSISTED mode
     * @param ackMode when the records are acknowledged to their sources
     * @param maxPending in PERSISTED mode, how many records may wait for their persisted ack
     *     before the publishing thread flushes; at least 1
     */
    public BatchPublisher(
            AmpsPublisher publisher,
            String connectorName,
            Duration flushTimeout,
            AmpsTargetProperties.AckMode ackMode,
            int maxPending) {
        if (maxPending < 1) {
            throw new IllegalArgumentException("max-pending must be at least 1: " + maxPending);
        }
        this.publisher = publisher;
        this.connectorName = connectorName;
        this.flushTimeout = flushTimeout;
        this.ackMode = ackMode == null ? AmpsTargetProperties.AckMode.FLUSH : ackMode;
        this.maxPending = maxPending;
        this.tracker = new PersistedAckTracker(connectorName);
        publisher.setPublishListener(tracker);
    }

    /**
     * Issue a batch and, if it lands, acknowledge it -- now, or as the acks arrive.
     *
     * @param batch the contexts, in the order the pipeline produced them
     */
    public void publish(List<MessageContext> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        if (ackMode == AmpsTargetProperties.AckMode.PERSISTED) {
            publishTracked(batch);
        } else {
            publishFlushed(batch);
        }
    }

    /** FLUSH: issue, one flush, and only if it returns, acknowledge everything. */
    private void publishFlushed(List<MessageContext> batch) {
        try {
            for (MessageContext context : batch) {
                long sequence = issue(context.out());
                if (sequence > 0) {
                    context.assignOutSeqno(sequence);
                }
            }
            if (!publisher.flush(flushTimeout)) {
                failed(batch, "flush did not complete within " + flushTimeout);
                return;
            }
        } catch (RuntimeException e) {
            failed(batch, e.toString());
            return;
        }
        for (MessageContext context : batch) {
            context.ack();
        }
        publishedMessages.addAndGet(batch.size());
        publishedBatches.incrementAndGet();
        log.debug("[{}] published a batch of {}", connectorName, batch.size());
    }

    /**
     * PERSISTED: issue and park each context; the persisted acks acknowledge them. A command
     * that throws ends the batch there: what was issued is parked and will be acknowledged
     * when the server persists it, what was not is left for the source to re-read.
     */
    private void publishTracked(List<MessageContext> batch) {
        int issued = 0;
        try {
            for (MessageContext context : batch) {
                long sequence = issue(context.out());
                if (sequence > 0) {
                    context.assignOutSeqno(sequence);
                }
                tracker.track(context);
                issued++;
            }
        } catch (RuntimeException e) {
            long count = failedBatches.incrementAndGet();
            log.warn("[{}] batch of {} stopped after {} command(s) ({} failed batch(es) so far): {}",
                    connectorName, batch.size(), issued, count, e.toString());
            return;
        }
        publishedBatches.incrementAndGet();
        log.debug("[{}] issued a batch of {}, {} pending", connectorName, batch.size(),
                tracker.pending());
        if (tracker.pending() > maxPending) {
            // Back-pressure, on the publishing thread: the source (or the deadline) waits
            // here until AMPS has caught up, exactly as it waits on every batch in FLUSH mode.
            backpressureFlushes.incrementAndGet();
            if (!publisher.flush(flushTimeout)) {
                long count = flushTimeouts.incrementAndGet();
                log.warn("[{}] {} record(s) pending, over max-pending {}, and the flush did not "
                                + "complete within {} ({} flush timeout(s) so far); they stay "
                                + "pending until the acks arrive",
                        connectorName, tracker.pending(), maxPending, flushTimeout, count);
            }
        }
    }

    /**
     * Wait once for whatever is still pending, and say what is left. For a stopping
     * connector, after the last batch has been released: a record still pending after this
     * was published and will be re-read, which is the at-least-once trade, but it deserves
     * a line saying so.
     *
     * @param timeout how long to wait for the persisted acks
     */
    public void drain(Duration timeout) {
        if (ackMode != AmpsTargetProperties.AckMode.PERSISTED) {
            return;
        }
        long before = tracker.pending();
        if (before == 0) {
            return;
        }
        boolean flushed;
        try {
            flushed = publisher.flush(timeout);
        } catch (RuntimeException e) {
            log.warn("[{}] the final flush failed: {}", connectorName, e.toString());
            flushed = false;
        }
        long remaining = tracker.pending();
        if (remaining == 0) {
            log.info("[{}] drained: {} pending record(s) acknowledged as persisted",
                    connectorName, before);
        } else {
            log.warn("[{}] stopping with {} of {} pending record(s) not acknowledged as "
                            + "persisted{}; they were published and will be re-read",
                    connectorName, remaining, before,
                    flushed ? "" : " (the flush did not complete within " + timeout + ")");
        }
    }

    /** One command, as its out-half spells it; answers the AMPS client sequence, or 0. */
    private long issue(OutboundRecord request) {
        return switch (request.command()) {
            case PUBLISH -> publisher.publish(request.topic(), request.data(), request.sowKey());
            case DELTA_PUBLISH ->
                    publisher.deltaPublish(request.topic(), request.data(), request.sowKey());
            case SOW_DELETE -> request.deleteFilter() != null
                    ? publisher.sowDeleteByFilter(request.topic(), request.deleteFilter())
                    : publisher.sowDeleteByKey(request.topic(), request.sowKey());
        };
    }

    /**
     * A batch that did not land. Nothing is acknowledged, so the sources re-read it; the
     * publish store replays whatever AMPS never confirmed.
     */
    private void failed(List<MessageContext> batch, String reason) {
        long count = failedBatches.incrementAndGet();
        log.warn("[{}] batch of {} not acknowledged ({} failed batch(es) so far): {}",
                connectorName, batch.size(), count, reason);
    }

    /** When the records are acknowledged to their sources. */
    public AmpsTargetProperties.AckMode ackMode() {
        return ackMode;
    }

    /** The tracker the persisted acks arrive at; in FLUSH mode it only counts failed writes. */
    public PersistedAckTracker tracker() {
        return tracker;
    }

    /**
     * Records AMPS acknowledged as persisted: in batches that flushed, or -- in PERSISTED mode
     * -- one by one as the acks arrived.
     */
    public long publishedMessages() {
        return ackMode == AmpsTargetProperties.AckMode.PERSISTED
                ? tracker.persisted()
                : publishedMessages.get();
    }

    /** Batches that reached AMPS: flushed, or issued in full in PERSISTED mode. */
    public long publishedBatches() {
        return publishedBatches.get();
    }

    /**
     * Batches that did not land: a flush that failed or timed out, or a command that threw.
     * Their records -- the ones not issued, in PERSISTED mode -- stay unacknowledged.
     */
    public long failedBatches() {
        return failedBatches.get();
    }

    /** Records issued and not yet acknowledged as persisted; always {@code 0} in FLUSH mode. */
    public long pending() {
        return tracker.pending();
    }

    /** Writes the server refused, other than duplicates; acknowledged to their sources anyway. */
    public long rejectedWrites() {
        return tracker.rejected();
    }

    /** PERSISTED mode: flushes forced by more than {@code max-pending} records waiting. */
    public long backpressureFlushes() {
        return backpressureFlushes.get();
    }

    /** PERSISTED mode: back-pressure flushes that did not complete within the timeout. */
    public long flushTimeouts() {
        return flushTimeouts.get();
    }
}
