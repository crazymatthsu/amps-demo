package com.demo.amps.connectors.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.config.AlertProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The manager on its own: a mutable clock for the suppression windows, recording sinks for
 * what came out, and no Spring anywhere.
 */
class AlertManagerTest {

    private static final Duration PATIENCE = Duration.ofSeconds(5);
    private static final Instant T0 = Instant.parse("2026-09-19T14:00:00Z");

    /** A clock the test moves by hand, so a thirty-second window closes when the test says. */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(T0);

        void advance(Duration by) {
            now.updateAndGet(current -> current.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private final MutableClock clock = new MutableClock();
    private final RecordingAlertSink sink = new RecordingAlertSink();
    private AlertManager manager;

    private static AlertProperties properties() {
        AlertProperties properties = new AlertProperties();
        properties.setSuppressRepeats(Duration.ofSeconds(30));
        return properties;
    }

    private AlertManager start(AlertProperties properties, AlertSink... sinks) {
        manager = new AlertManager(properties, "test-app", List.of(sinks), clock);
        manager.start();
        return manager;
    }

    private static Alert warn(String code, String connector) {
        return Alert.of(Alert.Severity.WARN, code, "something about " + code)
                .withConnector(connector);
    }

    @AfterEach
    void stopManager() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    @DisplayName("an alert is stamped with the clock and the application, logged, and sent to every sink")
    void deliversStampedAlerts() {
        RecordingAlertSink other = new RecordingAlertSink("other");
        start(properties(), sink, other);

        manager.raise(warn("SOURCE_ERROR", "orders"));

        Alert delivered = sink.awaitCode("SOURCE_ERROR");
        assertThat(delivered.timestamp()).isEqualTo(T0);
        assertThat(delivered.application()).isEqualTo("test-app");
        assertThat(delivered.connector()).isEqualTo("orders");
        assertThat(other.awaitCode("SOURCE_ERROR")).isEqualTo(delivered);
        assertThat(sink.startCount()).isEqualTo(1);
        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(manager.sent()).isEqualTo(1);
            // The queue emptied after the burst of one, so each sink was flushed once.
            assertThat(sink.flushCount()).isEqualTo(1);
        });
        assertThat(manager.raised()).isEqualTo(1);
        assertThat(manager.status()).contains("raised=1", "sent=1", "sinks=[recording, other]");
    }

    @Test
    @DisplayName("a timestamp the raiser set is kept; only a missing one is stamped")
    void keepsACallerTimestamp() {
        start(properties(), sink);
        Instant earlier = T0.minusSeconds(60);

        manager.raise(warn("X", null).withTimestamp(earlier).withApplication("elsewhere"));

        Alert delivered = sink.awaitCode("X");
        assertThat(delivered.timestamp()).isEqualTo(earlier);
        assertThat(delivered.application()).isEqualTo("elsewhere");
    }

    @Test
    @DisplayName("min-severity drops what is below it where it is raised")
    void appliesTheSeverityFloor() {
        AlertProperties properties = properties();
        properties.setMinSeverity(Alert.Severity.WARN);
        start(properties, sink);

        manager.raise(Alert.of(Alert.Severity.INFO, "STATUS", "fine"));
        manager.raise(warn("SOURCE_ERROR", "orders"));
        manager.raise(Alert.of(Alert.Severity.ERROR, "PUBLISH_FAILED", "bad"));

        sink.awaitCode("PUBLISH_FAILED");
        assertThat(sink.alerts()).extracting(Alert::code)
                .containsExactly("SOURCE_ERROR", "PUBLISH_FAILED");
        assertThat(manager.raised()).isEqualTo(3);
        assertThat(manager.filtered()).isEqualTo(1);
    }

    @Test
    @DisplayName("repeats of one code from one connector are collapsed until the window closes")
    void suppressesRepeatsAndSummarisesThem() {
        start(properties(), sink);

        manager.raise(warn("UNKNOWN_SYMBOL", "orders").withDetails(java.util.Map.of("symbol", "A")));
        manager.raise(warn("UNKNOWN_SYMBOL", "orders").withDetails(java.util.Map.of("symbol", "B")));
        manager.raise(warn("UNKNOWN_SYMBOL", "orders").withDetails(java.util.Map.of("symbol", "C")));
        // A different connector, and a different code, are each their own window.
        manager.raise(warn("UNKNOWN_SYMBOL", "fills"));
        manager.raise(warn("SOURCE_ERROR", "orders"));

        sink.awaitCode("SOURCE_ERROR");
        Awaitility.await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(sink.alerts()).hasSize(3));
        assertThat(sink.alerts("UNKNOWN_SYMBOL")).extracting(Alert::connector)
                .containsExactly("orders", "fills");
        assertThat(sink.alerts("UNKNOWN_SYMBOL").get(0).details()).containsEntry("symbol", "A");
        assertThat(manager.suppressed()).isEqualTo(2);

        // Thirty seconds later the window closes and the LAST repeat goes out, saying how
        // many it stands for.
        clock.advance(Duration.ofSeconds(31));
        List<Alert> summaries = sink.awaitCode("UNKNOWN_SYMBOL", 3);
        Alert summary = summaries.get(2);
        assertThat(summary.repeats()).isEqualTo(2);
        assertThat(summary.connector()).isEqualTo("orders");
        assertThat(summary.details()).containsEntry("symbol", "C");
        assertThat(summary.summary()).contains("(repeats=2)");

