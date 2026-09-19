package com.demo.amps.connectors.source;

/**
 * What a source wants done once one of its records has actually reached AMPS.
 *
 * <p>The delivery guarantee is <em>at-least-once</em>, and this is the whole of it: the batch
 * publisher calls {@link #acknowledge()} on every record of a batch after its {@code flush()}
 * has been acknowledged as persisted, and a failed flush leaves them unacknowledged so the
 * source re-reads them. Kafka commits offsets with it; an incremental JDBC poll persists its
 * watermark with it. A source that cannot rewind (TCP, Hazelcast) attaches none.
 *
 * <p>Invoked on the publishing thread, so an implementation must be cheap and must not throw.
 */
@FunctionalInterface
public interface Acknowledgment {

    /** The record, and everything before it from this source, has been published. */
    void acknowledge();
}
