package com.demo.amps.connectors.config;

import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a rule does when its {@code when} holds. Unlike a transform step, a {@code then} may
 * name several actions, because they are not alternatives that need an order chosen between
 * them: they always run in the one order that makes sense.
 *
 * <ol>
 *   <li>{@link #getSet() set} -- write literal fields, so that whatever follows sees them</li>
 *   <li>{@link #getBean() bean} -- run a {@code RecordTransform} bean over the record</li>
 *   <li>{@link #getAlert() alert} -- say so, with the record's details in the message</li>
 *   <li>{@link #isDrop() drop} -- and only then discard the record, so the alert still
 *       fires about a record nobody will see on the topic</li>
 * </ol>
 *
 * <p>A {@code then} with no action at all is refused by the validator: a rule that fires
 * and does nothing is a rule whose counter is its only effect, and a counter is what
 * {@code filter:} rules and the pipeline's own already provide.
 */
public class RuleThen {

    /** Fields to write, as literals; an existing value is overwritten. */
    private Map<String, String> set;

    /** {@code true} discards the record, after the alert. Counted as {@code dropped}. */
    private boolean drop = false;

    /** An alert to raise, with a message that may be a template over the record. */
    @Valid
    private RuleAlert alert;

    /** The name of a {@code RecordTransform} bean to run over the record. */
    private String bean;

    /**
     * The actions this {@code then} configures, in the order they run.
     *
     * @return e.g. {@code ["set", "alert"]}; empty for a rule that would do nothing
     */
    public Set<String> configuredActions() {
        Set<String> actions = new LinkedHashSet<>();
        if (set != null) {
            actions.add("set");
        }
        if (bean != null) {
            actions.add("bean");
        }
        if (alert != null) {
            actions.add("alert");
        }
        if (drop) {
            actions.add("drop");
        }
        return actions;
    }

    public Map<String, String> getSet() {
        return set;
    }

    public void setSet(Map<String, String> set) {
        this.set = set == null ? null : new LinkedHashMap<>(set);
    }

    public boolean isDrop() {
        return drop;
    }

    public void setDrop(boolean drop) {
        this.drop = drop;
    }

    public RuleAlert getAlert() {
        return alert;
    }

    public void setAlert(RuleAlert alert) {
        this.alert = alert;
    }

    public String getBean() {
        return bean;
    }

    public void setBean(String bean) {
        this.bean = bean;
    }
}
