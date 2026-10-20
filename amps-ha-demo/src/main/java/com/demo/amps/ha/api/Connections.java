package com.demo.amps.ha.api;

import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.FailedWriteHandler;
import com.crankuptheamps.client.FixedDelayStrategy;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.exception.AMPSException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a publisher, a subscriber and a subscriber's bookmark tracker do the
 * same way: dial the instances in order, keep redialling after a drop, and
 * log what the client would otherwise swallow.
 */
final class Connections {

    private static final Logger log = LoggerFactory.getLogger(Connections.class);

    private Connections() {
    }

    /**
     * Connects and logs on, trying {@code uris} in order and giving up only
     * on the first connect, after {@link HaConnectionSettings#firstConnectBudget()}.
     * The client's own state listener is {@code journal}, so the caller can
     * read where it ended up.
     */
    static void connect(HAClient client, ConnectionJournal journal, List<String> uris, HaConnectionSettings settings)
            throws AMPSException {
        client.addConnectionStateListener(journal);
        // Exceptions the client absorbs on its own threads would otherwise vanish.
        client.setExceptionListener(e -> log.warn("[{}] client reported: {}", client.getName(), e.toString()));
        client.setServerChooser(new DefaultServerChooser().addAll(uris));
        client.setTimeout(millis(settings.logonTimeout()));
        // Bounded for the FIRST connect only: against a pair that is not there
        // at all, connectAndLogon would otherwise block forever. Once connected,
        // reconnects never give up -- an outage is what an HA client is for.
        client.setReconnectDelayStrategy(
                new FixedDelayStrategy(millis(settings.reconnectDelay()), millis(settings.firstConnectBudget())));
        client.connectAndLogon();
        client.setReconnectDelayStrategy(new FixedDelayStrategy(millis(settings.reconnectDelay())));
        if (settings.heartbeatSeconds() > 0) {
            client.setHeartbeat(settings.heartbeatSeconds());
        }
    }

    /**
     * What the server refused. After a replay, Duplicate is the EXPECTED
     * answer for a message that had reached the survivor before the crash: it
     * is the server-side half of "at least once, seen once". Anything else is
     * worth a warning.
     *
     * @param what the noun for the log line: "message", "recovery point"
     */
    static FailedWriteHandler loggingFailedWriteHandler(String clientName, String what) {
        return (message, reason) -> {
            long sequence = message.isSequenceNull() ? 0 : message.getSequence();
            if (reason == Message.Reason.Duplicate) {
                log.info("[{}] {} sequence {} dropped by the server as a duplicate (already there, expected after a replay)",
                        clientName, what, sequence);
            } else {
                log.warn("[{}] {} sequence {} refused by the server, reason {}", clientName, what, sequence, reason);
            }
        };
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    static List<String> requireUris(List<String> uris) {
        if (uris == null || uris.isEmpty()) {
            throw new IllegalArgumentException("at least one server URI is required");
        }
        uris.forEach(uri -> requireText(uri, "uri"));
        return List.copyOf(uris);
    }

    private static int millis(java.time.Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, duration.toMillis()));
    }
}
