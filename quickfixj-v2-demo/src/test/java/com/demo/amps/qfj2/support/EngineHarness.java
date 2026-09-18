package com.demo.amps.qfj2.support;

import com.demo.amps.qfj2.engine.Direction;
import com.demo.amps.qfj2.engine.DropCopyApplication;
import com.demo.amps.qfj2.engine.FixEngine;
import com.demo.amps.qfj2.engine.FixHeaders;
import com.demo.amps.qfj2.engine.FixMessages;
import com.demo.amps.qfj2.engine.SessionListener;
import com.demo.amps.qfj2.flow.Destination;
import com.demo.amps.qfj2.flow.DestinationDispatcher;
import com.demo.amps.qfj2.flow.EnrichmentRule;
import com.demo.amps.qfj2.flow.RuleChain;
import com.demo.amps.qfj2.seqno.AmpsReplicatedFileStoreFactory;
import com.demo.amps.qfj2.seqno.RecoveryPolicy;
import com.demo.amps.qfj2.seqno.RecoveryReport;
import com.demo.amps.qfj2.seqno.SeqnoReplicator;
import com.demo.amps.qfj2.seqno.WriteBehindPublisher;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.springframework.integration.channel.DirectChannel;
import quickfix.DefaultMessageFactory;
import quickfix.FileStoreFactory;
import quickfix.Message;
import quickfix.MessageStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * The engine assembled by hand -- the same classes the Spring configuration
 * wires, without a Spring context -- so a test can run two or three of them
 * in one JVM: an acceptor playing the venue and an initiator playing the
 * consumer, each with its own file store, replicator and destinations.
 *
 * <p>The pipeline is exact: a {@code DirectChannel} whose one subscriber
 * runs the rule chain then the dispatcher, which is what the
 * {@code IntegrationFlow} in production reduces to.
 */
public final class EngineHarness implements AutoCloseable {

    /** An admin message seen on a session, for asserting what crossed the wire. */
    public record AdminMessage(SessionID sessionId, Direction direction, String msgType, String wire) {
    }

    public static final class Builder {
        private final Path workDir;
        private final String connectionType;
        private final int port;
        private final String host;
        private final List<SessionID> sessions = new ArrayList<>();
        private SeqnoReplicator replicator;
        private RecoveryPolicy policy = RecoveryPolicy.AMPS_WINS;
        private boolean requireAmps = true;
        private String source = "test";
        private final List<EnrichmentRule> rules = new ArrayList<>();
        private final List<Destination> destinations = new ArrayList<>();
        private boolean includeOutbound;
        private boolean disconnectOnFailure = true;
        private int heartBtInt = 1;

        private Builder(Path workDir, String connectionType, String host, int port) {
            this.workDir = workDir;
            this.connectionType = connectionType;
            this.host = host;
            this.port = port;
        }

        public Builder session(String sender, String target) {
            sessions.add(new SessionID("FIX.4.2", sender, target));
            return this;
        }

        /** Replicate through this; absent = a plain file store. */
        public Builder replicator(SeqnoReplicator replicator) {
            this.replicator = replicator;
            return this;
        }

