package com.demo.amps.connectors.transform.rules;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.config.RuleAlert;
import com.demo.amps.connectors.config.RuleProperties;
import com.demo.amps.connectors.config.RuleThen;
import com.demo.amps.connectors.decode.Fields;
import com.demo.amps.connectors.filter.FieldExpressions;
import com.demo.amps.connectors.source.InboundRecord;
import com.demo.amps.connectors.transform.RecordTransform;
import com.demo.amps.connectors.transform.TransformContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.expression.Expression;

/**
 * A connector's {@code rules:} step, compiled once and evaluated in order over every record.
 *
 * <p>A rule is a {@code when} -- SpEL over {@code #f} (the fields) and {@code #r} (the
 * record) -- and a {@code then} that runs when it holds, in the one order that makes sense:
 * {@code set} the fields, run the {@code bean}, raise the {@code alert}, and only then
 * {@code drop}. The alert comes before the drop on purpose: a record that a rule discards is
 * exactly the record somebody wants to hear about, and it is the one that will never reach
 * the topic to be seen there. Rules see the fields as the rules before them left them, so a
 * {@code set} by one rule is a fact the next can test; {@code stop: true} ends the step after
 * a hit, which is how "first match wins" is spelled.
 *
 * <p>Why a step and not a {@code RuleManager} the application owns: a rule's hit counter
 * belongs to the connector whose records it counted. Two connectors naming a rule
 * {@code large-notional} are two lines in the status log, not one number, and that is only
 * true when the rules live in the pipeline they run in. Per connector, per step, the
 * counters are {@link #summary()}: {@code rules[limit-without-price=12,large-notional=3]}.
 *
 * <p>Failures follow the pipeline's existing distinction. An expression that cannot be
 * evaluated -- a method that does not exist on the value it was given -- is an
 * {@link IllegalArgumentException} the pipeline counts as <em>rejected</em>, like a
 * {@code derive} that fails; a rule that drops is counted as <em>dropped</em>, like any
 * transform returning {@code null}. The input map is never written to: every {@code set}
 * lands in a copy -- {@link Fields#copy}, so a typed record's view is cloned rather than
 * flattened -- and the chain's "each step gets its own map" contract holds inside the step
 * as well as between steps.
 */
public final class RuleSet implements RecordTransform {

    /**
     * One rule's hit count, for the status line and the tests.
     *
     * @param name the rule's name
     * @param hits how many records its {@code when} held for
     */
    public record RuleStats(String name, long hits) {
    }

    private final String connectorName;
    private final Alerts alerts;
    private final List<CompiledRule> rules;

    private RuleSet(String connectorName, Alerts alerts, List<CompiledRule> rules) {
        this.connectorName = connectorName;
        this.alerts = alerts;
        this.rules = List.copyOf(rules);
    }

