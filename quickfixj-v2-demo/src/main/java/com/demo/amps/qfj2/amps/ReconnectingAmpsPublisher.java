package com.demo.amps.qfj2.amps;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.exception.AMPSException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The publish-side AMPS connection, reopened after any failure.
 *
 * <p>The plain {@link Client} does not reconnect: once AMPS restarts, every
 * later publish on it fails, forever. This wrapper drops the client on the
 * first failure and opens a new one on the next call, the same way the
 * checkpoint replicator does. The failed publish still fails -- the caller
 * (a destination on the FIX session thread) is meant to let that propagate,
 * so the session drops and the counterparty resends -- and the retry is the
 * next delivery attempt, on a fresh connection.
 *
 * <p>Two budgets: {@code connectWait} for the first connection (AMPS may
 * still be starting alongside the engine), and a short {@code reconnectWait}
 * for every later one, because a reconnect happens inside {@code fromApp}
 * and a session thread held for a minute misses its heartbeats.
 */
public final class ReconnectingAmpsPublisher implements AmpsPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReconnectingAmpsPublisher.class);

    private final String clientName;
    private final String uri;
    private final long timeoutMs;
    private final Duration connectWait;
    private final Duration reconnectWait;

    private Client client;
    private boolean connectedOnce;
    private long reconnects;

    /**
     * Connects eagerly, so a misconfigured URI fails the start rather than
     * the first message.
     */
    public ReconnectingAmpsPublisher(String clientName, String uri, long timeoutMs, Duration connectWait,
                                     Duration reconnectWait) throws AMPSException {
        this.clientName = clientName;
        this.uri = uri;
        this.timeoutMs = timeoutMs;
        this.connectWait = connectWait;
        this.reconnectWait = reconnectWait;
        client();
    }

    @Override
    public synchronized void publish(String topic, String payload, boolean flush) throws Exception {
        Client connected = client();
        try {
            connected.publish(topic, payload);
            if (flush) {
                connected.publishFlush(timeoutMs);
            }
        } catch (AMPSException | RuntimeException e) {
            log.warn("publish to {} failed ({}); the connection is dropped and reopened on the next attempt",
                    topic, e.toString());
            dropClient();
            throw e;
        }
    }

    /** How many times the connection has been reopened after a failure. */
    public synchronized long reconnects() {
        return reconnects;
    }

    public synchronized boolean isConnected() {
        return client != null;
    }

    private Client client() throws AMPSException {
        if (client == null) {
            Duration budget = connectedOnce ? reconnectWait : connectWait;
            client = AmpsClients.connect(clientName, uri, timeoutMs, budget);
            if (connectedOnce) {
                reconnects++;
            }
            connectedOnce = true;
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
        return "ReconnectingAmpsPublisher[" + uri + " as " + clientName + "]";
    }
}