        public Builder policy(RecoveryPolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder requireAmps(boolean requireAmps) {
            this.requireAmps = requireAmps;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder rule(EnrichmentRule rule) {
            rules.add(rule);
            return this;
        }

        public Builder destination(Destination destination) {
            destinations.add(destination);
            return this;
        }

        public Builder includeOutbound(boolean includeOutbound) {
            this.includeOutbound = includeOutbound;
            return this;
        }

        public Builder disconnectOnFailure(boolean disconnectOnFailure) {
            this.disconnectOnFailure = disconnectOnFailure;
            return this;
        }

        public Builder heartBtInt(int seconds) {
            this.heartBtInt = seconds;
            return this;
        }

        public EngineHarness build() {
            if (sessions.isEmpty()) {
                throw new IllegalStateException("at least one session");
            }
            SessionSettings settings = new SessionSettings();
            settings.setString("ConnectionType", connectionType);
            settings.setString("FileStorePath", workDir.resolve("store").toString());
            settings.setString("FileStoreSync", "Y");
            settings.setString("UseDataDictionary", "Y");
            settings.setString("DataDictionary", TestPaths.dictionary().toString());
            settings.setString("ValidateUserDefinedFields", "N");
            settings.setString("AllowUnknownMsgFields", "Y");
            settings.setString("NonStopSession", "Y");
            settings.setString("StartTime", "00:00:00");
            settings.setString("EndTime", "00:00:00");
            settings.setLong("HeartBtInt", heartBtInt);
            settings.setString("ResetOnLogon", "N");
            settings.setString("ResetOnLogout", "N");
            settings.setString("ResetOnDisconnect", "N");
            settings.setString("RejectMessageOnUnhandledException", "N");
            settings.setString("SLF4JLogHeartbeats", "N");
            if (connectionType.equals("acceptor")) {
                settings.setLong("SocketAcceptPort", port);
                settings.setString("SocketAcceptAddress", "127.0.0.1");
            } else {
                settings.setString("SocketConnectHost", host);
                settings.setLong("SocketConnectPort", port);
                settings.setLong("ReconnectInterval", 1);
                settings.setLong("LogonTimeout", 5);
                settings.setLong("LogoutTimeout", 2);
            }
            for (SessionID id : sessions) {
                settings.setString(id, "BeginString", id.getBeginString());
                settings.setString(id, "SenderCompID", id.getSenderCompID());
                settings.setString(id, "TargetCompID", id.getTargetCompID());
            }
            return new EngineHarness(this, settings);
        }
    }

    public static Builder acceptor(Path workDir, int port) {
        return new Builder(workDir, "acceptor", "127.0.0.1", port);
    }

    public static Builder initiator(Path workDir, String host, int port) {
        return new Builder(workDir, "initiator", host, port);
    }

    private final SessionSettings settings;
    private final List<SessionID> sessions;
    private final WriteBehindPublisher publisher;
    private final AmpsReplicatedFileStoreFactory replicatedFactory;
    private final RuleChain rules;
    private final DestinationDispatcher dispatcher;
    private final DropCopyApplication application;
    private final FixEngine engine;
    private final List<AdminMessage> admin = new CopyOnWriteArrayList<>();
    private final List<SessionID> loggedOn = new CopyOnWriteArrayList<>();

    private EngineHarness(Builder builder, SessionSettings settings) {
        this.settings = settings;
        this.sessions = List.copyOf(builder.sessions);
        MessageStoreFactory storeFactory;
        if (builder.replicator != null) {
            publisher = new WriteBehindPublisher(builder.replicator, Duration.ZERO, Duration.ofMillis(200),
                    Duration.ofSeconds(10));
            replicatedFactory = new AmpsReplicatedFileStoreFactory(settings, builder.replicator, publisher,
                    builder.policy, builder.requireAmps, builder.source);
            storeFactory = replicatedFactory;
        } else {
            publisher = null;
            replicatedFactory = null;
            storeFactory = new FileStoreFactory(settings);
        }
        rules = new RuleChain(builder.rules);
        dispatcher = new DestinationDispatcher(builder.destinations);

        DirectChannel channel = new DirectChannel();
        channel.subscribe(message -> {
            Message payload = (Message) message.getPayload();
            rules.apply(payload, FixHeaders.context(message.getHeaders()));
            dispatcher.dispatch(payload, FixHeaders.context(message.getHeaders()));
        });
        SessionListener listener = new SessionListener() {
            @Override
            public void onLogon(SessionID sessionId) {
                loggedOn.add(sessionId);
            }

            @Override
            public void onLogout(SessionID sessionId) {
                loggedOn.remove(sessionId);
            }

            @Override
            public void onAdmin(Message message, SessionID sessionId, Direction direction) {
                admin.add(new AdminMessage(sessionId, direction, FixMessages.msgType(message),
                        FixMessages.printable(message)));
            }
        };
        application = new DropCopyApplication(channel, builder.includeOutbound, builder.disconnectOnFailure,
                List.of(listener));
        engine = new FixEngine(settings, application, storeFactory, new SLF4JLogFactory(settings),
                new DefaultMessageFactory(), false, true);
    }

    public EngineHarness start() {
        engine.start();
        return this;
    }

    /** Stops the connector and closes the write-behind publisher, replicating the final numbers. */
    public void stop() {
        if (engine.isRunning()) {
            engine.stop();
        }
        if (replicatedFactory != null) {
            replicatedFactory.close();
        }
    }

    @Override
    public void close() {
        stop();
    }

    public SessionID sessionId() {
        return sessions.get(0);
    }

    public List<SessionID> sessionIds() {
        return sessions;
    }

    public Session session() {
        return session(sessionId());
    }

    public Session session(SessionID id) {
        Session session = Session.lookupSession(id);
        if (session == null) {
            throw new IllegalStateException("no session " + id);
        }
        return session;
    }

    public FixEngine engine() {
        return engine;
    }

    public DropCopyApplication application() {
        return application;
    }

    public DestinationDispatcher dispatcher() {
        return dispatcher;
    }

    public WriteBehindPublisher publisher() {
        return publisher;
    }

    public Optional<RecoveryReport> recovery(SessionID id) {
        return replicatedFactory == null ? Optional.empty() : replicatedFactory.lastRecovery(id);
    }

    public Optional<RecoveryReport> recovery() {
        return recovery(sessionId());
    }

    public SessionSettings settings() {
        return settings;
    }

    public List<AdminMessage> adminMessages() {
        return List.copyOf(admin);
    }

    public long adminCount(SessionID id, Direction direction, String msgType) {
        return admin.stream()
                .filter(m -> m.sessionId().equals(id) && m.direction() == direction && m.msgType().equals(msgType))
                .count();
    }

    public boolean isLoggedOn() {
        return engine.isLoggedOn(sessionId());
    }

    public EngineHarness awaitLogon() {
        return awaitLogon(sessionId());
    }

    public EngineHarness awaitLogon(SessionID id) {
        Awaitility.await("logon of " + id).atMost(Duration.ofSeconds(20))
                .until(() -> engine.isLoggedOn(id));
        return this;
    }

    public void awaitLogout(SessionID id) {
        Awaitility.await("logout of " + id).atMost(Duration.ofSeconds(20))
                .until(() -> !engine.isLoggedOn(id));
    }

    /** Sends on the harness's first session; fails if the session cannot take it. */
    public void send(Message message) {
        send(message, sessionId());
    }

    public void send(Message message, SessionID id) {
        try {
            if (!Session.sendToTarget(message, id)) {
                throw new IllegalStateException("send on " + id + " refused (not logged on?)");
            }
        } catch (quickfix.SessionNotFound e) {
            throw new IllegalStateException(e);
        }
    }

    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
