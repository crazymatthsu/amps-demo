package com.demo.amps.connectors.amps;

import com.demo.amps.connectors.runtime.PublishRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes one batch: every command in order, then <em>one</em> flush, then the
 * acknowledgments.
 *
 * <p>The flush is what costs, not the publish, so batching is about amortising exactly that:
 * a hundred records and one round trip to find out they are all persisted. The commands are
 * issued in the order the pipeline produced them, upserts and deletes together, because
 * regrouping them would resurrect a record that was deleted and re-added inside one batch.
 *
 * <p>Acknowledgment is all-or-nothing and deliberately one-directional. A successful flush
 * acknowledges every record in the batch -- committing a Kafka offset, persisting a JDBC
 * watermark -- and anything else acknowledges none of them. Nothing is lost by that: the
 * client's publish store still replays what AMPS never acknowledged, and the sources re-read
 * from their last committed position, so the failure mode is duplicates rather than gaps.
 * That is the at-least-once contract, and this method is where it is kept.
 *
 * <p>Nothing escapes: this runs on a timer thread as often as on the source's, and an
 * exception out of a timer-triggered release would kill the scheduler for every connector in
 * the application.
 */
public final class BatchPublisher {

    private static final Logger log = LoggerFactory.getLogger(BatchPublisher.class);

    private final AmpsPublisher publisher;
    private final String connectorName;
    private final Duration flushTimeout;

    private final AtomicLong publishedMessages = new AtomicLong();
    private final AtomicLong publishedBatches = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();

    /**
     * @param publisher the connector's AMPS connection
     * @param connectorName the connector's name, for the log lines
     * @param flushTimeout how long the batch waits for its publishes to be persisted
     */
    public BatchPublisher(AmpsPublisher publisher, String connectorName, Duration flushTimeout) {
        this.publisher = publisher;
        this.connectorName = connectorName;
        this.flushTimeout = flushTimeout;
    }

    /**
     * Issue a batch and, if it lands, acknowledge it.
     *
     * @param batch the requests, in the order they were produced
     */
    public void publish(List<PublishRequest> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            for (PublishRequest request : batch) {
                issue(request);
            }
            if (!publisher.flush(flushTimeout)) {
                failed(batch, "flush did not complete within " + flushTimeout);
                return;
            }
        } catch (RuntimeException e) {
            failed(batch, e.toString());
            return;
        }
        for (PublishRequest request : batch) {
            request.record().acknowledge();
        }
        publishedMessages.addAndGet(batch.size());
        publishedBatches.incrementAndGet();
        log.debug("[{}] published a batch of {}", connectorName, batch.size());
    }

    private void issue(PublishRequest request) {
        switch (request.command()) {
            case PUBLISH -> publisher.publish(request.topic(), request.data(), request.sowKey());
            case DELTA_PUBLISH ->
                    publisher.deltaPublish(request.topic(), request.data(), request.sowKey());
            case SOW_DELETE -> {
                if (request.deleteFilter() != null) {
                    publisher.sowDeleteByFilter(request.topic(), request.deleteFilter());
                } else {
                    publisher.sowDeleteByKey(request.topic(), request.sowKey());
                }
            }
        }
    }

    /**
     * A batch that did not land. Nothing is acknowledged, so the sources re-read it; the
     * publish store replays whatever AMPS never confirmed.
     */
    private void failed(List<PublishRequest> batch, String reason) {
        long count = failedBatches.incrementAndGet();
        log.warn("[{}] batch of {} not acknowledged ({} failed batch(es) so far): {}",
                connectorName, batch.size(), count, reason);
    }

    /** Records published in batches that were acknowledged as persisted. */
    public long publishedMessages() {
        return publishedMessages.get();
    }

    /** Batches that reached AMPS and flushed. */
    public long publishedBatches() {
        return publishedBatches.get();
    }

    /** Batches whose flush failed or timed out; their records stay unacknowledged. */
    public long failedBatches() {
        return failedBatches.get();
    }
}
