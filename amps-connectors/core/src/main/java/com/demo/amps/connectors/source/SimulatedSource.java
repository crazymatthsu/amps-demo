package com.demo.amps.connectors.source;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.SimulatedProperties;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordSource} that synthesises records in process, with no broker.
 *
 * <p>Selected with {@code source.driver: SIMULATED}, whichever transport block the connector
 * configures. It renders {@code source.simulated.template} at {@code source.simulated.rate}
 * records per second, cycling {@code source.simulated.keys} keys, so the decoder, filter,
 * transforms, key extraction, encoder, batcher and AMPS publisher all run exactly as they
 * would against a real feed. Only the reader thread is fake.
 *
 * <p>Three placeholders are substituted:
 *
 * <ul>
 *   <li>{@code &#123;&#123;key&#125;&#125;} -- {@code K-<n>}, cycling {@code 0 .. keys-1}, so a
 *       keyed SOW topic gets a bounded, repeating key set to converge onto</li>
 *   <li>{@code &#123;&#123;seq&#125;&#125;} -- a running counter, the field a delta publish can
 *       be seen changing</li>
 *   <li>{@code &#123;&#123;ts&#125;&#125;} -- {@link Instant#now()} in ISO-8601</li>
 * </ul>
 *
 * <p>A FIX or NVFIX template is written with {@code |} between its fields, because a literal
 * SOH does not survive a YAML file, an editor or a diff; for those formats the separator is
 * swapped for the connector's own {@code field-separator} on the way out. A JSON or TEXT
 * template is emitted verbatim, pipes and all.
 */
public final class SimulatedSource implements RecordSource {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSource.class);

    /**
     * How long {@link #close()} waits for a tick that is already inside the handler. The same
     * budget the real drivers give their reader threads, and the one {@link RecordSource#close}
     * promises.
     */
    private static final long CLOSE_JOIN_MILLIS = 5_000;

    private final ConnectorProperties connector;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();

    private volatile ScheduledExecutorService scheduler;

    /** The tick thread, so a {@link #close()} arriving on it does not wait for itself. */
    private volatile Thread ticker;

    public SimulatedSource(ConnectorProperties connector) {
        this.connector = connector;
    }

    @Override
    public void start(RecordHandler handler) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        SimulatedProperties simulated = connector.getSource().getSimulated();
        long periodMicros = Math.max(1L, 1_000_000L / simulated.getRate());

        // A daemon thread: a simulated feed must never be the reason the JVM stays up.
        ScheduledExecutorService started = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "amps-sim-" + connector.getName());
            thread.setDaemon(true);
            ticker = thread;
            return thread;
        });
        scheduler = started;
        started.scheduleAtFixedRate(() -> {
            if (!running.get()) {
                return;
            }
            try {
                handler.onRecord(next(simulated));
            } catch (RuntimeException e) {
                // The pipeline rejecting a synthetic record is a configuration bug worth
                // seeing, but it is never a reason to stop generating.
                log.error("[{}] simulated record failed", connector.getName(), e);
            }
        }, periodMicros, periodMicros, TimeUnit.MICROSECONDS);

        log.info("[{}] simulated source started ({} rec/s across {} keys, {} template)",
                connector.getName(), simulated.getRate(), simulated.getKeys(),
                connector.getFormat());
    }

    /** One synthetic record: the template with its placeholders filled in. */
    private InboundRecord next(SimulatedProperties simulated) {
        long seq = sequence.incrementAndGet();
        String key = "K-" + Math.floorMod(seq, simulated.getKeys());
        String payload = simulated.getTemplate()
                .replace("{{key}}", key)
                .replace("{{seq}}", Long.toString(seq))
                .replace("{{ts}}", Instant.now().toString());
        if (connector.getFormat().delimited()) {
            payload = payload.replace('|', connector.getFieldSeparator());
        }
        return InboundRecord.of(payload, key);
    }

    @Override
    public boolean isConnected() {
        return running.get();
    }

    /**
     * Stop ticking and wait for the tick that is already in the handler, if there is one.
     *
     * <p>{@code shutdown()} rather than {@code shutdownNow()}, and the difference is a record.
     * The handler runs the pipeline on the tick thread, and the pipeline ends in the
     * aggregator, which takes its lock with {@code lockInterruptibly()}. A tick interrupted
     * there does not deliver its record and does not throw it back either: the aggregator
     * reports "Interrupted getting lock", the connector counts a source error, and the record
     * misses the forced release the connector performs right after this returns -- one
     * {@code published} short of {@code received} at every shutdown. So no new tick starts
     * ({@code running} is already false, and a shut-down scheduler reschedules nothing), the
     * one in flight is allowed to finish, and only a tick that has not finished within
     * {@value #CLOSE_JOIN_MILLIS}ms -- which means the lock it waits on is held by a release
     * that is itself stuck -- is interrupted.
     */
    @Override
    public void close() {
        running.set(false);
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        if (Thread.currentThread() == ticker) {
            // Closed from inside the handler: the in-flight tick is this call, and it will
            // finish the moment it returns.
            return;
        }
        boolean finished;
        try {
            finished = current.awaitTermination(CLOSE_JOIN_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            log.warn("[{}] simulated tick did not finish within {}ms; interrupting it",
                    connector.getName(), CLOSE_JOIN_MILLIS);
            current.shutdownNow();
        }
    }
}
