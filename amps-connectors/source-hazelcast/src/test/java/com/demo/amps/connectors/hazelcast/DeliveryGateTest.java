package com.demo.amps.connectors.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.source.RecordHandler;
import com.demo.amps.connectors.source.InboundRecord;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate on its own, with the threads under the test's control: a delivery that is inside
 * the handler holds {@code awaitIdle} until it leaves, one that arrives after {@code close()}
 * never reaches the handler, and the wait gives up at its deadline rather than forever.
 */
class DeliveryGateTest {

    private final ExecutorService threads = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
    }

    @Test
    @DisplayName("awaitIdle() waits for the delivery inside the handler, and it is not interrupted")
    void awaitIdleWaitsForTheDeliveryInFlight() throws Exception {
        DeliveryGate gate = new DeliveryGate("gated");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        List<InboundRecord> delivered = new CopyOnWriteArrayList<>();
        RecordHandler guarded = gate.guard(record -> {
            entered.countDown();
            try {
                // What the aggregator does with the delivering thread: an interruptible
                // wait for a lock another thread holds.
                released.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new IllegalStateException(e);
            }
            delivered.add(record);
        });

        threads.submit(() -> guarded.onRecord(InboundRecord.of("{}", "K-1")));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.inFlight()).isEqualTo(1);

        gate.close();
        Future<Boolean> idle = threads.submit(
                () -> gate.awaitIdle(System.currentTimeMillis() + 5_000));
        assertThatThrownBy(() -> idle.get(300, TimeUnit.MILLISECONDS))
                .as("awaitIdle() is still waiting for the blocked delivery")
                .isInstanceOf(TimeoutException.class);

        released.countDown();
        assertThat(idle.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isFalse();
        assertThat(delivered).extracting(InboundRecord::key).containsExactly("K-1");
        assertThat(gate.inFlight()).isZero();
    }

    @Test
    @DisplayName("a delivery arriving after close() is refused, and never counted as in flight")
    void deliveriesAfterCloseAreRefused() {
        DeliveryGate gate = new DeliveryGate("gated");
        List<InboundRecord> delivered = new CopyOnWriteArrayList<>();
        RecordHandler guarded = gate.guard(delivered::add);

        guarded.onRecord(InboundRecord.of("{}", "before"));
        gate.close();
        guarded.onRecord(InboundRecord.of("{}", "after"));
        guarded.onBatch(List.of(InboundRecord.of("{}", "after-2")));

        assertThat(delivered).extracting(InboundRecord::key).containsExactly("before");
        assertThat(gate.inFlight()).isZero();
        assertThat(gate.awaitIdle(System.currentTimeMillis() + 1_000)).isTrue();
    }

    @Test
    @DisplayName("awaitIdle() gives up at its deadline while a delivery is still inside the handler")
    void awaitIdleGivesUpAtTheDeadline() throws Exception {
        DeliveryGate gate = new DeliveryGate("gated");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        RecordHandler guarded = gate.guard(record -> {
            entered.countDown();
            try {
                released.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        threads.submit(() -> guarded.onRecord(InboundRecord.of("{}", "stuck")));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.currentTimeMillis();
        assertThat(gate.awaitIdle(started + 100)).isFalse();
        assertThat(System.currentTimeMillis() - started).isLessThan(2_000);
        assertThat(gate.inFlight()).isEqualTo(1);

        released.countDown();
        assertThat(gate.awaitIdle(System.currentTimeMillis() + 5_000)).isTrue();
    }

    @Test
    @DisplayName("a handler that throws still leaves the gate, so close() does not wait for a failure")
    void aThrowingHandlerStillExits() {
        DeliveryGate gate = new DeliveryGate("gated");
        RecordHandler guarded = gate.guard(record -> {
            throw new IllegalStateException("rejected");
        });

        assertThatThrownBy(() -> guarded.onRecord(InboundRecord.of("{}", "bad")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(gate.inFlight()).isZero();
    }
}
