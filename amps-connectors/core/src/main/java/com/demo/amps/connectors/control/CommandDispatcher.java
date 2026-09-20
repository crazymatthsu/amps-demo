package com.demo.amps.connectors.control;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.ControlProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceRecord;
import com.demo.amps.connectors.source.SourceResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The control channel: reads commands from a {@link RecordSource}, decides whether they are
 * for this instance, and hands each to the {@link CommandHandler} that owns its name.
 *
 * <p>The source is the whole trick. A control topic on Kafka and one on AMPS are the same
 * six lines under {@code control.source:} that a feed would be, resolved by the same
 * {@link SourceResolver} and read by the same driver module; so the dispatcher never learns
 * a transport, and a driver that can read a feed can carry commands. It does that by
 * presenting the control block to the resolver as a synthetic connector named
 * {@code <application>-control}, {@code format: JSON} -- which is all a source factory reads
 * of a connector -- so an AMPS control listener logs on as
 * {@code <prefix>-<application>-control-source} beside the application's publishers.
 *
 * <p>Every record follows one path: parse, address, dispatch, log, acknowledge.
 *
 * <ul>
 *   <li>A payload that is not a command ({@code COMMAND_INVALID}) and a command nobody
 *       handles ({@code COMMAND_UNKNOWN}) are alerts at WARN and count as <em>failed</em>:
 *       somebody sent them meaning something.</li>
 *   <li>A command whose {@code to} is neither absent, nor {@code all}, nor this instance's
 *       {@code target}, nor in its {@code accept-targets} is <em>ignored</em> -- counted,
 *       logged at DEBUG, never alerted, because a command for another instance is not this
 *       one's mistake. A {@code DELETE} record (a tombstone, an out-of-focus message) is
 *       ignored the same way: it carries no command.</li>
 *   <li>A handler that throws is {@code COMMAND_FAILED}, with the command, its target, its
 *       request id and the exception's message in the details, so the sender can correlate
 *       the answer with the question.</li>
 *   <li>The record is acknowledged whatever happened. A control topic on Kafka commits its
 *       offset past a bad command rather than re-reading it forever, because the second
 *       reading would fail the same way.</li>
 * </ul>
 *
 * <p>Commands run one at a time, on the source's reader thread. That is a choice: a
 * {@code reload} that takes a minute is a minute during which no other command is read,
 * and the alternative -- a pool -- would let two reloads of one table race. The registry
 * serialises those anyway, but "one command at a time, in the order they were sent" is a
 * contract an operator can reason about.
 *
 * <p>Built-in handlers, {@link ReloadCommand} and {@link StatusCommand}, are registered
 * first; a handler from the application that answers to the same name <em>replaces</em>
 * the built-in, and says so in the log. Latest in the start order ({@code MAX - 500}), so
 * a command that arrives during startup finds the resources loaded and the connectors
 * running, and earliest in the stop order, so nothing is reloaded while it is being torn
 * down.
 */
