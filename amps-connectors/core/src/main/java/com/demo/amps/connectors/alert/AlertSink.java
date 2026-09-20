package com.demo.amps.connectors.alert;

/**
 * One destination for alerts: an AMPS topic, a Kafka topic, a test's list.
 *
 * <p>Sinks are collected, not configured: the {@link AlertManager} takes every
 * {@code AlertSink} bean in the context, so an application adds a destination by declaring
 * one, and {@code :amps-connectors:source-kafka} contributes its Kafka sink the same way it
 * contributes its source factory. Core ships the AMPS one.
 *
 * <p>Every method here is called from the manager's single delivery thread, one alert at a
 * time, so an implementation needs no locking and can hold a plain client. The contract that
 * matters is the other way round: a sink that throws is <em>counted</em> and the alert goes
 * on to the next sink, so a sink should not swallow its own failures to be polite -- the
 * counter is how an operator learns the alerts topic is unreachable. {@link #start()} is the
 * exception: a sink whose endpoint is down at boot should say so and return, because the
 * application is starting with or without an alerts topic.
 */
public interface AlertSink {

    /** A short name for the status line and the failure log, e.g. {@code amps:connectors/alerts}. */
    String name();

    /**
     * Connect, if there is anything to connect to. Called once, before the first
     * {@link #send}; a failure is logged and the sink is kept, so {@link #send} should be
     * prepared to connect lazily.
     *
     * @throws Exception if the endpoint refused; logged, not fatal
     */
    default void start() throws Exception {
    }

    /**
     * Deliver one alert.
     *
     * @param alert the alert, stamped with its timestamp and application
     * @throws Exception if it could not be delivered; counted, and the next sink still runs
     */
    void send(Alert alert) throws Exception;

    /**
     * Called when the queue has just been drained: the moment to wait for acknowledgments
     * for what {@link #send} handed to an asynchronous client, so a burst costs one round
     * trip rather than one per alert.
     *
     * @throws Exception if the acknowledgments did not arrive; counted
     */
    default void flush() throws Exception {
    }

    /** Disconnect. Called once, on shutdown, after the last {@link #flush}. */
    default void close() {
    }
}
