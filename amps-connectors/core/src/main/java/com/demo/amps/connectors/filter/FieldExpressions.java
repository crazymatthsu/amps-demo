package com.demo.amps.connectors.filter;

import com.demo.amps.connectors.source.InboundRecord;
import java.lang.reflect.Method;
import java.util.Map;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * The one SpEL dialect this framework exposes to configuration, in one place.
 *
 * <p>Every place that takes an expression -- a filter's {@code expression:}, a
 * {@code derive:} transform, a rule's {@code when:} and its alert {@code message:} -- sees
 * exactly the same thing, because a configuration author should not have to learn two:
 *
 * <ul>
 *   <li>{@code #f} -- the decoded field map, so {@code #f['35']} is a FIX tag and
 *       {@code #f['order']['price']} a nested JSON member</li>
 *   <li>{@code #r} -- the {@link InboundRecord} the fields came from, so {@code #r.key} is the
 *       source's own key, {@code #r.action} is {@code UPSERT} or {@code DELETE}, and
 *       {@code #r.attributes['topic']} is whatever the transport said about the message.
 *       Bound where a record is at hand (transforms and rules); a filter runs before a
 *       delete's key is known to matter and sees {@code #f} only</li>
 *   <li>{@code #num(x)} -- the value as a double, or {@code NaN}</li>
 *   <li>{@code #str(x)} -- the value as text, or {@code ""}</li>
 * </ul>
 *
 * <p>Expressions are parsed once, at startup, so a syntax error stops the application rather
 * than surfacing as a per-record surprise on a Friday. Evaluation failures cannot be dealt
 * with that early, so they are wrapped into an {@link IllegalArgumentException} carrying the
 * expression text: the pipeline counts the record as rejected and the log line says which
 * expression to go and look at.
 *
 * <p>A <em>template</em> is the same dialect inside {@code #{...}} with literal text around
 * it -- {@code "limit order #{#f['11']} has no price"} -- and is what an alert message is,
 * because a message with the order id in it is worth more than a message without. Text with
 * no embedded expression is a literal, so a plain sentence costs nothing per record.
 */
public final class FieldExpressions {

    /** {@code #num} and {@code #str}, resolved once; SpEL registers {@link Method} objects. */
    private static final Method NUM = function("num");
    private static final Method STR = function("str");

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    /** {@code #{...}} inside literal text, the way Spring's own {@code @Value} spells it. */
    private static final ParserContext TEMPLATE = new TemplateParserContext("#{", "}");

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
     * Parse literal text with {@code #{...}} expressions inside it.
     *
     * @param text the template as configured, e.g. {@code "order #{#f['11']} has no price"}
     * @return the compiled template; a plain literal when the text embeds no expression
     * @throws org.springframework.expression.ParseException if an embedded expression does
     *     not parse, or is opened and never closed
     */
    public static Expression parseTemplate(String text) {
        return PARSER.parseExpression(text, TEMPLATE);
    }

    /**
     * A fresh evaluation context for one record's fields.
     *
     * <p>Fresh, not shared: a TCP connector in {@code LISTEN} mode runs the pipeline on one
     * thread per connected client, and a context whose {@code #f} variable is reassigned per
     * record is exactly the state two threads must not share. The cost is two map writes.
     *
     * @param fields the decoded record, bound to {@code #f}
     * @return the context to evaluate in
     */
    public static EvaluationContext context(Map<String, Object> fields) {
        return context(fields, null);
    }

    /**
     * A fresh evaluation context for one record, fields and all.
     *
     * <p>The record is bound as itself: SpEL reads a record component through its accessor
     * ({@code #r.key} calls {@code key()}), so the expression language sees exactly the
     * {@link InboundRecord} a code transform sees, and nothing has to be kept in step with it.
     *
     * @param fields the decoded record, bound to {@code #f}
     * @param record the record the fields came from, bound to {@code #r}; {@code null} leaves
     *     {@code #r} unbound, which a filter -- evaluated on the fields alone -- is fine with
     * @return the context to evaluate in
     */
    public static EvaluationContext context(Map<String, Object> fields, InboundRecord record) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("f", fields);
        if (record != null) {
            context.setVariable("r", record);
        }
        context.registerFunction("num", NUM);
        context.registerFunction("str", STR);
        return context;
    }

    /**
     * Evaluate against one record's fields.
     *
     * @param expression the parsed expression
     * @param text its configured text, for the error message
     * @param fields the decoded record
     * @return whatever it evaluated to, which may be {@code null}
     * @throws IllegalArgumentException if evaluation failed -- a configuration mistake, so the
     *     pipeline counts the record as rejected rather than filtered
     */
    public static Object evaluate(Expression expression, String text, Map<String, Object> fields) {
        return evaluate(expression, text, null, fields);
    }

    /**
     * Evaluate against one record, with {@code #r} bound.
     *
     * @param expression the parsed expression
     * @param text its configured text, for the error message
     * @param record the record the fields came from, for {@code #r}; may be {@code null}
     * @param fields the decoded record, as the earlier steps left it
     * @return whatever it evaluated to, which may be {@code null}
     * @throws IllegalArgumentException if evaluation failed -- a configuration mistake, so the
     *     pipeline counts the record as rejected rather than filtered
     */
    public static Object evaluate(
            Expression expression, String text, InboundRecord record, Map<String, Object> fields) {
        try {
            return expression.getValue(context(fields, record));
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
        return test(expression, text, null, fields);
    }

    /**
     * Evaluate as a predicate, with {@code #r} bound.
     *
     * @param expression the parsed expression
     * @param text its configured text, for the error message
     * @param record the record the fields came from, for {@code #r}; may be {@code null}
     * @param fields the decoded record, as the earlier steps left it
     * @return the boolean it evaluated to
     * @throws IllegalArgumentException if it failed or did not answer a boolean
     */
    public static boolean test(
            Expression expression, String text, InboundRecord record, Map<String, Object> fields) {
        Object result = evaluate(expression, text, record, fields);
        if (result instanceof Boolean verdict) {
            return verdict;
        }
        throw new IllegalArgumentException("expression [" + text + "] answered "
                + (result == null ? "null" : result.getClass().getSimpleName())
                + " rather than a boolean");
    }

    /**
     * Render a template for one record.
     *
     * @param template the parsed template, from {@link #parseTemplate}
     * @param text its configured text, for the error message
     * @param record the record the fields came from, for {@code #r}; may be {@code null}
     * @param fields the decoded record, as the earlier steps left it
     * @return the text with every {@code #{...}} replaced by what it evaluated to; a null
     *     result renders as {@code ""}
     * @throws IllegalArgumentException if an embedded expression failed
     */
    public static String render(
            Expression template, String text, InboundRecord record, Map<String, Object> fields) {
        try {
            String rendered = template.getValue(context(fields, record), String.class);
            return rendered == null ? "" : rendered;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "template [" + text + "] failed: " + e.getMessage(), e);
        }
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
