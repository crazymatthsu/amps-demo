package com.demo.amps.qfj2.amps;

/**
 * What an AMPS destination needs from a connection: publish, and optionally
 * wait until the server has it. An interface so a test can record instead
 * of connecting, and so the reconnect policy lives in one place.
 */
public interface AmpsPublisher extends AutoCloseable {

    /**
     * Publishes {@code payload} to {@code topic}. With {@code flush}, returns
     * only once the server has processed it. Throws if it could not be sent
     * or acknowledged.
     */
    void publish(String topic, String payload, boolean flush) throws Exception;

    @Override
    default void close() {
    }
}
