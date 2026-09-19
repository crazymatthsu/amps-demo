package com.demo.amps.connectors.filter;

import java.lang.reflect.Method;
import java.util.Map;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * The one SpEL dialect this framework exposes to configuration, in one place.
 *
 * <p>Both places that take an expression -- a filter's {@code expression:} and a
 * {@code derive:} transform -- see exactly the same thing, because a configuration author
 * should not have to learn two:
 *
 * <ul>
 *   <li>{@code #f} -- the decoded field map, so {@code #f['35']} is a FIX tag and
 *       {@code #f['order']['price']} a nested JSON member</li>
 *   <li>{@code #num(x)} -- the value as a double, or {@code NaN}</li>
 *   <li>{@code #str(x)} -- the value as text, or {@code ""}</li>
 * </ul>
 *
 * <p>Expressions are parsed once, at startup, so a syntax error stops the application rather
 * than surfacing as a per-record surprise on a Friday. Evaluation failures cannot be dealt
 * with that early, so they are wrapped into an {@link IllegalArgumentException} carrying the
 * expression text: the pipeline counts the record as rejected and the log line says which
 * expression to go and look at.
 */
public final class FieldExpressions {

    /** {@code #num} and {@code #str}, resolved once; SpEL registers {@link Method} objects. */
    private static final Method NUM = function("num");
    private static final Method STR = function("str");

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private FieldExpressions() {
    }

    /**
     * @param text the expression as configured
     * @return the compiled expression
     * @throws org.springframework.expression.ParseException if it does not parse
     */
    public static Expression parse(String text) {
        return PARSER.parseExpression(text);
    }

    /**
     * A fresh evaluation context for one record.
     *
     * <p>Fresh, not shared: a TCP connector in {@code LISTEN} mode runs the pipeline on one
     * thread per connected client, and a context whose {@code #f} variable is reassigned per
     * record is exactly the state two threads must not share. The cost is two map writes.
     *
     * @param fields the decoded record, bound to {@code #f}
     * @return the context to evaluate in
     */
    public static EvaluationContext context(Map<String, Object> fields) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("f", fields);
        context.registerFunction("num", NUM);
        context.registerFunction("str", STR);
        return context;
    }

    /**
     * Evaluate against one record.
     *
     * @param expression the parsed expression
     * @param text its configured text, for the error message
     * @param fields the decoded record
     * @return whatever it evaluated to, which may be {@code null}
     * @throws IllegalArgumentException if evaluation failed -- a configuration mistake, so the
     *     pipeline counts the record as rejected rather than filtered
     */
    public static Object evaluate(Expression expression, String text, Map<String, Object> fields) {
        try {
            return expression.getValue(context(fields));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "expression [" + text + "] failed: " + e.getMessage(), e);
        }
    }

    /**
     * Evaluate as a predicate.
     *
     * @param expression the parsed expression
     * @param text its configured text, for the error message
     * @param fields the decoded record
     * @return the boolean it evaluated to
     * @throws IllegalArgumentException if it failed or did not answer a boolean
     */
    public static boolean test(Expression expression, String text, Map<String, Object> fields) {
        Object result = evaluate(expression, text, fields);
        if (result instanceof Boolean verdict) {
            return verdict;
        }
        throw new IllegalArgumentException("expression [" + text + "] answered "
                + (result == null ? "null" : result.getClass().getSimpleName())
                + " rather than a boolean");
    }

    /**
     * {@code #num(x)} -- a value as a double.
     *
     * <p>Anything that is not a number is {@code NaN}, not zero, because every comparison
     * against NaN is false: a missing or non-numeric field makes {@code #num(#f['38']) > 100}
     * false <em>and</em> {@code < 100} false, which is what "no opinion" should look like.
     * Zero would quietly satisfy half of them.
     *
     * @param value a decoded field value
     * @return its numeric value, or {@code NaN}
     */
    public static double num(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * {@code #str(x)} -- a value as text.
     *
     * @param value a decoded field value
     * @return its string form; {@code ""} for an absent or null field, so an expression can
     *     call {@code startsWith} on it without a null check. Ask about absence with a
     *     {@code present} rule instead
     */
    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Method function(String name) {
        try {
            return FieldExpressions.class.getDeclaredMethod(name, Object.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("expression helper #" + name + " is missing", e);
        }
    }
}
