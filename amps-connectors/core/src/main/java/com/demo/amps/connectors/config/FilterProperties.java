package com.demo.amps.connectors.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Which of a source's records are worth publishing.
 *
 * <p>Optional, and absent means "all of them". Present, a record passes when the
 * {@link #getRules() rules} agree according to {@link #getMatch() match} <em>and</em> the
 * {@link #getExpression() expression}, if set, is true -- the two are ANDed, not alternatives.
 *
 * <p>Two ways to say the same kind of thing, on purpose. The rule list is the declarative
 * form: readable in a diff, reviewable by someone who does not write Java, and impossible to
 * make slow. The expression is the escape hatch for the conditions a rule list cannot state --
 * relations between fields, arithmetic -- and it is SpEL over {@code #f} (the decoded field
 * map) with {@code #num(x)} and {@code #str(x)} helpers.
 *
 * <pre>{@code
 * filter:
 *   match: ALL
 *   rules:
 *     - { field: "35", in: [D, G, F] }
 *     - { field: "38", gt: 0 }
 *   expression: "#f['35'] == 'D' && #num(#f['38']) > 100"
 * }</pre>
 *
 * <p>Filtering happens before the transforms, so rules address the fields as the <em>source</em>
 * names them, not as the target will.
 */
public class FilterProperties {

    /** How the rule results are combined. */
    public enum Match {
        /** Every rule must pass. */
        ALL,
        /** At least one rule must pass. */
        ANY
    }

    @NotNull
    private Match match = Match.ALL;

    /** The declarative rules; each names exactly one operator. */
    @Valid
    @NotNull
    private List<FilterRule> rules = new ArrayList<>();

    /**
     * A SpEL predicate over {@code #f} (the decoded fields, as {@code Map<String, Object>}),
     * with {@code #num(x)} coercing to double and {@code #str(x)} to String. Parsed once at
     * startup, so a syntax error is a startup failure rather than a per-record surprise.
     */
    private String expression;

    public Match getMatch() {
        return match;
    }

    public void setMatch(Match match) {
        this.match = match;
    }

    public List<FilterRule> getRules() {
        return rules;
    }

    public void setRules(List<FilterRule> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }
}
