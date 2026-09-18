package com.demo.amps.qfj2.seqno;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The write-behind thread: takes checkpoints off the FIX session thread and
 * publishes them to the {@link SeqnoReplicator}, newest first and only the
 * newest.
 *
 * <p>QuickFIX/J touches the store on every message in either direction,
 * heartbeats included, and a session under load changes its numbers far
 * faster than a round trip to AMPS. So this keeps <b>one</b> pending
 * checkpoint per session -- a later offer for the same session replaces the
 * earlier one -- and the thread publishes whatever is pending as soon as the
 * previous publish is acknowledged. Under load that is a stream of
 * checkpoints each carrying the numbers current when it was taken; idle, it
 * is one publish per change. The file store has already been written before
 * {@link #offer} is called, so nothing here is on the durability path of the
 * FIX session itself: replication lags the file by at most one in-flight
 * publish, which is the window the recovery policies are written for.
 *
 * <p>A failed publish is retried after a backoff, unless a newer checkpoint
 * for the session has arrived meanwhile (then that one goes instead). A
 * checkpoint offered after {@link #close} has drained the thread is published
 * inline, so the logout that a stopping engine sends still reaches AMPS.
 */
public final class WriteBehindPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WriteBehindPublisher.class);

    /** Counters, for tests and for the shutdown log line. */
    public record Stats(long offered, long published, long coalesced, long failures, int pending, String lastError) {
    }

    private final SeqnoReplicator replicator;
    private final long minIntervalMs;
    private final long retryBackoffMs;
    private final Duration closeTimeout;

    private final Object lock = new Object();
    /** Newest pending checkpoint per session; guarded by {@link #lock}. */
    private final Map<String, SeqnoSnapshot> pending = new LinkedHashMap<>();
    private boolean inFlight;
    private boolean closed;
    private boolean drained;

    private final Thread thread;
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final Map<String, SeqnoSnapshot> lastPublished = new ConcurrentHashMap<>();
    private volatile String lastError;

    /**
     * @param replicator     where checkpoints go
     * @param minInterval    a pause between publishes; zero publishes as
     *                       soon as the previous one is acknowledged
     * @param retryBackoff   the pause after a failed publish
     * @param closeTimeout   how long {@link #close} waits for the thread to drain
     */
    public WriteBehindPublisher(SeqnoReplicator replicator, Duration minInterval, Duration retryBackoff,
                                Duration closeTimeout) {
        this.replicator = replicator;
        this.minIntervalMs = Math.max(0, minInterval.toMillis());
        this.retryBackoffMs = Math.max(1, retryBackoff.toMillis());
        this.closeTimeout = closeTimeout;
        this.thread = new Thread(this::run, "qfj2-seqno-write-behind");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Queues a checkpoint; returns at once. */
    public void offer(SeqnoSnapshot snapshot) {
        synchronized (lock) {
            offered.incrementAndGet();
            if (closed && drained) {
                // The thread is gone. This is a stopping engine recording its
                // logout: publish it here rather than lose the final numbers.
                publishInline(snapshot);
                return;
            }
            if (pending.put(snapshot.sessionId(), snapshot) != null) {
                coalesced.incrementAndGet();
            }
            lock.notifyAll();
        }
    }

    /**
     * Blocks until everything offered so far has been published (or has
     * failed and is waiting to be retried, in which case this keeps waiting).
     *
     * @return false if the timeout elapsed first
     */
    public boolean flush(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (!pending.isEmpty() || inFlight) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            return true;
        }
    }

    public Stats stats() {
        synchronized (lock) {
            return new Stats(offered.get(), published.get(), coalesced.get(), failures.get(),
                    pending.size(), lastError);
        }
    }

    /** The newest checkpoint the replicator has acknowledged for the session. */
    public Optional<SeqnoSnapshot> lastPublished(String sessionId) {
        return Optional.ofNullable(lastPublished.get(sessionId));
    }

    private void run() {
        try {
            while (true) {
                List<SeqnoSnapshot> batch;
                synchronized (lock) {
                    while (pending.isEmpty() && !closed) {
                        lock.wait();
                    }
                    if (pending.isEmpty()) {
                        drained = true;
                        return;
                    }
                    batch = new ArrayList<>(pending.values());
                    pending.clear();
                    inFlight = true;
                }
                try {
                    for (SeqnoSnapshot snapshot : batch) {
                        publishWithRetry(snapshot);
                    }
                } finally {
                    synchronized (lock) {
                        inFlight = false;
                        lock.notifyAll();
                    }
                }
                if (minIntervalMs > 0) {
                    synchronized (lock) {
                        if (!closed) {
                            lock.wait(minIntervalMs);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                drained = true;
                log.warn("write-behind thread interrupted with {} checkpoint(s) unpublished", pending.size());
            }
        }
    }

    private void publishWithRetry(SeqnoSnapshot snapshot) throws InterruptedException {
        try {
            replicator.publish(snapshot);
            published.incrementAndGet();
            lastPublished.put(snapshot.sessionId(), snapshot);
            lastError = null;
            log.debug("replicated {}", snapshot.describe());
        } catch (Exception e) {
            failures.incrementAndGet();
            lastError = e.toString();
            synchronized (lock) {
                if (closed) {
                    log.error("checkpoint {} could not be replicated before shutdown: {}. The file store "
                            + "holds the numbers; AMPS is behind until the next start publishes them.",
                            snapshot.describe(), e.toString());
                    return;
                }
                // Retry, unless a newer checkpoint for the session arrived meanwhile.
                pending.putIfAbsent(snapshot.sessionId(), snapshot);
                log.warn("replicating {} failed: {}; retrying in {} ms", snapshot.describe(), e.toString(),
                        retryBackoffMs);
                lock.wait(retryBackoffMs);
            }
        }
    }

    private void publishInline(SeqnoSnapshot snapshot) {
        try {
            replicator.publish(snapshot);
            published.incrementAndGet();
            lastPublished.put(snapshot.sessionId(), snapshot);
            log.info("replicated after shutdown: {}", snapshot.describe());
        } catch (Exception e) {
            failures.incrementAndGet();
            lastError = e.toString();
            log.error("checkpoint {} offered after shutdown could not be replicated: {}",
                    snapshot.describe(), e.toString());
        }
    }

    /** Drains what is pending (bounded by the close timeout) and stops the thread. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            lock.notifyAll();
        }
        try {
            thread.join(closeTimeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.error("write-behind thread did not drain within {}; {} checkpoint(s) may not be replicated",
                    closeTimeout, stats().pending());
            thread.interrupt();
        } else {
            log.info("write-behind publisher closed: {}", stats());
        }
    }
}
