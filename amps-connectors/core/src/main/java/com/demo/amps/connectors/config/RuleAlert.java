package com.demo.amps.connectors.config;

import com.demo.amps.connectors.alert.Alert;
import jakarta.validation.constraints.NotNull;

/**
 * The alert a rule raises: a severity, a code, and a message that is a template over the
 * record.
 *
 * <pre>{@code
 * alert: { severity: WARN, code: LIMIT_WITHOUT_PRICE,
 *          message: "limit order #{#f['11']} has no price" }
 * }</pre>
 *
 * <p>The code is required because it is the alert's identity: what a dashboard groups by
 * and what repeat suppression keys on (with the connector), so a rule that fires a thousand
 * times in a window is one alert and a summary rather than a thousand. The message is for
 * the human reading it, and {@code #{...}} inside it is evaluated per record in the same
 * dialect as {@code when} -- the order id in the message is what saves the reader a query.
 * Absent, the message is the rule's name. The alert carries the connector and one detail,
 * {@code rule}, so the two identities -- which connector, which line of configuration --
 * are both there to match on.
 */
public class RuleAlert {

    /** How bad; {@code WARN} unless said otherwise, because a rule that fires is news. */
    @NotNull
    private Alert.Severity severity = Alert.Severity.WARN;

    /** The alert code: stable, machine-readable, required. */
    private String code;

    /**
     * A template over the record ({@code #{#f['11']}}), or a plain sentence; absent, the
     * rule's name.
     */
    private String message;

    public Alert.Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Alert.Severity severity) {
        this.severity = severity;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
