package com.demo.amps.connectors.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.config.ConnectorProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle of the in-process generator, and one case in particular: what {@code close()}
 * does to a tick that is already inside the handler.
 *
 * <p>The handler runs the pipeline, and the pipeline ends in the aggregator's
 * {@code lockInterruptibly()}. A close that interrupts the tick thread there loses the record
 * -- it never joins the forced release the connector performs right after the source closes
 * -- so the handler here blocks on an interruptible wait of the same shape and the test
 * asserts the record comes out the far side rather than an {@link InterruptedException}.
 */
class SimulatedSourceTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);

    private final ExecutorService closer = Executors.newSingleThreadExecutor();
    private SimulatedSource source;

    @AfterEach
    void tearDown() {
        if (source != null) {
            source.close();
        }
        closer.shutdownNow();
    }

    @Test
    @DisplayName("close() waits for the tick inside the handler instead of interrupting it")
    void closeLetsTheInFlightTickFinish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        List<InboundRecord> delivered = new CopyOnWriteArrayList<>();

        source = new SimulatedSource(fastTicking("sim-close"));
        source.start(record -> {
            entered.countDown();
            try {
                // What the aggregator does with the tick thread: an interruptible wait for
                // a lock another thread holds.
                if (!released.await(BUDGET.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the handler");
                }
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new IllegalStateException("interrupted inside the handler", e);
            }
            delivered.add(record);
        });
        assertThat(entered.await(BUDGET.toSeconds(), TimeUnit.SECONDS))
                .as("the first tick reached the handler")
                .isTrue();

        // Close while the handler is blocked, the way Connector.stop() does on shutdown.
        Future<?> closing = closer.submit(source::close);

        // It reports closed as soon as it starts -- nothing new starts -- but it has not
        // RETURNED: the tick that is in the handler is still in the handler.
        Awaitility.await().atMost(BUDGET).until(() -> !source.isConnected());
        assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS))
                .as("close() is still waiting for the blocked tick")
                .isInstanceOf(TimeoutException.class);
        assertThat(interrupted).as("the blocked tick was not interrupted").isFalse();
        assertThat(delivered).isEmpty();

        // Let the handler go: close() returns, and the record it was carrying is delivered.
        released.countDown();
        closing.get(BUDGET.toSeconds(), TimeUnit.SECONDS);
        assertThat(interrupted).as("the tick was never interrupted").isFalse();
        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).key()).isEqualTo("K-1");
        // Text at the tick's position, with nothing to acknowledge: the generator's shape.
        assertThat(delivered.get(0).seqno()).isEqualTo(1L);
        assertThat(delivered.get(0).type()).isEqualTo(PayloadType.UNSET);
        assertThat(delivered.get(0).acknowledger()).isSameAs(Acknowledger.NONE);

        // And no tick starts after close: the generator stays at one record.
        Awaitility.await().during(Duration.ofMillis(200)).atMost(BUDGET)
                .untilAsserted(() -> assertThat(delivered).hasSize(1));
    }

    @Test
    @DisplayName("close() from inside the handler returns instead of waiting for itself")
    void closeFromTheTickThreadDoesNotDeadlock() throws Exception {
        AtomicReference<Duration> closeTook = new AtomicReference<>();
        List<InboundRecord> delivered = new CopyOnWriteArrayList<>();

        source = new SimulatedSource(fastTicking("sim-self-close"));
        source.start(record -> {
            if (closeTook.get() == null) {
                long started = System.nanoTime();
                source.close();
                closeTook.set(Duration.ofNanos(System.nanoTime() - started));
            }
            delivered.add(record);
        });

        Awaitility.await().atMost(BUDGET).untilAsserted(() -> assertThat(delivered).hasSize(1));
        assertThat(closeTook.get())
                .as("close() did not sit out its own budget waiting for the calling tick")
                .isLessThan(Duration.ofSeconds(1));
        assertThat(source.isConnected()).isFalse();
        Awaitility.await().during(Duration.ofMillis(200)).atMost(BUDGET)
                .untilAsserted(() -> assertThat(delivered).hasSize(1));
    }

    /** A simulated connector whose first tick arrives in a millisecond, not a fifth of a second. */
    private static ConnectorProperties fastTicking(String name) {
        ConnectorProperties connector = TestConnectors.simulated(name);
        connector.getSource().getSimulated().setRate(1_000);
        return connector;
    }
}
