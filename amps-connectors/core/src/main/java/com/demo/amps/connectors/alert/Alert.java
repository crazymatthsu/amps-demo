package com.demo.amps.connectors.alert;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One thing that went wrong (or, for {@link Severity#INFO}, one thing worth saying), as the
 * record every sink serialises and every reader of the alerts topic parses.
 *
 * <p>Two fields carry the identity and the rest carry the story. {@code code} is a stable,
 * machine-readable name ({@code UNKNOWN_SYMBOL}, {@code PUBLISH_FLUSH_TIMEOUT}) that a
 * dashboard groups by and that repeat suppression keys on together with {@code connector};
 * {@code message} is the sentence for a human, and {@code details} is whatever a human would
 * ask for next -- the symbol, the order id, the exception text. The message is never parsed,
 * so it can say what it likes; the code is never prose, so it can be matched.
 *
 * <p>{@code timestamp} and {@code application} are {@code null} until the {@link AlertManager}
 * stamps them on the way in: the code that raises an alert knows what happened, and the
 * manager knows when and on whose behalf. A record rather than a class so that stamping is a
 * copy, and an alert handed to two sinks is the same immutable value in both.
 *
 * @param timestamp when it was raised; stamped by the manager from its clock
 * @param application the application it was raised in; stamped by the manager
 * @param severity how bad
 * @param code the stable, machine-readable name
 * @param message the sentence for a human
 * @param connector the connector it concerns, or {@code null} for an application-level alert
 * @param repeats how many identical alerts were collapsed into this one by repeat
 *     suppression; {@code 0} for a first occurrence
 * @param details what a reader would ask for next, in insertion order
 */
public record Alert(
        Instant timestamp,
        String application,
        Severity severity,
        String code,
        String message,
        String connector,
        int repeats,
        Map<String, Object> details) {

    /** How bad, in order -- the order {@code min-severity} filters by. */
    public enum Severity {
        /** Worth recording, nothing to fix. A status report, a reload that succeeded. */
        INFO,
        /** Degraded or lossy, and carrying on: a symbol nobody knows, a flush that timed out. */
        WARN,
        /** Broken: a publish that threw, a resource that will not load. */
        ERROR
    }

    public Alert {
        Objects.requireNonNull(severity, "severity");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("an alert needs a code");
        }
        message = message == null ? "" : message;
        details = details == null || details.isEmpty()
                ? Map.of()
                // Not Map.copyOf: a detail is allowed to be null (an order with no id), and
                // insertion order is what makes the JSON read the way it was written.
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /**
     * A fresh, unstamped alert.
     *
     * @param severity how bad
     * @param code the stable name, e.g. {@code UNKNOWN_SYMBOL}
     * @param message the sentence for a human
     * @return the alert, with no connector, no details and no timestamp yet
     */
    public static Alert of(Severity severity, String code, String message) {
        return new Alert(null, null, severity, code, message, null, 0, Map.of());
    }

    /** The same alert, attributed to a connector. */
    public Alert withConnector(String connector) {
        return new Alert(timestamp, application, severity, code, message, connector, repeats,
                details);
    }

    /** The same alert, with these details (replacing any it had). */
    public Alert withDetails(Map<String, Object> details) {
        return new Alert(timestamp, application, severity, code, message, connector, repeats,
                details);
    }

    /** The same alert, saying how many identical ones it stands for. */
    public Alert withRepeats(int repeats) {
        return new Alert(timestamp, application, severity, code, message, connector, repeats,
                details);
    }

    /** The same alert, stamped with when it was raised. */
    public Alert withTimestamp(Instant timestamp) {
        return new Alert(timestamp, application, severity, code, message, connector, repeats,
                details);
    }

    /** The same alert, stamped with the application it was raised in. */
    public Alert withApplication(String application) {
        return new Alert(timestamp, application, severity, code, message, connector, repeats,
                details);
    }

    /**
     * The one-line form the log carries: severity, code, connector, message, and the details
     * when there are any.
     *
     * @return e.g. {@code WARN UNKNOWN_SYMBOL [orders-enriched] symbol XYZ is not in
     *     instruments (repeats=12) {symbol=XYZ, clOrdId=ORD-7}}
     */
    public String summary() {
        StringBuilder text = new StringBuilder()
                .append(severity).append(' ').append(code);
        if (connector != null) {
            text.append(" [").append(connector).append(']');
        }
        if (!message.isEmpty()) {
            text.append(' ').append(message);
        }
        if (repeats > 0) {
            text.append(" (repeats=").append(repeats).append(')');
        }
        if (!details.isEmpty()) {
            text.append(' ').append(details);
        }
        return text.toString();
    }
}