    /**
     * Compile a step's rules.
     *
     * <p>Everything that can be wrong with the configuration is wrong here, at connector
     * start: a blank or duplicate name, a {@code when} that does not parse, a {@code then}
     * that does nothing, an alert with no code, a message template that does not close, a
     * bean that is not registered.
     *
     * @param rules the step's {@code rules:} list, in order
     * @param context the connector the step belongs to, the beans, and where to raise
     * @return the compiled step
     * @throws IllegalArgumentException if a rule is malformed
     * @throws IllegalStateException if a {@code bean} action names an unregistered bean
     * @throws org.springframework.expression.ParseException if a {@code when} or a message
     *     template does not parse
     */
    public static RuleSet compile(List<RuleProperties> rules, TransformContext context) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("a rules step lists no rules");
        }
        List<CompiledRule> compiled = new ArrayList<>(rules.size());
        Set<String> names = new HashSet<>();
        for (RuleProperties rule : rules) {
            String name = rule.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a rule has no name, and the name is its "
                        + "counter and its alert detail");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate rule name '" + name
                        + "' in one rules step: the two would share a counter");
            }
            compiled.add(compile(rule, context));
        }
        String connector = context.connectorName();
        return new RuleSet(connector.isBlank() ? null : connector, context.alerts(), compiled);
    }

    private static CompiledRule compile(RuleProperties rule, TransformContext context) {
        String name = rule.getName();
        String when = rule.getWhen();
        if (when == null || when.isBlank()) {
            throw new IllegalArgumentException("rule '" + name + "' has no when");
        }
        RuleThen then = rule.getThen();
        if (then == null || then.configuredActions().isEmpty()) {
            throw new IllegalArgumentException("rule '" + name + "' names no action "
                    + "(set/bean/alert/drop), so it would do nothing but count");
        }
        RecordTransform bean = null;
        if (then.getBean() != null) {
            if (then.getBean().isBlank()) {
                throw new IllegalArgumentException("rule '" + name + "' names a blank bean");
            }
            bean = context.registry().require(then.getBean()).bind(context);
        }
        AlertTemplate alert = null;
        if (then.getAlert() != null) {
            RuleAlert configured = then.getAlert();
            if (configured.getCode() == null || configured.getCode().isBlank()) {
                throw new IllegalArgumentException("rule '" + name + "': alert needs a code, "
                        + "the stable name a reader groups by");
            }
            String message = configured.getMessage() == null || configured.getMessage().isBlank()
                    ? name
                    : configured.getMessage();
            Alert.Severity severity = configured.getSeverity() == null
                    ? Alert.Severity.WARN
                    : configured.getSeverity();
            alert = new AlertTemplate(severity, configured.getCode().trim(), message,
                    FieldExpressions.parseTemplate(message));
        }
        return new CompiledRule(
                name, when, FieldExpressions.parse(when),
                then.getSet() == null ? null : new LinkedHashMap<>(then.getSet()),
                bean, alert, then.isDrop(), rule.isStop());
    }

    @Override
    public Map<String, Object> apply(InboundRecord record, Map<String, Object> fields) {
        Map<String, Object> current = fields;
        for (CompiledRule rule : rules) {
            if (!FieldExpressions.test(rule.when, rule.whenText, record, current)) {
                continue;
            }
            rule.hits.incrementAndGet();
            if (rule.set != null) {
                Map<String, Object> written = Fields.copy(current);
                rule.set.forEach((field, value) -> Fields.put(written, field, value));
                current = written;
            }
            Map<String, Object> next = current;
            if (rule.bean != null) {
                next = rule.bean.apply(record, current);
            }
            if (rule.alert != null) {
                // Rendered over the fields the bean produced when it produced any, else the
                // fields as the set left them: the message describes the record as the rule
                // leaves it, which is also what the drop below discards.
                alerts.raise(rule.alert.render(record, next == null ? current : next)
                        .withConnector(connectorName)
                        .withDetails(Map.of("rule", rule.name)));
            }
            if (rule.drop || next == null) {
                return null;
            }
            current = next;
            if (rule.stop) {
                break;
            }
        }
        return current;
    }

    /** How many rules the step holds. */
    public int size() {
        return rules.size();
    }

    /** Every rule's hit count, in configuration order. */
    public List<RuleStats> rules() {
        List<RuleStats> stats = new ArrayList<>(rules.size());
        for (CompiledRule rule : rules) {
            stats.add(new RuleStats(rule.name, rule.hits.get()));
        }
        return stats;
    }

    /**
     * The counters as the status line prints them.
     *
     * @return e.g. {@code rules[limit-without-price=12,large-notional=3]}
     */
    public String summary() {
        StringJoiner text = new StringJoiner(",", "rules[", "]");
        for (CompiledRule rule : rules) {
            text.add(rule.name + "=" + rule.hits.get());
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return summary();
    }

    /** One rule, parsed and resolved, with its counter. */
    private static final class CompiledRule {

        private final String name;
        private final String whenText;
        private final Expression when;
        private final Map<String, String> set;
        private final RecordTransform bean;
        private final AlertTemplate alert;
        private final boolean drop;
        private final boolean stop;
        private final AtomicLong hits = new AtomicLong();

        CompiledRule(
                String name, String whenText, Expression when, Map<String, String> set,
                RecordTransform bean, AlertTemplate alert, boolean drop, boolean stop) {
            this.name = name;
            this.whenText = whenText;
            this.when = when;
            this.set = set;
            this.bean = bean;
            this.alert = alert;
            this.drop = drop;
            this.stop = stop;
        }
    }

    /** An alert with its message still to be rendered over a record. */
    private record AlertTemplate(
            Alert.Severity severity, String code, String text, Expression template) {

        Alert render(InboundRecord record, Map<String, Object> fields) {
            return Alert.of(
                    severity, code, FieldExpressions.render(template, text, record, fields));
        }
    }
}
