package com.demo.amps.qfj2.mock;

import com.demo.amps.qfj2.config.QfjProperties;
import com.demo.amps.qfj2.engine.FixSessionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.fix42.ExecutionReport;

/**
 * The venue side's traffic: one execution report per interval on every
 * logged-on session, so the drop-copy consumer has something to copy.
 *
 * <p>Enabled by {@code qfj.mock.enabled=true} (the venue role sets it).
 * ExecIDs carry a per-process run id, so a restarted venue never reuses one
 * and the blotter, keyed on ExecID, shows every report from every run.
 */
@Component
@ConditionalOnProperty(prefix = "qfj.mock", name = "enabled", havingValue = "true")
public class MockExecutionFeed implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MockExecutionFeed.class);

    private final QfjProperties.Mock settings;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qfj2-mock-feed");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<SessionID, ScheduledFuture<?>> running = new ConcurrentHashMap<>();
    private final Map<SessionID, AtomicInteger> sent = new ConcurrentHashMap<>();
    private final String runId = Long.toString(Instant.now().getEpochSecond(), 36).toUpperCase(Locale.ROOT);
    private final AtomicLong sequence = new AtomicLong();

    public MockExecutionFeed(QfjProperties properties) {
        this.settings = properties.mock();
    }

    @EventListener
    public void onSession(FixSessionEvent event) {
        switch (event.kind()) {
            case LOGON -> start(event.sessionId());
            case LOGOUT -> stop(event.sessionId());
        }
    }

    private void start(SessionID sessionId) {
        running.computeIfAbsent(sessionId, id -> {
            log.info("mock feed: one 35=8 every {} ms on {}{}", settings.intervalMs(), id,
                    settings.count() > 0 ? " (" + settings.count() + " in all)" : "");
            return scheduler.scheduleAtFixedRate(() -> tick(id), settings.intervalMs(), settings.intervalMs(),
                    TimeUnit.MILLISECONDS);
        });
    }

    private void stop(SessionID sessionId) {
        ScheduledFuture<?> task = running.remove(sessionId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void tick(SessionID sessionId) {
        try {
            AtomicInteger count = sent.computeIfAbsent(sessionId, id -> new AtomicInteger());
            if (settings.count() > 0 && count.get() >= settings.count()) {
                log.info("mock feed: {} reports sent on {}, stopping", count.get(), sessionId);
                stop(sessionId);
                return;
            }
            long n = sequence.incrementAndGet();
            List<String> symbols = settings.symbols() == null || settings.symbols().isEmpty()
                    ? List.of("AAPL") : settings.symbols();
            ExecutionReport report = ExecutionReports.sample(runId, n, symbols.get((int) (n % symbols.size())));
            if (Session.sendToTarget(report, sessionId)) {
                count.incrementAndGet();
            } else {
                log.warn("mock feed: 35=8 #{} not sent, {} is not logged on", n, sessionId);
            }
        } catch (Exception e) {
            log.warn("mock feed on {} failed: {}", sessionId, e.toString());
        }
    }

    /** Reports sent on the session so far. */
    public int sentCount(SessionID sessionId) {
        AtomicInteger count = sent.get(sessionId);
        return count == null ? 0 : count.get();
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
