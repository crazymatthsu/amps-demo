package com.demo.amps.qfj2.config;

import com.demo.amps.qfj2.engine.DropCopyApplication;
import com.demo.amps.qfj2.engine.FixEngine;
import com.demo.amps.qfj2.engine.FixSessionEvent;
import com.demo.amps.qfj2.engine.SessionListener;
import com.demo.amps.qfj2.engine.SessionSettingsLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.MessageChannel;
import quickfix.CompositeLogFactory;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * Wires the QuickFIX/J engine: settings, logging, the application that feeds
 * the flow, and the lifecycle bean that starts and stops the connector.
 */
@Configuration
public class FixEngineConfiguration {

    @Bean
    public SessionSettings sessionSettings(QfjProperties properties) {
        return SessionSettingsLoader.load(Path.of(properties.engine().settings()));
    }

    /**
     * SLF4J always (so FIX events land in the application log), plus
     * QuickFIX/J's own per-session files when {@code FileLogPath} is set.
     */
    @Bean
    public LogFactory qfjLogFactory(SessionSettings settings) {
        List<LogFactory> factories = new ArrayList<>();
        factories.add(new SLF4JLogFactory(settings));
        if (settings.isSetting(FileLogFactory.SETTING_FILE_LOG_PATH)) {
            factories.add(new FileLogFactory(settings));
        }
        return factories.size() == 1
                ? factories.get(0)
                : new CompositeLogFactory(factories.toArray(new LogFactory[0]));
    }

    @Bean
    public MessageFactory qfjMessageFactory() {
        return new DefaultMessageFactory();
    }

    /** The engine's application; absent under the seqno-admin profile, which runs no engine. */
    @Bean
    @Profile("!seqno-admin")
    public DropCopyApplication dropCopyApplication(MessageChannel fixInboundChannel, QfjProperties properties,
                                                   ApplicationEventPublisher events,
                                                   ObjectProvider<SessionListener> listeners) {
        List<SessionListener> all = new ArrayList<>(listeners.orderedStream().toList());
        all.add(new SessionListener() {
            @Override
            public void onLogon(SessionID sessionId) {
                events.publishEvent(new FixSessionEvent(this, sessionId, FixSessionEvent.Kind.LOGON));
            }

            @Override
            public void onLogout(SessionID sessionId) {
                events.publishEvent(new FixSessionEvent(this, sessionId, FixSessionEvent.Kind.LOGOUT));
            }
        });
        return new DropCopyApplication(fixInboundChannel, properties.flow().includeOutbound(),
                properties.flow().disconnectOnDeliveryFailure(), all);
    }

    @Bean
    @Profile("!seqno-admin")
    public FixEngine fixEngine(SessionSettings settings, DropCopyApplication application,
                               MessageStoreFactory storeFactory, LogFactory qfjLogFactory,
                               MessageFactory qfjMessageFactory, QfjProperties properties) {
        return new FixEngine(settings, application, storeFactory, qfjLogFactory, qfjMessageFactory,
                properties.engine().threaded(), properties.engine().autoStart());
    }
}
