package com.demo.amps.qfj2.seqno;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.qfj2.amps.AmpsClients;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checkpoints on an AMPS SOW topic keyed by session id.
 *
 * <p>{@link #publish} is a {@code publish} followed by {@code publishFlush},
 * so it returns only once the server has processed the record -- "replicated"
 * means AMPS has it, not that it left the client's buffer. {@link #load} is
 * one keyed SOW query. The topic is JSON-typed, so this holds its own client
 * on {@code /amps/json}; the engine's FIX publishes travel on a separate
 * {@code /amps/fix} connection, because a connection serves one message type.
 *
 * <p>The connection is opened on first use and dropped on any failure, so the
 * next call reconnects rather than reusing a broken client. Calls are
 * serialised: only the write-behind thread and the startup recovery use this.
 */
public final class AmpsSeqnoReplicator implements SeqnoReplicator {

    private static final Logger log = LoggerFactory.getLogger(AmpsSeqnoReplicator.class);

    /**
     * Connection settings.
     *
     * @param uri         must select the {@code json} message type
     * @param topic       the SOW topic keyed on {@code /sessionId}
     * @param clientName  this client's identity to the server
     * @param timeoutMs   logon, flush and query timeout
     * @param connectWait how long to keep retrying a refused connection
     */
    public record Settings(String uri, String topic, String clientName, long timeoutMs, Duration connectWait) {
        public Settings {
            if (uri == null || !uri.endsWith("/json")) {
                throw new IllegalArgumentException("the sequence-number checkpoint topic is json-typed, so "
                        + "qfj.seqno.uri must end in /amps/json; got " + uri);
            }
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("qfj.seqno.topic is required");
            }
            if (clientName == null || clientName.isBlank()) {
                throw new IllegalArgumentException("qfj.seqno.client-name is required");
            }
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("qfj.seqno.timeout-ms must be positive");
            }
            connectWait = connectWait == null ? Duration.ZERO : connectWait;
        }
    }

    private final Settings settings;
    private Client client;

    public AmpsSeqnoReplicator(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    @Override
    public synchronized void publish(SeqnoSnapshot snapshot) throws AMPSException {
        Client connected = client();
        try {
            connected.publish(settings.topic(), SeqnoJson.encode(snapshot));
            connected.publishFlush(settings.timeoutMs());
        } catch (AMPSException | RuntimeException e) {
            dropClient();
            throw e;
        }
    }

    @Override
    public synchronized Optional<SeqnoSnapshot> load(String sessionId) throws AMPSException {
        Client connected = client();
        List<SeqnoSnapshot> found = new ArrayList<>();
        try (MessageStream stream = connected.sow(settings.topic(), filterFor(sessionId))) {
            stream.timeout((int) Math.min(Integer.MAX_VALUE, settings.timeoutMs()));
            while (stream.hasNext()) {
                Message message = stream.next();
                if (message == null) {
                    break;
                }
                int command = message.getCommand();
                if (command == Message.Command.GroupEnd) {
                    break;
                }
                if (command == Message.Command.SOW && !message.isDataNull()) {
                    found.add(SeqnoJson.decode(message.getData()));
                }
            }
        } catch (AMPSException | RuntimeException e) {
            dropClient();
            throw e;
        }
        if (found.size() > 1) {
            // Keyed on /sessionId, so this cannot happen unless the topic is
            // misconfigured; refuse rather than pick one.
            throw new IllegalStateException("expected at most one checkpoint for " + sessionId + " on "
                    + settings.topic() + " (keyed /sessionId), found " + found.size());
        }
        log.debug("checkpoint lookup for {}: {}", sessionId,
                found.isEmpty() ? "none" : found.get(0).describe());
        return found.stream().findFirst();
    }

    /** The keyed query: {@code /sessionId = '<id>'}. */
    static String filterFor(String sessionId) {
        if (sessionId.indexOf('\'') >= 0) {
            throw new IllegalArgumentException("session id cannot contain a quote: " + sessionId);
        }
        return "/sessionId = '" + sessionId + "'";
    }

    private Client client() throws AMPSException {
        if (client == null) {
            client = AmpsClients.connect(settings.clientName(), settings.uri(), settings.timeoutMs(),
                    settings.connectWait());
        }
        return client;
    }

    private void dropClient() {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // Already broken; the point is to forget it.
            }
            client = null;
        }
    }

    @Override
    public synchronized void close() {
        dropClient();
    }

    @Override
    public String toString() {
        return "AmpsSeqnoReplicator[" + settings.uri() + " " + settings.topic() + "]";
    }
}
