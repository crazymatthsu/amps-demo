package com.demo.amps.connectors.filter;

import com.demo.amps.connectors.config.FilterProperties;
import com.demo.amps.connectors.config.FilterRule;
import com.demo.amps.connectors.decode.Fields;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.expression.Expression;

/**
 * Decides which decoded records are worth publishing.
 *
 * <p>Everything expensive happens once, in the constructor: each rule becomes a
 * {@link Predicate} with its regular expression compiled and its numeric operand parsed, and
 * the optional SpEL expression is parsed. Per record the filter is a walk over a handful of
 * closures, which matters because it runs on the source's reader thread for every message the
 * feed produces -- including all the ones it is about to discard.
 *
 * <p>A record passes when the rules agree according to {@code match} (ALL or ANY)
 * <em>and</em> the expression, if set, evaluates true. An empty rule list is vacuously true
 * for both, so a filter with only an expression behaves the way it reads.
 *
 * <p>The two ways a rule can be false are deliberately the same way: a field that is absent
 * and a field whose value does not parse as a number both make a numeric rule false rather
 * than raising anything. A filter is a statement about which records are worth keeping, and
 * "not this one" is the honest answer to both. An <em>expression</em> is different -- a SpEL
 * error or a non-Boolean result is a configuration mistake, not a verdict, so it throws and
 * the pipeline counts the record as rejected instead of filtered. Those two counters answer
 * different questions when a topic looks emptier than expected.
 */
public final class RecordFilter {

    private final FilterProperties.Match match;
    private final List<Predicate<Map<String, Object>>> rules;
    private final Expression expression;
    private final String expressionText;

    /**
     * @param properties the connector's {@code filter:} block
     * @throws IllegalArgumentException if a rule does not name exactly one operator, or its
     *     numeric operand is not a number
     * @throws org.springframework.expression.ParseException if the expression does not parse
     */
    public RecordFilter(FilterProperties properties) {
        this.match = properties.getMatch();
        List<Predicate<Map<String, Object>>> compiled = new ArrayList<>();
        for (FilterRule rule : properties.getRules()) {
            compiled.add(compile(rule));
        }
        this.rules = List.copyOf(compiled);
        this.expressionText = properties.getExpression();
        this.expression = expressionText == null || expressionText.isBlank()
                ? null
                : FieldExpressions.parse(expressionText);
    }

    /**
     * @param fields the decoded record
     * @return {@code true} when the record should be published
     * @throws IllegalArgumentException if the expression failed or did not answer a boolean --
     *     a configuration mistake, counted as a rejection rather than as a filtered record
     */
    public boolean accepts(Map<String, Object> fields) {
        if (!rulesAccept(fields)) {
            return false;
        }
        return expression == null || FieldExpressions.test(expression, expressionText, fields);
    }

    private boolean rulesAccept(Map<String, Object> fields) {
        if (rules.isEmpty()) {
            return true;
        }
        if (match == FilterProperties.Match.ANY) {
            for (Predicate<Map<String, Object>> rule : rules) {
                if (rule.test(fields)) {
                    return true;
                }
            }
            return false;
        }
        for (Predicate<Map<String, Object>> rule : rules) {
            if (!rule.test(fields)) {
                return false;
            }
        }
        return true;
    }

    /** One rule, with its regex compiled and its operand parsed, as a predicate. */
    private static Predicate<Map<String, Object>> compile(FilterRule rule) {
        String field = rule.getField();
        Set<String> operators = rule.configuredOperators();
        if (operators.size() != 1) {
            throw new IllegalArgumentException("filter rule on field '" + field + "' names "
                    + (operators.isEmpty() ? "no operator" : "operators " + operators)
                    + ", but a rule names exactly one");
        }
        if (rule.getEquals() != null) {
            String expected = rule.getEquals();
            return fields -> expected.equals(text(fields, field));
        }
        if (rule.getNotEquals() != null) {
            String expected = rule.getNotEquals();
            return fields -> !expected.equals(text(fields, field));
        }
        if (rule.getIn() != null) {
            Set<String> allowed = new LinkedHashSet<>(rule.getIn());
            return fields -> {
                String value = text(fields, field);
                return value != null && allowed.contains(value);
            };
        }
        if (rule.getMatches() != null) {
            Pattern pattern = Pattern.compile(rule.getMatches());
            return fields -> {
                String value = text(fields, field);
                return value != null && pattern.matcher(value).matches();
            };
        }
        if (rule.getPresent() != null) {
            boolean expected = rule.getPresent();
            return fields -> Fields.contains(fields, field) == expected;
        }
        return numeric(rule, field);
    }

    /** The four numeric rules; the operand is parsed here, once, never per record. */
    private static Predicate<Map<String, Object>> numeric(FilterRule rule, String field) {
        String operator;
        String operandText;
        if (rule.getGt() != null) {
            operator = "gt";
            operandText = rule.getGt();
        } else if (rule.getGte() != null) {
            operator = "gte";
            operandText = rule.getGte();
        } else if (rule.getLt() != null) {
            operator = "lt";
            operandText = rule.getLt();
        } else {
            operator = "lte";
            operandText = rule.getLte();
        }
        double operand;
        try {
            operand = Double.parseDouble(operandText.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("filter rule on field '" + field + "': "
                    + operator + " operand '" + operandText + "' is not a number", e);
        }
        return switch (operator) {
            case "gt" -> fields -> FieldExpressions.num(Fields.get(fields, field)) > operand;
            case "gte" -> fields -> FieldExpressions.num(Fields.get(fields, field)) >= operand;
            case "lt" -> fields -> FieldExpressions.num(Fields.get(fields, field)) < operand;
            default -> fields -> FieldExpressions.num(Fields.get(fields, field)) <= operand;
        };
    }

    private static String text(Map<String, Object> fields, String field) {
        return Fields.text(Fields.get(fields, field));
    }
}
