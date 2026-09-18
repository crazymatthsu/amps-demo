package com.demo.amps.qfj2.engine;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import quickfix.Application;
import quickfix.ConfigError;
import quickfix.Connector;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.Session;
import quickfix.SessionFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;
import quickfix.ThreadedSocketAcceptor;
import quickfix.ThreadedSocketInitiator;

/**
 * The FIX engine as a Spring lifecycle bean: an acceptor or an initiator,
 * decided by {@code ConnectionType} in the QuickFIX/J settings, started when
 * the context starts (after everything it publishes to exists) and stopped
 * first when it closes.
 *
 * <p>Nothing here knows about AMPS: the store factory it is handed does the
 * replication, and the {@link Application} does the routing. That is the
 * seam the tests use to run the same engine with an in-memory replicator.
 */
public final class FixEngine implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FixEngine.class);

    public enum ConnectionType {
        ACCEPTOR, INITIATOR;

        public static ConnectionType from(SessionSettings settings) {
            try {
                String raw = settings.getString(SessionFactory.SETTING_CONNECTION_TYPE);
                return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                    case SessionFactory.ACCEPTOR_CONNECTION_TYPE -> ACCEPTOR;
                    case SessionFactory.INITIATOR_CONNECTION_TYPE -> INITIATOR;
                    default -> throw new IllegalStateException("ConnectionType must be acceptor or initiator, got "
                            + raw);
                };
            } catch (ConfigError e) {
                throw new IllegalStateException("the QuickFIX/J settings need a ConnectionType", e);
            }
        }
    }

    private final SessionSettings settings;
    private final Application application;
    private final MessageStoreFactory storeFactory;
    private final LogFactory logFactory;
    private final MessageFactory messageFactory;
    private final boolean threaded;
    private final boolean autoStart;
    private final ConnectionType type;

    private volatile Connector connector;
    private volatile boolean running;

    public FixEngine(SessionSettings settings, Application application, MessageStoreFactory storeFactory,
                     LogFactory logFactory, MessageFactory messageFactory, boolean threaded, boolean autoStart) {
        this.settings = settings;
        this.application = application;
        this.storeFactory = storeFactory;
        this.logFactory = logFactory;
        this.messageFactory = messageFactory;
        this.threaded = threaded;
        this.autoStart = autoStart;
        this.type = ConnectionType.from(settings);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            Connector built = build();
            built.start();
            connector = built;
            running = true;
            log.info("FIX {} started ({}): sessions {}", type.name().toLowerCase(Locale.ROOT),
                    built.getClass().getSimpleName(), built.getSessions());
        } catch (ConfigError e) {
            throw new IllegalStateException("cannot start the FIX engine: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        log.info("stopping FIX {}", type.name().toLowerCase(Locale.ROOT));
        connector.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }

    public ConnectionType connectionType() {
        return type;
    }

    public List<SessionID> sessions() {
        Connector current = connector;
        return current == null ? List.of() : List.copyOf(current.getSessions());
    }

    public Optional<Session> session(SessionID sessionId) {
        return Optional.ofNullable(Session.lookupSession(sessionId));
    }

    public boolean isLoggedOn(SessionID sessionId) {
        return session(sessionId).map(Session::isLoggedOn).orElse(false);
    }

    private Connector build() throws ConfigError {
        return switch (type) {
            case ACCEPTOR -> threaded
                    ? new ThreadedSocketAcceptor(application, storeFactory, settings, logFactory, messageFactory)
                    : new SocketAcceptor(application, storeFactory, settings, logFactory, messageFactory);
            case INITIATOR -> threaded
                    ? new ThreadedSocketInitiator(application, storeFactory, settings, logFactory, messageFactory)
                    : new SocketInitiator(application, storeFactory, settings, logFactory, messageFactory);
        };
    }
}
