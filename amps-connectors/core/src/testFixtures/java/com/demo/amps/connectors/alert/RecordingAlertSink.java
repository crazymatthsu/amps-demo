package com.demo.amps.connectors.alert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;

/**
 * An {@link AlertSink} that keeps every alert it is given, for the assertions afterwards.
 *
 * <p>Declared as a bean it stands in for the AMPS or Kafka topic, so a test of a whole
 * application can say "and this raised {@code UNKNOWN_SYMBOL}" without a broker; handed to
 * an {@link AlertManager} directly it is how the manager's own behaviour -- the floor, the
 * suppression, the queue -- is observed. Delivery happens on the manager's thread, which is
 * why {@link #awaitCode} exists: the alert is there soon, not now.
 *
 * <p>{@link #failWith} makes every {@link #send} throw, for the case a sink exists to prove:
 * that one broken destination costs nothing but a counter.
 */
public class RecordingAlertSink implements AlertSink {

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private final String name;
    private final List<Alert> alerts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    private volatile RuntimeException failure;

    public RecordingAlertSink() {
        this("recording");
    }

    /** A named one, for a test with two sinks that need telling apart. */
    public RecordingAlertSink(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void start() {
        starts.incrementAndGet();
    }

    @Override
    public void send(Alert alert) {
        RuntimeException failing = failure;
        if (failing != null) {
            throw failing;
        }
        alerts.add(alert);
    }

    @Override
    public void flush() {
        flushes.incrementAndGet();
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }

    /** Make every send from now on throw {@code failure}; {@code null} restores it. */
    public RecordingAlertSink failWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    /** Every alert received so far, in order. A copy, so an assertion cannot race delivery. */
    public List<Alert> alerts() {
        synchronized (alerts) {
            return List.copyOf(alerts);
        }
    }

    /** The alerts with one code, in order. */
    public List<Alert> alerts(String code) {
        return alerts().stream().filter(alert -> alert.code().equals(code)).toList();
    }

    /**
     * Wait for the first alert with {@code code} to arrive.
     *
     * @param code the alert code
     * @return the first alert with that code
     * @throws org.awaitility.core.ConditionTimeoutException if none arrives in time
     */
    public Alert awaitCode(String code) {
        Awaitility.await("alert " + code).atMost(PATIENCE)
                .until(() -> !alerts(code).isEmpty());
        return alerts(code).get(0);
    }

    /**
     * Wait until at least {@code count} alerts with {@code code} have arrived.
     *
     * @param code the alert code
     * @param count how many
     * @return the alerts with that code, at least {@code count} of them
     */
    public List<Alert> awaitCode(String code, int count) {
        Awaitility.await(count + " x alert " + code).atMost(PATIENCE)
                .until(() -> alerts(code).size() >= count);
        return alerts(code);
    }

    /** How many times the manager started this sink; once is the contract. */
    public int startCount() {
        return starts.get();
    }

    /** How many times the manager flushed this sink: once per drained burst. */
    public int flushCount() {
        return flushes.get();
    }

    /** How many times the manager closed this sink; once is the contract. */
    public int closeCount() {
        return closes.get();
    }

    /** Forget everything recorded, keeping the failure setting and the counters. */
    public void clear() {
        alerts.clear();
    }
}
