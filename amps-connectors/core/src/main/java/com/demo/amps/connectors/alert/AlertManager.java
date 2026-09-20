package com.demo.amps.connectors.alert;

import com.demo.amps.connectors.config.AlertProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The application's one {@link Alerts}: takes alerts from any thread, delivers them from one
 * of its own -- to the log, and to every {@link AlertSink} -- with a severity floor, repeat
 * suppression and a bounded queue in between.
 *
 * <p>The design follows from where {@link #raise} is called: on a source's reader thread, in
 * the middle of a record, often for the same reason a thousand times in a row. So the caller's
 * thread does only what is cheap and bounded -- a severity compare, a map update, a queue
 * offer -- and never a publish. Everything with a network in it happens on {@code amps-alerts},
 * the single daemon thread that drains the queue, so the alerts topic being slow or down
 * costs alerts and never records.
 *
 * <p><strong>Suppression</strong> is keyed on {@code code|connector}. The first alert with a
 * key opens a window of {@code suppress-repeats}; every alert with the same key inside it is
 * counted and dropped. When the window closes, the <em>last</em> of the repeats is sent with
 * {@code repeats} set to the count, so a reader sees the first symptom promptly and the size
 * of the storm afterwards, and a topic that would have carried ten thousand
 * {@code UNKNOWN_SYMBOL} alerts carries two. The trade is deliberate and worth knowing: a
 * storm across many different symbols collapses too, and the summary carries the last one.
 *
 * <p><strong>The queue</strong> is bounded and drops its oldest entry when full. Oldest
 * rather than newest because the newest alert is the one that says what is happening now;
 * dropped rather than blocking because the thread being blocked would be a source's. Every
 * drop is counted, and the count is on the status line.
 *
 * <p>A sink that throws is counted and skipped for that alert; the other sinks still get it
 * and the thread carries on. Nothing a sink does can stop alerting for the sinks beside it.
 *
 * <p>Earliest in the start order and latest in the stop order (phase {@code MAX - 3000}) so
 * that resources, connectors and command handlers can all raise from their own start and
 * stop; {@link #stop()} drains what is queued (up to two seconds) before closing the sinks.
 */
public final class AlertManager implements Alerts, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AlertManager.class);

    /** How long the delivery thread waits for an alert before checking for expired windows. */
    private static final long POLL_MILLIS = 250L;

    /** How long {@link #stop()} lets the delivery thread finish what is queued. */
    private static final Duration STOP_DRAIN = Duration.ofSeconds(2);

    private final AlertProperties properties;
    private final String application;
    private final List<AlertSink> sinks;
    private final Clock clock;
    private final LinkedBlockingQueue<Alert> queue;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private final AtomicLong raised = new AtomicLong();
    private final AtomicLong filtered = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong sinkFailures = new AtomicLong();

    private volatile boolean running;
    private volatile Thread thread;

    /**
     * @param properties the {@code alerts:} block
     * @param application the name every alert carries; already resolved through the
     *     fallbacks ({@link AlertProperties#applicationName})
     * @param sinks every destination; empty means log only
     * @param clock stamps the alerts and drives the suppression windows
     */
    public AlertManager(
            AlertProperties properties, String application, List<AlertSink> sinks, Clock clock) {
        this.properties = properties;
        this.application = application;
        this.sinks = List.copyOf(sinks);
        this.clock = clock;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, properties.getQueueSize()));
    }

    /** The application name every alert is stamped with. */
    public String application() {
        return application;
    }

    @Override
    public void raise(Alert alert) {
        if (alert == null || !properties.isEnabled()) {
            return;
        }
        raised.incrementAndGet();
        if (alert.severity().compareTo(properties.getMinSeverity()) < 0) {
            filtered.incrementAndGet();
            return;
        }
        Instant now = clock.instant();
        Alert stamped = stamp(alert, now);
        if (suppressed(stamped, now)) {
            suppressed.incrementAndGet();
            return;
        }
        enqueue(stamped);
    }

    private Alert stamp(Alert alert, Instant now) {
        Alert stamped = alert;
        if (stamped.timestamp() == null) {
            stamped = stamped.withTimestamp(now);
        }
        if (stamped.application() == null) {
            stamped = stamped.withApplication(application);
        }
        return stamped;
    }

    /**
     * Whether this alert falls inside an open window for its key. Opening a window and
     * counting a repeat both happen inside the map's per-key update, so a repeat can never
     * be lost to the sweep closing the window at the same moment: whichever runs first, the
     * other sees the result.
     */
    private boolean suppressed(Alert alert, Instant now) {
        Duration length = properties.getSuppressRepeats();
        if (length == null || length.isZero() || length.isNegative()) {
            return false;
        }
        boolean[] opened = new boolean[1];
        Alert[] summary = new Alert[1];
        windows.compute(key(alert), (key, window) -> {
            if (window != null && !window.expired(now)) {
                window.repeat(alert);
                return window;
            }
            if (window != null) {
                // The sweep had not got to this expired window yet; its summary still goes
                // out, ahead of the alert that opens the next one.
                summary[0] = window.summary();
            }
            opened[0] = true;
            return new Window(now.plus(length));
        });
        if (summary[0] != null) {
            enqueue(summary[0]);
        }
        return !opened[0];
    }

    private static String key(Alert alert) {
        return alert.code() + "|" + (alert.connector() == null ? "" : alert.connector());
    }

    /** Offer, and make room by discarding the oldest when there is none. */
    private void enqueue(Alert alert) {
        while (!queue.offer(alert)) {
            if (queue.poll() != null) {
                dropped.incrementAndGet();
            }
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (!properties.isEnabled()) {
            log.info("alerts are disabled");
            return;
        }
        for (AlertSink sink : sinks) {
            try {
                sink.start();
            } catch (Exception e) {
                // Kept, not dropped: the endpoint may be up by the first alert, and send()
                // is expected to connect lazily.
                sinkFailures.incrementAndGet();
                log.warn("alert sink '{}' failed to start, will retry on the first alert: {}",
                        sink.name(), e.toString());
            }
        }
        Thread delivering = new Thread(this::deliver, "amps-alerts");
        delivering.setDaemon(true);
        delivering.start();
        thread = delivering;
        log.info("alerts as '{}': min-severity={} suppress-repeats={} queue-size={} sinks={}",
                application, properties.getMinSeverity(), properties.getSuppressRepeats(),
                Math.max(1, properties.getQueueSize()), sinkNames());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        Thread delivering = thread;
        thread = null;
        if (delivering == null) {
            // Disabled: nothing was started, so there is nothing to drain or close.
            return;
        }
        try {
            delivering.join(STOP_DRAIN.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (delivering.isAlive()) {
            log.warn("alert delivery did not drain within {}; {} alert(s) left behind",
                    STOP_DRAIN, queue.size());
            delivering.interrupt();
        }
        for (AlertSink sink : sinks) {
            try {
                sink.close();
            } catch (RuntimeException e) {
                log.warn("alert sink '{}' failed to close", sink.name(), e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Before the resources and the connectors, which raise from their own start and stop. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 3_000;
    }

    /**
     * The delivery loop: one alert at a time to the log and every sink, a flush when the
     * queue has just emptied, and a sweep of the suppression windows on every pass. Runs
     * until stopped <em>and</em> empty, which is what lets {@link #stop()} drain; the windows
     * still open at that point are closed early, because what they counted is worth saying
     * more than the exact moment they would have closed.
     */
    private void deliver() {
        boolean pendingFlush = false;
        while (running || !queue.isEmpty()) {
            Alert alert;
            try {
                alert = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (alert != null) {
                send(alert);
                pendingFlush = true;
            }
            if (pendingFlush && queue.isEmpty()) {
                flushSinks();
                pendingFlush = false;
            }
            sweep();
        }
        boolean closedAny = false;
        for (Map.Entry<String, Window> entry : windows.entrySet()) {
            if (windows.remove(entry.getKey(), entry.getValue())) {
                Alert summary = entry.getValue().summary();
                if (summary != null) {
                    send(summary);
                    closedAny = true;
                }
            }
        }
        if (closedAny) {
            flushSinks();
        }
    }

    private void send(Alert alert) {
        switch (alert.severity()) {
            case INFO -> log.info("alert {}", alert.summary());
            case WARN -> log.warn("alert {}", alert.summary());
            case ERROR -> log.error("alert {}", alert.summary());
        }
        for (AlertSink sink : sinks) {
            try {
                sink.send(alert);
            } catch (Exception e) {
                sinkFailures.incrementAndGet();
                log.warn("alert sink '{}' failed to send {}: {}",
                        sink.name(), alert.code(), e.toString());
            }
        }
        sent.incrementAndGet();
    }

    private void flushSinks() {
        for (AlertSink sink : sinks) {
            try {
                sink.flush();
            } catch (Exception e) {
                sinkFailures.incrementAndGet();
                log.warn("alert sink '{}' failed to flush: {}", sink.name(), e.toString());
            }
        }
    }

    /** Close every expired window, sending its summary when it collapsed anything. */
    private void sweep() {
        if (windows.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<Alert> summaries = new ArrayList<>();
        for (Map.Entry<String, Window> entry : windows.entrySet()) {
            Window window = entry.getValue();
            // Conditional remove: if a raise replaced the window meanwhile, that raise has
            // already queued this window's summary and this pass must not queue it twice.
            if (window.expired(now) && windows.remove(entry.getKey(), window)) {
                Alert summary = window.summary();
                if (summary != null) {
                    summaries.add(summary);
                }
            }
        }
        summaries.forEach(this::enqueue);
    }

    /** Alerts handed to {@link #raise}, whatever became of them. */
    public long raised() {
        return raised.get();
    }

    /** Alerts below {@code min-severity}, dropped where they were raised. */
    public long filtered() {
        return filtered.get();
    }

    /** Alerts inside an open window, counted into a summary instead of sent. */
    public long suppressed() {
        return suppressed.get();
    }

    /** Alerts discarded from a full queue, oldest first. */
    public long dropped() {
        return dropped.get();
    }

    /** Alerts delivered to the log and offered to every sink. */
    public long sent() {
        return sent.get();
    }

    /** Sink calls -- start, send or flush -- that threw. */
    public long sinkFailures() {
        return sinkFailures.get();
    }

    /** Alerts waiting for the delivery thread right now. */
    public int queued() {
        return queue.size();
    }

    /** The names of the sinks, for the status line. */
    public List<String> sinkNames() {
        return sinks.stream().map(AlertSink::name).toList();
    }

    /** One line for the status log. */
    public String status() {
        return String.format(
                "alerts raised=%d sent=%d suppressed=%d filtered=%d dropped=%d "
                        + "sink-failures=%d queued=%d sinks=%s",
                raised(), sent(), suppressed(), filtered(), dropped(), sinkFailures(),
                queued(), sinkNames());
    }

    /**
     * One open suppression window: when it closes, how many repeats it swallowed, and the
     * last of them -- the one the summary is built from.
     */
    private static final class Window {

        private final Instant closesAt;
        private final AtomicInteger repeats = new AtomicInteger();
        private volatile Alert last;

        Window(Instant closesAt) {
            this.closesAt = closesAt;
        }

        boolean expired(Instant now) {
            return !now.isBefore(closesAt);
        }

        void repeat(Alert alert) {
            last = alert;
            repeats.incrementAndGet();
        }

        /** The last repeat, saying how many there were; {@code null} if there were none. */
        Alert summary() {
            int count = repeats.get();
            Alert alert = last;
            return count == 0 || alert == null ? null : alert.withRepeats(count);
        }
    }
}
