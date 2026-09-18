package com.demo.amps.qfj2.support;

import com.demo.amps.qfj2.seqno.SeqnoReplicator;
import com.demo.amps.qfj2.seqno.SeqnoSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A replicator that is a map: the last checkpoint per session, plus every
 * checkpoint ever published in order. Failures and stalls can be injected
 * so the write-behind thread's retry and coalescing paths are testable.
 */
public final class InMemorySeqnoReplicator implements SeqnoReplicator {

    private final Map<String, SeqnoSnapshot> latest = new ConcurrentHashMap<>();
    private final List<SeqnoSnapshot> history = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresToInject = new AtomicInteger();
    private final AtomicInteger loadFailuresToInject = new AtomicInteger();
    private final AtomicReference<CountDownLatch> gate = new AtomicReference<>();

    @Override
    public void publish(SeqnoSnapshot snapshot) throws Exception {
        CountDownLatch open = gate.get();
        if (open != null) {
            open.await();
        }
        if (failuresToInject.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("injected publish failure");
        }
        latest.put(snapshot.sessionId(), snapshot);
        history.add(snapshot);
    }

    @Override
    public Optional<SeqnoSnapshot> load(String sessionId) {
        if (loadFailuresToInject.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("injected load failure");
        }
        return Optional.ofNullable(latest.get(sessionId));
    }

    /** Seeds a checkpoint as if a previous instance had published it. */
    public void seed(SeqnoSnapshot snapshot) {
        latest.put(snapshot.sessionId(), snapshot);
        history.add(snapshot);
    }

    /** The next {@code count} publishes throw. */
    public void failNextPublishes(int count) {
        failuresToInject.set(count);
    }

    /** The next {@code count} loads throw. */
    public void failNextLoads(int count) {
        loadFailuresToInject.set(count);
    }

    /** Publishes block until {@link #open()} is called. */
    public void stall() {
        gate.set(new CountDownLatch(1));
    }

    public void open() {
        CountDownLatch open = gate.getAndSet(null);
        if (open != null) {
            open.countDown();
        }
    }

    public Optional<SeqnoSnapshot> latest(String sessionId) {
        return Optional.ofNullable(latest.get(sessionId));
    }

    public List<SeqnoSnapshot> history() {
        return List.copyOf(history);
    }

    public int publishedCount() {
        return history.size();
    }

    public void clear() {
        latest.clear();
        history.clear();
    }
}