        // The window is gone, so the next one passes straight through again.
        manager.raise(warn("UNKNOWN_SYMBOL", "orders").withDetails(java.util.Map.of("symbol", "D")));
        assertThat(sink.awaitCode("UNKNOWN_SYMBOL", 4).get(3).repeats()).isZero();
    }

    @Test
    @DisplayName("a raise that finds an expired window closes it itself, summary included")
    void aRaiseAfterTheWindowClosesEmitsTheSummaryFirst() {
        start(properties(), sink);

        manager.raise(warn("X", "c"));
        manager.raise(warn("X", "c"));
        sink.awaitCode("X");
        clock.advance(Duration.ofSeconds(31));
        // Raised before the delivery thread's sweep can possibly have run for the new time:
        // the raise itself must roll the window over, and nothing is counted twice.
        manager.raise(warn("X", "c"));

        List<Alert> delivered = sink.awaitCode("X", 3);
        assertThat(delivered).extracting(Alert::repeats).containsExactly(0, 1, 0);
        Awaitility.await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(sink.alerts("X")).hasSize(3));
    }

    @Test
    @DisplayName("suppress-repeats: 0 sends every alert")
    void zeroWindowSendsEverything() {
        AlertProperties properties = properties();
        properties.setSuppressRepeats(Duration.ZERO);
        start(properties, sink);

        for (int i = 0; i < 5; i++) {
            manager.raise(warn("X", "c"));
        }

        assertThat(sink.awaitCode("X", 5)).hasSize(5);
        assertThat(manager.suppressed()).isZero();
    }

    @Test
    @DisplayName("a full queue drops its oldest alert and counts the drop")
    void dropsTheOldestWhenTheQueueIsFull() {
        AlertProperties properties = properties();
        properties.setSuppressRepeats(Duration.ZERO);
        properties.setQueueSize(2);
        // Not started yet: nothing drains, so the queue's behaviour is exact.
        manager = new AlertManager(properties, "test-app", List.of(sink), clock);

        for (int i = 1; i <= 5; i++) {
            manager.raise(Alert.of(Alert.Severity.WARN, "X", "alert " + i));
        }
        assertThat(manager.dropped()).isEqualTo(3);
        assertThat(manager.queued()).isEqualTo(2);

        manager.start();
        assertThat(sink.awaitCode("X", 2)).extracting(Alert::message)
                .containsExactly("alert 4", "alert 5");
    }

    @Test
    @DisplayName("a sink that throws is counted, and the other sink still gets the alert")
    void isolatesAFailingSink() {
        RecordingAlertSink broken = new RecordingAlertSink("broken")
                .failWith(new IllegalStateException("topic is gone"));
        start(properties(), broken, sink);

        manager.raise(warn("X", "c"));
        manager.raise(warn("Y", "c"));

        assertThat(sink.awaitCode("Y")).isNotNull();
        assertThat(sink.alerts()).extracting(Alert::code).containsExactly("X", "Y");
        assertThat(broken.alerts()).isEmpty();
        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(manager.sinkFailures()).isEqualTo(2);
            assertThat(manager.sent()).isEqualTo(2);
        });
        assertThat(manager.status()).contains("sink-failures=2");
    }

    @Test
    @DisplayName("stop drains what is queued, closes an open window, and closes the sinks")
    void stopDrains() throws InterruptedException {
        AlertProperties properties = properties();
        CountDownLatch release = new CountDownLatch(1);
        AlertSink slow = new AlertSink() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public void send(Alert alert) throws Exception {
                // The first alert blocks delivery until the test lets go, so everything
                // raised meanwhile is still queued when stop() is called.
                release.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
            }
        };
        start(properties, slow, sink);
        for (int i = 0; i < 5; i++) {
            manager.raise(Alert.of(Alert.Severity.WARN, "X" + i, "m"));
        }
        // A repeat with its window still open at shutdown: the count must not be lost.
        manager.raise(warn("R", "c"));
        manager.raise(warn("R", "c"));
        manager.raise(warn("R", "c"));

        Thread stopping = new Thread(() -> manager.stop(), "stopper");
        stopping.start();
        release.countDown();
        stopping.join(PATIENCE.toMillis());

        assertThat(stopping.isAlive()).isFalse();
        assertThat(manager.isRunning()).isFalse();
        assertThat(sink.alerts()).extracting(Alert::code)
                .containsExactly("X0", "X1", "X2", "X3", "X4", "R", "R");
        assertThat(sink.alerts("R").get(1).repeats()).isEqualTo(2);
        assertThat(sink.closeCount()).isEqualTo(1);
        assertThat(manager.queued()).isZero();
    }

    @Test
    @DisplayName("disabled raises nothing, and a sink that fails to start is kept")
    void disabledAndStartFailures() {
        AlertProperties disabled = properties();
        disabled.setEnabled(false);
        AlertManager off = new AlertManager(disabled, "test-app", List.of(sink), clock);
        off.start();
        off.raise(warn("X", "c"));
        off.stop();
        assertThat(sink.alerts()).isEmpty();
        assertThat(off.raised()).isZero();
        assertThat(sink.startCount()).isZero();

        AlertSink refusing = new AlertSink() {
            @Override
            public String name() {
                return "refusing";
            }

            @Override
            public void start() throws Exception {
                throw new java.io.IOException("connection refused");
            }

            @Override
            public void send(Alert alert) {
                sink.send(alert);
            }
        };
        start(properties(), refusing);
        manager.raise(warn("X", "c"));
        assertThat(sink.awaitCode("X")).isNotNull();
        assertThat(manager.sinkFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("the manager runs first and stops last")
    void lifecycleOrder() {
        start(properties(), sink);
        assertThat(manager.getPhase()).isEqualTo(Integer.MAX_VALUE - 3_000);
        assertThat(manager.isRunning()).isTrue();
        assertThat(manager.application()).isEqualTo("test-app");
    }
}
