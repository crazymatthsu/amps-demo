package com.demo.amps.qfj2.config;

import com.demo.amps.qfj2.flow.DestinationSpec;
import com.demo.amps.qfj2.flow.DestinationType;
import com.demo.amps.qfj2.flow.RuleFactory;
import com.demo.amps.qfj2.flow.RuleSpec;
import com.demo.amps.qfj2.seqno.RecoveryPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import quickfix.SessionID;

/**
 * Everything under {@code qfj.*}, bound from {@code application.yml} and the
 * role file layered over it.
 *
 * <p>{@link #validate()} runs at startup and refuses to boot on anything that
 * would otherwise fail on the first message: a rule of an unknown type, an
 * AMPS destination on a connection that does not select the {@code fix}
 * message type, a checkpoint connection that does not select {@code json}.
 *
 * @param role   a short name for this instance; used in log file and client names
 * @param engine which settings file, and whether to start
 * @param amps   the connection the AMPS destinations publish on
 * @param seqno  the replicated sequence-number store
 * @param flow   the rules and destinations
 * @param mock   the venue-side execution feed
 */
@ConfigurationProperties(prefix = "qfj")
public record QfjProperties(
        @DefaultValue("engine") String role,
        @DefaultValue Engine engine,
        @DefaultValue Amps amps,
        @DefaultValue Seqno seqno,
        @DefaultValue Flow flow,
        @DefaultValue Mock mock) {

    /**
     * @param settings  path to the QuickFIX/J settings file
     * @param autoStart start the engine when the context starts
     * @param threaded  use the threaded acceptor/initiator (a thread per session)
     */
    public record Engine(
            @DefaultValue("config/dropcopy/quickfixj.cfg") String settings,
            @DefaultValue("true") boolean autoStart,
            @DefaultValue("false") boolean threaded) {
    }

    /**
     * @param uri           must end in {@code /amps/fix}
     * @param clientName    identity to the server
     * @param timeoutMs     logon and flush timeout
     * @param connectWaitMs   how long a starting engine retries a refused connection
     * @param reconnectWaitMs how long a reopen after a failed publish may take; short,
     *                        because it happens on the FIX session thread
     * @param flushEach       publishFlush after every publish, so the FIX message
     *                        is acknowledged only once AMPS has it
     */
    public record Amps(
            @DefaultValue("tcp://127.0.0.1:9007/amps/fix") String uri,
            @DefaultValue("qfj2-fix") String clientName,
            @DefaultValue("10000") long timeoutMs,
            @DefaultValue("60000") long connectWaitMs,
            @DefaultValue("5000") long reconnectWaitMs,
            @DefaultValue("true") boolean flushEach) {
    }

    /**
     * @param enabled        false = plain QuickFIX/J file store, nothing replicated
     * @param uri            must end in {@code /amps/json}
     * @param topic          the SOW topic keyed on {@code /sessionId}
     * @param clientName     identity to the server
     * @param timeoutMs      logon, flush and query timeout
     * @param recovery       what to do when file and AMPS disagree at startup
     * @param requireAmps    refuse to start if the checkpoint cannot be read
     * @param minIntervalMs  pause between write-behind publishes (0 = none)
     * @param retryBackoffMs pause after a failed publish
     * @param source         stamped on every checkpoint; empty = the host name
     */
    public record Seqno(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("tcp://127.0.0.1:9007/amps/json") String uri,
            @DefaultValue("sow/quickfixj/seqno") String topic,
            @DefaultValue("qfj2-seqno") String clientName,
            @DefaultValue("10000") long timeoutMs,
            @DefaultValue("AMPS_WINS") RecoveryPolicy recovery,
            @DefaultValue("true") boolean requireAmps,
            @DefaultValue("0") long minIntervalMs,
            @DefaultValue("1000") long retryBackoffMs,
            @DefaultValue("") String source) {

        /** The configured source, or this host's name. */
        public String sourceOrHostname() {
            if (source != null && !source.isBlank()) {
                return source.trim();
            }
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                return "unknown-host";
            }
        }
    }

    /** How the engine hands messages to the flow. */
    public enum ChannelMode {
        /** On the session thread; a failed destination delays the message, never loses it. */
        DIRECT,
        /** On one ordered background thread; faster, lossy if the process dies. */
        EXECUTOR
    }

    /**
     * @param channel                     DIRECT or EXECUTOR
     * @param includeOutbound             also route what this engine sends
     * @param disconnectOnDeliveryFailure drop the session when a destination fails,
     *                                    so the counterparty stops sending until the
     *                                    engine reconnects and requests the gap once
     * @param rules                       applied in order to every routed message
     * @param destinations                every one whose msg-types filter matches gets the message
     */
    public record Flow(
            @DefaultValue("DIRECT") ChannelMode channel,
            @DefaultValue("false") boolean includeOutbound,
            @DefaultValue("true") boolean disconnectOnDeliveryFailure,
            @DefaultValue List<RuleSpec> rules,
            @DefaultValue List<DestinationSpec> destinations) {

        public boolean hasAmpsDestination() {
            return destinations.stream().anyMatch(spec -> spec.type() == DestinationType.AMPS);
        }
    }

    /**
     * @param enabled    send invented execution reports on every logged-on session
     * @param intervalMs one report per interval per session
     * @param count      stop after this many per session; 0 = never
     * @param symbols    cycled through
     */
    public record Mock(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("2000") long intervalMs,
            @DefaultValue("0") int count,
            @DefaultValue({"AAPL", "MSFT", "TSLA", "NVDA", "AMZN"}) List<String> symbols) {
    }

    /** Fails the context start on a configuration that would fail later. */
    public void validate() {
        if (engine.settings() == null || engine.settings().isBlank()) {
            throw new IllegalStateException("qfj.engine.settings must name the QuickFIX/J settings file");
        }
        if (!Files.isRegularFile(Path.of(engine.settings()))) {
            throw new IllegalStateException("qfj.engine.settings points at "
                    + Path.of(engine.settings()).toAbsolutePath()
                    + ", which is not a file (the working directory should be the module folder, or /app "
                    + "in the container)");
        }
        if (flow.hasAmpsDestination() && (amps.uri() == null || !amps.uri().endsWith("/fix"))) {
            throw new IllegalStateException("qfj.amps.uri must select the 'fix' message type (a URI ending "
                    + "/amps/fix), because every drop-copy topic is declared <MessageType>fix</MessageType>. Got: "
                    + amps.uri());
        }
        if (seqno.enabled() && (seqno.uri() == null || !seqno.uri().endsWith("/json"))) {
            throw new IllegalStateException("qfj.seqno.uri must select the 'json' message type (a URI ending "
                    + "/amps/json), because the checkpoint topic is json-typed. Got: " + seqno.uri());
        }
        // Throws on an unknown type or a missing parameter.
        RuleFactory.createAll(flow.rules());

        Set<String> names = new HashSet<>();
        for (DestinationSpec spec : flow.destinations()) {
            if (spec.name() == null || spec.name().isBlank()) {
                throw new IllegalStateException("every qfj.flow.destinations entry needs a name: " + spec);
            }
            if (!names.add(spec.name())) {
                throw new IllegalStateException("duplicate destination name '" + spec.name() + "'");
            }
            if (spec.type() == null) {
                throw new IllegalStateException("destination '" + spec.name() + "' needs a type (amps or fix)");
            }
            switch (spec.type()) {
                case AMPS -> {
                    if (spec.topic() == null || spec.topic().isBlank()) {
                        throw new IllegalStateException("amps destination '" + spec.name() + "' needs a topic");
                    }
                }
                case FIX -> {
                    if (spec.session() == null || spec.session().isBlank()) {
                        throw new IllegalStateException("fix destination '" + spec.name()
                                + "' needs a session, e.g. FIX.4.2:DROPCOPY->DOWNSTREAM");
                    }
                    SessionID id;
                    try {
                        id = new SessionID(spec.session());
                    } catch (IllegalArgumentException e) {
                        id = null;
                    }
                    if (id == null || id.getBeginString().isEmpty() || id.getSenderCompID().isEmpty()
                            || id.getTargetCompID().isEmpty()) {
                        throw new IllegalStateException("fix destination '" + spec.name() + "' session '"
                                + spec.session() + "' is not of the form BeginString:Sender->Target");
                    }
                }
            }
        }
    }
}
