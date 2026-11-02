package com.demo.amps.fix42.state.publish;

import com.demo.amps.fix42.state.config.Mode;
import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.machine.OrderState;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.Outcome;
import com.demo.amps.fix42.state.machine.RecordWriter;
import com.demo.amps.fix42.state.spi.Decision;
import com.demo.amps.fix42.state.spi.MessageSink;
import com.demo.amps.fix42.state.spi.OrderStateHook;
import com.demo.amps.fix42.state.spi.OrderStateView;
import com.demo.amps.fix42.state.spi.RecordFields;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the feed to the blotter: for each message, {@code plan} → the
 * blotter publish → the audit publishes → {@code commit}, in that order and
 * no other (docs/02 §5, docs/04 §6).
 *
 * <ul>
 *   <li>the audit publishes come after {@code plan} because their 13011
 *       trailer needs the resolved key, and after the blotter publish because
 *       that is the one that must not be lost; the audit copies are keyed, so
 *       a redelivery after a failure between the two overwrites them
 *       idempotently;</li>
 *   <li>{@code commit} comes last: a publish that throws propagates before it,
 *       so the cache is unchanged and the message can be redelivered, and
 *       {@link #onMessage} returns -- letting the FIX engine acknowledge --
 *       only after every publish is in the client's store.</li>
 * </ul>
 *
 * <p>The {@link RepairQueue} the sink's failure callback fills (over AMPS,
 * {@code amps.AmpsFailedWriteHandler}) is drained here,
 * on the publishing thread, before each message, on an idle timer and at
 * {@link #close()}: a refused delta is repaired by a delta of every cached
 * field, never a {@code publish} that would erase the D's terms the cache does
 * not hold; a refused creation is re-sent whole; a refused audit publish goes
 * to the lenient catch-all (S2).
 *
 * <p>Every public method is synchronized: the feed's thread and the idle timer
 * take turns, and the machine never sees two at once (docs/02 §7).
 */
public final class OrderStatePublisher implements MessageSink {

    private static final Logger log = LoggerFactory.getLogger(OrderStatePublisher.class);

    /** A refused payload that keeps being refused is not retried for ever. */
    private static final int MAX_REPAIR_ATTEMPTS = 3;
    /** Enough to cover the store's window several times over; older entries go first. */
    private static final int SENT_HISTORY = 65_536;

    private final OrderStateMachine machine;
    private final RecordWriter writer;
    private final AuditRouter router;
    private final PublishSink sink;
    private final RepairQueue repairs;
    private final List<OrderStateHook> hooks;
    private final Mode mode;
    private final String blotter;
    private final String malformedTopic;
    private final long timeoutMs;

    private final FixFrame frame = new FixFrame();
    private final FixWriter source = new FixWriter(512);
    private final FixWriter out = new FixWriter(1024);
    private final RecordFields extra = new RecordFields();

    /** Blotter sequence → key, for a refused payload that does not carry its own 13011. */
    private final Map<Long, String> sentKeys = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > SENT_HISTORY;
        }
    };
    private final Map<String, Integer> repairAttempts = new HashMap<>();
    private final Map<String, Long> unroutedByType = new TreeMap<>();
    private final Counters counters = new Counters();
    private ScheduledExecutorService idleDrain;

    public OrderStatePublisher(OrderStateMachine machine, RecordWriter writer, AuditRouter router, PublishSink sink,
                               RepairQueue repairs, List<OrderStateHook> hooks, PublisherOptions options) {
        this.machine = machine;
        this.writer = writer;
        this.router = router;
        this.sink = sink;
        this.repairs = repairs;
        this.hooks = List.copyOf(hooks);
        this.mode = options.mode();
        this.blotter = options.blotter();
        this.malformedTopic = options.malformedTopic();
        this.timeoutMs = options.flushTimeoutMs();
    }

    // ---- the feed ------------------------------------------------------------------

    /**
     * One message as received. Bytes that do not parse raise in strict mode,
     * and in lenient mode are wrapped on {@code fix42/malformed} (docs/03 §9).
     */
    @Override
    public synchronized void onMessage(byte[] payload, int offset, int length) throws Exception {
        FixFrame message;
        try {
            message = frame.wrap(payload, offset, length);
        } catch (IllegalArgumentException e) {
            if (mode == Mode.STRICT || malformedTopic == null) {
                throw new UnroutedMessageException("unparseable FIX (strict mode)", e);
            }
            AuditRouter.Publish wrapped = AuditRouter.malformed(malformedTopic, payload, offset, length,
                    String.valueOf(e.getMessage()));
            sink.publish(wrapped.topic(), wrapped.payload(), 0, wrapped.length());
            counters.malformed.incrementAndGet();
            log.warn("unparseable message recorded on {}: {}", malformedTopic, e.getMessage());
            return;
        }
        send(message);
    }

    /** The readable path: one message, rendered and sent. */
    public synchronized Outcome send(FixMessage message) throws Exception {
        source.clear();
        message.writeTo(source);
        return send(new FixFrame().wrap(source));
    }

    /**
     * One indexed message: plan, publish, commit.
     *
     * @return the machine's outcome, or null for a route that does not feed the machine
     */
    public synchronized Outcome send(FixFrame message) throws Exception {
        drainRepairs();
        String msgType = message.value(FixTags.MSG_TYPE);
        Route route = router.match(msgType);

        Outcome outcome = null;
        FixFrame applied = message;
        String key;
        extra.clear();
        if (!route.applyState()) {
            key = machine.resolveKey(message);
            unroutedByType.merge(msgType.isEmpty() ? "?" : msgType, 1L, Long::sum);
        } else {
            String override = null;
            for (OrderStateHook hook : hooks) {
                override = hook.resolveKey(message, machine.index());
                if (override != null) {
                    break;
                }
            }
            String beforeKey = override != null ? override : machine.resolveKey(message);
            OrderStateView before = beforeKey == null ? null : machine.state(beforeKey);
            Decision decision = Decision.proceed();
            for (OrderStateHook hook : hooks) {
                Decision next = hook.beforeApply(applied, before);
                if (next.ignored()) {
                    decision = next;
                    break;
                }
                if (next.replacement() != null) {
                    applied = next.replacement();
                }
            }
            outcome = decision.ignored() ? machine.planVeto(message, override) : machine.plan(applied, override);
            OrderState after = outcome.state();
            for (OrderStateHook hook : hooks) {
                switch (outcome.kind()) {
                    case CREATED -> hook.onCreate(applied, after, extra);
                    case CHANGED -> hook.afterApply(applied, before, after, outcome, extra);
                    case IGNORED -> hook.onIgnored(applied, after != null ? after : before, outcome.reason());
                }
            }
            key = outcome.key();
        }
        AuditRouter.Plan plan = router.plan(message, route, key);

        if (outcome != null) {
            out.clear();
            if (outcome.publish() != Outcome.Publish.NONE && (key == null || key.isEmpty())) {
                // AMPS 5.3.5.135 acknowledges a blotter payload without its key
                // and silently discards it, and stores one with an empty key
                // under that empty key (docs/11 F5): never send either.
                throw new IllegalStateException("a blotter publish without a key: " + outcome);
            }
            if (writer.write(outcome, applied, out)) {
                extra.writeTo(out);
                publishBlotter(outcome.key(), outcome.publish() == Outcome.Publish.FULL, out.buffer(), out.size());
            }
        }
        for (AuditRouter.Publish publish : plan.publishes()) {
            sink.publish(publish.topic(), publish.payload(), 0, publish.length());
            counters.auditPublishes.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("publish       {} {}", publish.topic(), publish.printable());
            }
        }
        if (plan.keyTagConflict()) {
            counters.auditKeyTagConflict.incrementAndGet();
        }
        if (outcome != null) {
            machine.commit(outcome);
        }
        return outcome;
    }

    private void publishBlotter(String key, boolean full, byte[] payload, int length) throws Exception {
        long sequence = full ? sink.publish(blotter, payload, 0, length)
                : sink.deltaPublish(blotter, payload, 0, length);
        if (sequence > 0) {
            sentKeys.put(sequence, key);
        }
        (full ? counters.fullPublishes : counters.deltaPublishes).incrementAndGet();
        counters.bytesPublished.addAndGet(length);
        // Guarded: the payload's text is an ARGUMENT, built even when the
        // level discards it. WARN turns the per-publish line off for free.
        if (log.isInfoEnabled()) {
            log.info("{} {} {}", full ? "publish      " : "delta_publish", blotter,
                    new String(payload, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\u0001', '|'));
        }
    }

    // ---- repairs ---------------------------------------------------------------------

    /** Repairs everything the server has refused so far (S2). */
    public synchronized void drainRepairs() throws Exception {
        RepairQueue.Failure failure;
        while ((failure = repairs.poll()) != null) {
            if (failure.topic().equals(blotter)) {
                repairBlotter(failure);
            } else {
                repairAudit(failure);
            }
        }
    }

    private void repairBlotter(RepairQueue.Failure failure) throws Exception {
        counters.publishFailures.incrementAndGet();
        String key = keyOf(failure);
        OrderState state = key == null ? null : machine.state(key);
        if (state == null) {
            counters.unrepairable.incrementAndGet();
            log.error("a refused blotter publish (sequence {}) names no cached order; not repaired: {}",
                    failure.sequence(), printable(failure.data()));
            return;
        }
        int attempts = repairAttempts.merge(key, 1, Integer::sum);
        if (attempts > MAX_REPAIR_ATTEMPTS) {
            counters.unrepairable.incrementAndGet();
            log.error("order {}: the blotter refused {} repairs in a row; giving up", key, attempts - 1);
            return;
        }
        boolean wholeAndKeyed = failure.wasPublish() && key.equals(keyIn(failure.data()));
        if (wholeAndKeyed) {
            // A refused creation: re-sent whole. Rebuilt from the cache it would
            // lose the D's terms, which the cache deliberately does not hold.
            publishBlotter(key, true, failure.data(), failure.data().length);
        } else {
            // A refused delta: every cached field, as a delta. A publish would
            // replace the record and erase the account, symbol and side.
            out.clear();
            writer.writeAll(state, out);
            publishBlotter(key, false, out.buffer(), out.size());
        }
        counters.repairs.incrementAndGet();
        log.warn("order {}: repaired a refused {} with a {}", key, failure.wasPublish() ? "publish" : "delta",
                wholeAndKeyed ? "re-publish" : "delta of every cached field");
    }

    private void repairAudit(RepairQueue.Failure failure) throws Exception {
        counters.auditPublishFailures.incrementAndGet();
        String catchAll = router.catchAllTopic();
        if (mode == Mode.LENIENT && catchAll != null && !failure.topic().equals(catchAll)) {
            sink.publish(catchAll, failure.data(), 0, failure.data().length);
            log.warn("a refused publish to {} is recorded on {} instead", failure.topic(), catchAll);
        } else {
            log.error("a publish to {} was refused and has nowhere else to go: {}", failure.topic(),
                    printable(failure.data()));
        }
    }

    private String keyOf(RepairQueue.Failure failure) {
        String inPayload = keyIn(failure.data());
        return inPayload != null ? inPayload : sentKeys.get(failure.sequence());
    }

    private static String keyIn(byte[] data) {
        try {
            String key = new FixFrame().wrap(data, 0, data.length).value(FixTags.Record.ROOT_CL_ORD_ID);
            return key.isEmpty() ? null : key;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String printable(byte[] data) {
        return new String(data, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\u0001', '|');
    }

    /**
     * Publishes {@code payload} to the blotter on {@code key}'s behalf, as the
     * machine would but unchecked: the raw path {@code PublishPathIT} uses to
     * send the server payloads the publisher itself never would.
     */
    public synchronized void publishRaw(String key, boolean full, byte[] payload) throws Exception {
        publishBlotter(key, full, payload, payload.length);
    }

    // ---- lifecycle -------------------------------------------------------------------

    /** What the idle timer does: repairs, then terminal entries past their retention (docs/04 §4). */
    private synchronized void idle() {
        try {
            drainRepairs();
            machine.evictExpired();
        } catch (Exception e) {
            log.error("idle repair drain failed", e);
        }
    }

    /** Drains the repair queue and evicts expired entries every {@code period} while the feed is quiet. */
    public synchronized void startIdleDrain(Duration period) {
        if (idleDrain != null) {
            return;
        }
        idleDrain = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fix42-order-state-repairs");
            thread.setDaemon(true);
            return thread;
        });
        idleDrain.scheduleWithFixedDelay(this::idle, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Waits until everything published is acknowledged as persisted, repairing
     * what the server refused on the way, until nothing is left to repair.
     */
    public synchronized void flush() throws Exception {
        sink.flush(timeoutMs);
        while (!repairs.isEmpty()) {
            drainRepairs();
            sink.flush(timeoutMs);
        }
    }

    /** Stops the idle timer, repairs what is queued and flushes: Spring's destroy method. The client is closed after. */
    public synchronized void close() throws Exception {
        if (idleDrain != null) {
            idleDrain.shutdownNow();
            idleDrain = null;
        }
        flush();
    }

    // ---- observability --------------------------------------------------------------

    public Counters counters() {
        return counters;
    }

    public OrderStateMachine machine() {
        return machine;
    }

    public long publishStoreDepth() {
        return sink.unpersisted();
    }

    public long publishDuplicatesOnReplay() {
        return repairs.duplicates();
    }

    /** The end-of-run summary line (docs/04 §7): every non-zero counter by name. */
    public synchronized String summary() {
        Map<String, Long> all = new TreeMap<>(machine.counters().nonZero());
        counters.forEach((name, value) -> {
            if (value != 0) {
                all.put(name, value);
            }
        });
        all.put("cacheEntries", (long) machine.size());
        all.put("indexEntries", (long) machine.index().clOrdIdCount() + machine.index().orderIdCount());
        all.put("pendingOnTerminal", machine.pendingOnTerminal());
        all.put("publishStoreDepth", sink.unpersisted());
        all.put("publishDuplicatesOnReplay", repairs.duplicates());
        unroutedByType.forEach((type, count) -> all.put("unrouted[35=" + type + "]", count));
        return all.toString();
    }

    /** The publisher's own counters (docs/04 §7); the machine's are on {@link OrderStateMachine#counters()}. */
    public static final class Counters {
        final AtomicLong fullPublishes = new AtomicLong();
        final AtomicLong deltaPublishes = new AtomicLong();
        final AtomicLong auditPublishes = new AtomicLong();
        final AtomicLong bytesPublished = new AtomicLong();
        final AtomicLong publishFailures = new AtomicLong();
        final AtomicLong auditPublishFailures = new AtomicLong();
        final AtomicLong repairs = new AtomicLong();
        final AtomicLong unrepairable = new AtomicLong();
        final AtomicLong malformed = new AtomicLong();
        final AtomicLong auditKeyTagConflict = new AtomicLong();

        public long fullPublishes() {
            return fullPublishes.get();
        }

        public long deltaPublishes() {
            return deltaPublishes.get();
        }

        public long auditPublishes() {
            return auditPublishes.get();
        }

        public long bytesPublished() {
            return bytesPublished.get();
        }

        public long publishFailures() {
            return publishFailures.get();
        }

        public long auditPublishFailures() {
            return auditPublishFailures.get();
        }

        public long repairs() {
            return repairs.get();
        }

        public long unrepairable() {
            return unrepairable.get();
        }

        public long malformed() {
            return malformed.get();
        }

        public long auditKeyTagConflict() {
            return auditKeyTagConflict.get();
        }

        void forEach(java.util.function.BiConsumer<String, Long> action) {
            action.accept("fullPublishes", fullPublishes.get());
            action.accept("deltaPublishes", deltaPublishes.get());
            action.accept("auditPublishes", auditPublishes.get());
            action.accept("bytesPublished", bytesPublished.get());
            action.accept("publishFailures", publishFailures.get());
            action.accept("auditPublishFailures", auditPublishFailures.get());
            action.accept("repairs", repairs.get());
            action.accept("unrepairable", unrepairable.get());
            action.accept("malformed", malformed.get());
            action.accept("auditKeyTagConflict", auditKeyTagConflict.get());
        }
    }
}
