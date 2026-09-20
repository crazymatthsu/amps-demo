package com.demo.amps.connectors.alert;

import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.connectors.amps.AmpsPublisher;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An {@link AmpsPublisher} that raises an alert when the one underneath fails, and otherwise
 * stays out of the way.
 *
 * <p>A decorator rather than a change to {@code BatchPublisher}, which already knows what a
 * failed flush means for acknowledgments and logs it: that class is about the at-least-once
 * contract, and it does not need to know that anybody is listening. Wrapping the publisher
 * inside {@code Connector} puts the alert exactly where the failure is first known, with the
 * topic and the operation to hand, and leaves the batching code untouched.
 *
 * <p>Two codes, at two severities, because they are two different situations. A flush that
 * returns {@code false} ({@code PUBLISH_FLUSH_TIMEOUT}, WARN) is not data loss: the publish
 * store still holds everything and replays it after the reconnect, the batch's records just
 * stay unacknowledged and will be re-read. A publish that <em>throws</em>
 * ({@code PUBLISH_FAILED}, ERROR) is the client refusing the command outright, and the
 * exception is rethrown after the alert so the batch fails the way it always has.
 */
public final class AlertingAmpsPublisher implements AmpsPublisher {

    /** Raised, at WARN, when a flush does not complete within its timeout. */
    public static final String PUBLISH_FLUSH_TIMEOUT = "PUBLISH_FLUSH_TIMEOUT";

    /** Raised, at ERROR, when a publish or a delete throws; the exception is rethrown. */
    public static final String PUBLISH_FAILED = "PUBLISH_FAILED";

    private final AmpsPublisher delegate;
    private final String connector;
    private final Alerts alerts;

    /**
     * @param delegate the real publisher
     * @param connector the connector's name, which every alert is attributed to
     * @param alerts where the alerts go
     */
    public AlertingAmpsPublisher(AmpsPublisher delegate, String connector, Alerts alerts) {
        this.delegate = delegate;
        this.connector = connector;
        this.alerts = alerts;
    }

    /** The publisher underneath, for a test that wants its recording. */
    public AmpsPublisher delegate() {
        return delegate;
    }

    @Override
    public void connect() throws AMPSException {
        // No alert of its own: a connector that cannot connect fails to start, and the
        // manager raises CONNECTOR_START_FAILED with the same exception.
        delegate.connect();
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void publish(String topic, String data, String sowKey) {
        try {
            delegate.publish(topic, data, sowKey);
        } catch (RuntimeException e) {
            throw failed("publish", topic, e);
        }
    }

    @Override
    public void deltaPublish(String topic, String data, String sowKey) {
        try {
            delegate.deltaPublish(topic, data, sowKey);
        } catch (RuntimeException e) {
            throw failed("delta_publish", topic, e);
        }
    }

    @Override
    public void sowDeleteByKey(String topic, String sowKey) {
        try {
            delegate.sowDeleteByKey(topic, sowKey);
        } catch (RuntimeException e) {
            throw failed("sow_delete", topic, e);
        }
    }

    @Override
    public void sowDeleteByFilter(String topic, String filter) {
        try {
            delegate.sowDeleteByFilter(topic, filter);
        } catch (RuntimeException e) {
            throw failed("sow_delete", topic, e);
        }
    }

    @Override
    public boolean flush(Duration timeout) {
        boolean persisted = delegate.flush(timeout);
        if (!persisted) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("timeout", String.valueOf(timeout));
            alerts.raise(Alert.of(Alert.Severity.WARN, PUBLISH_FLUSH_TIMEOUT,
                            "flush did not complete within " + timeout
                                    + "; the batch stays unacknowledged and will be re-read")
                    .withConnector(connector)
                    .withDetails(details));
        }
        return persisted;
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** Raise {@code PUBLISH_FAILED} and hand the exception back to be thrown. */
    private RuntimeException failed(String operation, String topic, RuntimeException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operation", operation);
        details.put("topic", topic);
        details.put("error", e.toString());
        alerts.raise(Alert.of(Alert.Severity.ERROR, PUBLISH_FAILED,
                        operation + " to " + topic + " failed: " + e.getMessage())
                .withConnector(connector)
                .withDetails(details));
        return e;
    }

    @Override
    public String toString() {
        return "AlertingAmpsPublisher[" + delegate + "]";
    }
}
