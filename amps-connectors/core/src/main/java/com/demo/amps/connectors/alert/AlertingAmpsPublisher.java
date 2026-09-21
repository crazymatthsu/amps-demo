package com.demo.amps.connectors.alert;

import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.fields.ReasonField;
import com.demo.amps.connectors.amps.AmpsPublisher;
import com.demo.amps.connectors.amps.PublishListener;
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
 * <p>Three codes, at two severities, because they are three different situations. A flush
 * that returns {@code false} ({@code PUBLISH_FLUSH_TIMEOUT}, WARN) is not data loss: the
 * publish store still holds everything and replays it after the reconnect, the batch's
 * records just stay unacknowledged and will be re-read. A publish that <em>throws</em>
 * ({@code PUBLISH_FAILED}, ERROR) is the client refusing the command outright, and the
 * exception is rethrown after the alert so the batch fails the way it always has. A publish
 * the <em>server</em> refuses after the client accepted it ({@code PUBLISH_REJECTED}, ERROR)
 * arrives later, on the receive thread, as a failed write: the client discards it, nothing
 * retries it, and the record is acknowledged to its source all the same -- which is exactly
 * why it has to be an alert, because no counter on the batch ever fails over it. A duplicate
 * is not one of those: it is a replayed publish the server already had, the normal aftermath
 * of a reconnect, and it passes to the listener without a word.
 */
public final class AlertingAmpsPublisher implements AmpsPublisher {

    /** Raised, at WARN, when a flush does not complete within its timeout. */
    public static final String PUBLISH_FLUSH_TIMEOUT = "PUBLISH_FLUSH_TIMEOUT";

    /** Raised, at ERROR, when a publish or a delete throws; the exception is rethrown. */
    public static final String PUBLISH_FAILED = "PUBLISH_FAILED";

    /**
     * Raised, at ERROR, when the server refuses a publish the client had accepted -- a failed
     * write other than a duplicate. The record is acknowledged to its source and not retried.
     */
    public static final String PUBLISH_REJECTED = "PUBLISH_REJECTED";

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

    /**
     * {@inheritDoc}
     *
     * <p>The listener underneath hears everything; on the way, a failed write that is not a
     * duplicate is raised as {@code PUBLISH_REJECTED}.
     */
    @Override
    public void setPublishListener(PublishListener listener) {
        delegate.setPublishListener(listener == null ? null : new PublishListener() {
            @Override
            public void persistedUpTo(long seqno) {
                listener.persistedUpTo(seqno);
            }

            @Override
            public void failedWrite(long seqno, int reason) {
                if (reason != Message.Reason.Duplicate) {
                    rejected(seqno, reason);
                }
                listener.failedWrite(seqno, reason);
            }
        });
    }

    /** Raise {@code PUBLISH_REJECTED} for a write the server refused. */
    private void rejected(long seqno, int reason) {
        String reasonText = ReasonField.encodeReason(reason);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("seqno", seqno);
        details.put("reason", reason);
        details.put("reasonText", reasonText);
        alerts.raise(Alert.of(Alert.Severity.ERROR, PUBLISH_REJECTED,
                        "AMPS refused publish " + seqno + " (" + reasonText + "); the record "
                                + "is acknowledged to its source and not retried")
                .withConnector(connector)
                .withDetails(details));
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public long publish(String topic, Object data, String sowKey) {
        try {
            return delegate.publish(topic, data, sowKey);
        } catch (RuntimeException e) {
            throw failed("publish", topic, e);
        }
    }

    @Override
    public long deltaPublish(String topic, Object data, String sowKey) {
        try {
            return delegate.deltaPublish(topic, data, sowKey);
        } catch (RuntimeException e) {
            throw failed("delta_publish", topic, e);
        }
    }

    @Override
    public long sowDeleteByKey(String topic, String sowKey) {
        try {
            return delegate.sowDeleteByKey(topic, sowKey);
        } catch (RuntimeException e) {
            throw failed("sow_delete", topic, e);
        }
    }

    @Override
    public long sowDeleteByFilter(String topic, String filter) {
        try {
            return delegate.sowDeleteByFilter(topic, filter);
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
