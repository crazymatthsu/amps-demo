package com.demo.amps.connectors.alert;

import com.demo.amps.connectors.amps.AmpsPublisher;
import com.demo.amps.connectors.amps.HaAmpsPublisher;
import com.demo.amps.connectors.config.AmpsServerProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes every alert, as JSON, onto one AMPS topic.
 *
 * <p>Over the same {@link HaAmpsPublisher} the connectors use, logged on as
 * {@code <prefix>-<application>-alerts} against {@code /amps/json}: an alert is a message
 * like any other, and the HA client's reconnect and publish store are exactly what a
 * destination that must survive the server's restart needs. One client per application,
 * because the application is what the alerts are about.
 *
 * <p>Failure is handled the way an alerts channel has to be: never at the expense of the
 * application. {@link #start()} tries to connect and, if AMPS is not there yet, lets the
 * manager log and count that and keep the sink -- the connectors will be retrying the same
 * server, and the sink connects on the first alert instead. A failed connect costs about
 * {@code logon-timeout} on whichever thread made it, so the next attempt waits
 * {@code reconnect-delay}, and until then a send fails fast and is counted rather than
 * pinning the delivery thread on a server that is not answering.
 */
public final class AmpsAlertSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger(AmpsAlertSink.class);

    private final AmpsServerProperties server;
    private final String topic;
    private final AmpsPublisher publisher;

    /** Compared by difference, the way {@code nanoTime} values have to be. */
    private volatile long nextConnectAttemptNanos = System.nanoTime();

    /**
     * @param server the application's AMPS server block
     * @param application the application name; makes the client name unique
     * @param topic the JSON topic the alerts go onto
     */
    public AmpsAlertSink(AmpsServerProperties server, String application, String topic) {
        this(server, topic, new HaAmpsPublisher(server, application + "-alerts", "json"));
    }

    /** For tests: the same sink over a publisher that records instead of connecting. */
    AmpsAlertSink(AmpsServerProperties server, String topic, AmpsPublisher publisher) {
        this.server = server;
        this.topic = topic;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "amps:" + topic;
    }

    @Override
    public void start() throws Exception {
        ensureConnected();
        log.info("alerts go to {} via {}", topic, publisher);
    }

    @Override
    public void send(Alert alert) throws Exception {
        ensureConnected();
        publisher.publish(topic, AlertJson.write(alert), null);
    }

    @Override
    public void flush() {
        Duration timeout = server.getFlushTimeout();
        if (!publisher.flush(timeout)) {
            throw new IllegalStateException(
                    "alerts on " + topic + " were not acknowledged within " + timeout);
        }
    }

    @Override
    public void close() {
        publisher.close();
    }

    private void ensureConnected() throws Exception {
        if (publisher.isConnected()) {
            return;
        }
        if (System.nanoTime() - nextConnectAttemptNanos < 0) {
            throw new IllegalStateException("not connected to " + server.uri("json")
                    + "; next attempt in " + server.getReconnectDelay());
        }
        try {
            publisher.connect();
        } catch (Exception e) {
            deferNextAttempt();
            throw e;
        }
    }

    private void deferNextAttempt() {
        nextConnectAttemptNanos = System.nanoTime() + server.getReconnectDelay().toNanos();
    }

    @Override
    public String toString() {
        return "AmpsAlertSink[" + topic + " via " + publisher + "]";
    }
}
