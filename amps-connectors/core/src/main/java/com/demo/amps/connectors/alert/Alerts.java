package com.demo.amps.connectors.alert;

/**
 * The one-method seam everything that can go wrong depends on.
 *
 * <p>A connector, a resource, a transform and a command handler all want to say "something
 * is wrong" without knowing whether that ends up on an AMPS topic, a Kafka topic, in a log or
 * nowhere -- and, just as important, without any of them holding a reference to the manager
 * that decides. This interface is that boundary: one method, no configuration, no lifecycle,
 * so a plain unit test can hand a lambda that collects into a list and assert on exactly what
 * was raised.
 *
 * <p>{@link #raise} never blocks and never throws. It runs on the caller's thread -- often a
 * source's reader thread, mid-record -- so the implementation's contract is to take the alert
 * and get out of the way; the {@link AlertManager} queues and delivers on a thread of its own.
 */
@FunctionalInterface
public interface Alerts {

    /**
     * Raise an alert. Returns at once; delivery is somebody else's thread.
     *
     * @param alert what happened; {@code timestamp} and {@code application} may be left
     *     {@code null} for the manager to stamp
     */
    void raise(Alert alert);

    /**
     * An implementation that discards everything, for code that runs where nobody is
     * listening -- a unit test, a tool -- and for the parameter that would otherwise be
     * {@code null}.
     *
     * @return the discarding implementation
     */
    static Alerts none() {
        return alert -> {
        };
    }
}
