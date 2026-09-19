package com.demo.amps.connectors.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/**
 * How many records ride on one AMPS flush.
 *
 * <p>The flush, not the publish, is what costs: a batch issues its publish commands in order
 * and then waits <em>once</em> for everything to be acknowledged as persisted, which is also
 * the moment every record in it is acknowledged back to its source. So these two numbers set
 * the connector's throughput and its worst-case latency at the same time, from the two ends:
 *
 * <ul>
 *   <li>{@link #getMaxMessages()} -- a full batch is published on the source thread, which is
 *       where the back-pressure comes from: a source that outruns AMPS ends up waiting in its
 *       own reader loop rather than growing a queue</li>
 *   <li>{@link #getFlushInterval()} -- a partial batch is published by the scheduler after this
 *       much idle time, so a quiet feed's last record does not sit unsent</li>
 * </ul>
 */
public class BatchProperties {

    /** Publish once this many records have accumulated, without waiting for the flush tick. */
    @Min(1)
    private int maxMessages = 500;

    /** Maximum time a buffered record waits before its partial batch is published anyway. */
    @NotNull
    private Duration flushInterval = Duration.ofMillis(250);

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }
}