public final class CommandDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    /** Raised, at WARN, for a payload that is not a command. */
    public static final String COMMAND_INVALID = "COMMAND_INVALID";

    /** Raised, at WARN, for a command no handler answers to. */
    public static final String COMMAND_UNKNOWN = "COMMAND_UNKNOWN";

    /** Raised, at WARN, when a handler threw. */
    public static final String COMMAND_FAILED = "COMMAND_FAILED";

    /** What the synthetic connector, and so its AMPS client, is called after the application. */
    public static final String CONNECTOR_SUFFIX = "-control";

    /** How much of an unparseable payload an alert quotes. */
    private static final int PAYLOAD_EXCERPT = 200;

    private final ControlProperties control;
    private final String target;
    private final SourceResolver sources;
    private final CommandContext context;
    private final Alerts alerts;
    private final Map<String, CommandHandler> handlers = new LinkedHashMap<>();
    private final ConnectorProperties connector;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();

    /** Commands run one at a time, whatever thread the source delivers them on. */
    private final Object dispatching = new Object();

    private volatile RecordSource source;
    private volatile boolean running;

    /**
     * @param control the {@code control:} block
     * @param application the application's name: what a blank {@code target} falls back to,
     *     and the stem of the synthetic connector's name
     * @param sources resolves the control source from the modules on the classpath
     * @param context what the handlers are given
     * @param extra the application's own handlers, in order; one answering to a built-in's
     *     name replaces it
     */
    public CommandDispatcher(
            ControlProperties control,
            String application,
            SourceResolver sources,
            CommandContext context,
            List<CommandHandler> extra) {
        this.control = control;
        this.target = control.getTarget() == null || control.getTarget().isBlank()
                ? application
                : control.getTarget().trim();
        this.sources = sources;
        this.context = context;
        this.alerts = context.alerts();
        register(new ReloadCommand(), false);
        register(new StatusCommand(), false);
        for (CommandHandler handler : extra) {
            register(handler, true);
        }
        this.connector = new ConnectorProperties();
        connector.setName(application + CONNECTOR_SUFFIX);
        connector.setFormat(SourceFormat.JSON);
        connector.setSource(control.getSource());
    }

    private void register(CommandHandler handler, boolean fromApplication) {
        String name = handler.command();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("command handler "
                    + handler.getClass().getName() + " answers to no command name");
        }
        CommandHandler replaced = handlers.put(name.trim(), handler);
        if (replaced != null && fromApplication) {
            log.info("[control] '{}' is handled by {} instead of the built-in {}",
                    name, handler.getClass().getName(), replaced.getClass().getSimpleName());
        }
    }

    /** The name this instance answers to in a command's {@code to}. */
    public String target() {
        return target;
    }

    /** The commands this dispatcher answers to, built-ins included. */
    public Set<String> commands() {
        return Set.copyOf(handlers.keySet());
    }

    /** The synthetic connector the control source is resolved for; the client is named after it. */
    public ConnectorProperties connector() {
        return connector;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!control.isEnabled()) {
            log.debug("[control] disabled; no commands will be read");
            return;
        }
        RecordSource resolved = sources.resolve(connector);
        resolved.start(this::onRecord);
        this.source = resolved;
        this.running = true;
        log.info("[control] listening on {} as '{}': target={} accept-targets={} commands={}",
                control.getSource().describe(), connector.getName(), target,
                control.getAcceptTargets(), handlers.keySet());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        RecordSource current = source;
        source = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException e) {
                log.warn("[control] closing the control source failed", e);
            }
        }
        log.info("[control] stopped after {}", counters());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** After the connectors, so a command finds them running; before them on the way down. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 500;
    }

    /** Whether the control source is connected to its topic. */
    public boolean isConnected() {
        RecordSource current = source;
        return running && current != null && current.isConnected();
    }

    /** One record from the control source: parse, address, dispatch, log, acknowledge. */
    void onRecord(SourceRecord record) {
        received.incrementAndGet();
        try {
            if (record.action() == SourceRecord.Action.DELETE) {
                ignored.incrementAndGet();
                log.debug("[control] ignored a removal: it carries no command");
                return;
            }
            ControlCommand command;
            try {
                command = ControlCommand.parse(record.data());
            } catch (IllegalArgumentException e) {
                failed.incrementAndGet();
                // The outcome lines are INFO throughout: the alert the manager logs beside
                // them is the WARN, and one warning per bad command is enough.
                log.info("[control] a payload is not a command: {}", e.getMessage());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("error", e.getMessage());
                details.put("payload", excerpt(record.data()));
                alerts.raise(Alert.of(Alert.Severity.WARN, COMMAND_INVALID,
                                "a payload on the control channel is not a command: "
                                        + e.getMessage())
                        .withDetails(details));
                return;
            }
            if (!accepts(command)) {
                ignored.incrementAndGet();
                log.debug("[control] ignored {}: this instance is '{}'",
                        command.describe(), target);
                return;
            }
            dispatch(command);
        } finally {
            // Whatever happened, the source's position moves past this record: reading a
            // bad command a second time would fail the same way.
            record.acknowledge();
        }
    }

    /** Whether {@code to} names this instance: absent, {@code all}, its target, or its group. */
    boolean accepts(ControlCommand command) {
        if (command.isBroadcast()) {
            return true;
        }
        String to = command.to().trim();
        if (to.equals(target)) {
            return true;
        }
        for (String accepted : control.getAcceptTargets()) {
            if (accepted != null && to.equals(accepted.trim())) {
                return true;
            }
        }
        return false;
    }

    private void dispatch(ControlCommand command) {
        CommandHandler handler = handlers.get(command.command());
        if (handler == null) {
            failed.incrementAndGet();
            log.info("[control] {} unknown; this instance answers to {}",
                    command.describe(), handlers.keySet());
            alerts.raise(Alert.of(Alert.Severity.WARN, COMMAND_UNKNOWN,
                            "unknown command '" + command.command() + "'; known: "
                                    + handlers.keySet())
                    .withDetails(details(command, null)));
            return;
        }
        synchronized (dispatching) {
            try {
                handler.handle(command, context);
                succeeded.incrementAndGet();
                log.info("[control] {} ok", command.describe());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failed.incrementAndGet();
                log.info("[control] {} failed: {}", command.describe(), e.toString());
                alerts.raise(Alert.of(Alert.Severity.WARN, COMMAND_FAILED,
                                command.describe() + " failed: " + e.getMessage())
                        .withDetails(details(command, e)));
            }
        }
    }

    /** What every alert about a command carries: {@code command, target, requestId[, error]}. */
    private static Map<String, Object> details(ControlCommand command, Exception error) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("command", command.command());
        details.put("target", command.target());
        details.put("requestId", command.requestId());
        if (error != null) {
            details.put("error", error.toString());
        }
        return details;
    }

    private static String excerpt(String payload) {
        if (payload == null) {
            return null;
        }
        return payload.length() <= PAYLOAD_EXCERPT
                ? payload
                : payload.substring(0, PAYLOAD_EXCERPT) + "...";
    }

    /** Records read from the control source, commands or not. */
    public long received() {
        return received.get();
    }

    /** Commands a handler carried out without throwing. */
    public long succeeded() {
        return succeeded.get();
    }

    /** Payloads that were not commands, commands nobody handles, and handlers that threw. */
    public long failed() {
        return failed.get();
    }

    /** Commands addressed to another instance, and removals. */
    public long ignored() {
        return ignored.get();
    }

    /**
     * One line for the status log.
     *
     * @return e.g. {@code control: amps:connectors/control target=instrument-enricher
     *     LISTENING received=3 succeeded=2 failed=1 ignored=0}, or {@code control: disabled}
     */
    public String status() {
        if (!control.isEnabled()) {
            return "control: disabled";
        }
        return "control: " + control.getSource().describe() + " target=" + target + " "
                + (running ? (isConnected() ? "LISTENING" : "RETRYING") : "STOPPED") + " "
                + counters();
    }

    private String counters() {
        return String.format("received=%d succeeded=%d failed=%d ignored=%d",
                received.get(), succeeded.get(), failed.get(), ignored.get());
    }

    @Override
    public String toString() {
        return status();
    }
}
