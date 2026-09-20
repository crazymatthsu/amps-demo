package com.demo.amps.connectors.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One rule of a {@code rules:} transform step: a condition over the record and what to do
 * when it holds.
 *
 * <pre>{@code
 * transforms:
 *   - rules:
 *       - name: limit-without-price
 *         when: "#f['40'] == '2' && !#f.containsKey('44')"
 *         then: { alert: { severity: WARN, code: LIMIT_WITHOUT_PRICE,
 *                          message: "limit order #{#f['11']} has no price" } }
 *       - name: large-notional
 *         when: "#num(#f['38']) * #num(#f['44']) > 1000000"
 *         then: { set: { "5001": LARGE } }
 *         stop: false
 * }</pre>
 *
 * <p>The name is not decoration. It is the key of the rule's hit counter on the connector's
 * status line ({@code rules[limit-without-price=12,large-notional=3]}), the {@code rule}
 * detail on every alert the rule raises, and the word an operator uses to say which line of
 * the configuration fired -- so it is required and unique within its step.
 *
 * <p>{@code when} is SpEL in the same dialect as a filter's {@code expression:} and a
 * {@code derive:}, with {@code #r} (the record) bound as well as {@code #f} (the fields as
 * the earlier steps and the earlier <em>rules</em> left them: a {@code set} by one rule is
 * visible to the next). Rules run in order; {@code stop: true} ends the step after this rule
 * hits, for the "first match wins" shape a code table wants.
 */
public class RuleProperties {

    /** The rule's name: its counter, its alert detail, and its line in the log. Unique per step. */
    @NotBlank
    private String name;

    /** The SpEL predicate over {@code #f} and {@code #r}; a non-boolean answer is a rejection. */
    @NotBlank
    private String when;

    /** What happens when {@code when} holds; at least one action. */
    @Valid
    @NotNull
    private RuleThen then = new RuleThen();

    /** {@code true} ends the step after this rule hits: later rules are not evaluated. */
    private boolean stop = false;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWhen() {
        return when;
    }

    public void setWhen(String when) {
        this.when = when;
    }

    public RuleThen getThen() {
        return then;
    }

    public void setThen(RuleThen then) {
        this.then = then == null ? new RuleThen() : then;
    }

    public boolean isStop() {
        return stop;
    }

    public void setStop(boolean stop) {
        this.stop = stop;
    }

    @Override
    public String toString() {
        return "rule '" + name + "'";
    }
}
