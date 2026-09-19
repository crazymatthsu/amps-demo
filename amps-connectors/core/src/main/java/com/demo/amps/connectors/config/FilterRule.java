package com.demo.amps.connectors.config;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One condition on one field.
 *
 * <p>A rule names a {@link #getField() field} and exactly <em>one</em> operator; the validator
 * rejects a rule that names none or several. That is what keeps the YAML readable --
 * {@code { field: "35", in: [D, G, F] }} is a sentence -- and what makes a rule's meaning
 * independent of the order its keys happen to appear in.
 *
 * <p>The operators:
 *
 * <ul>
 *   <li>{@code equals} / {@code not-equals} -- string comparison of the field's value</li>
 *   <li>{@code in} -- string membership</li>
 *   <li>{@code matches} -- a regular expression over the whole value</li>
 *   <li>{@code gt} / {@code gte} / {@code lt} / {@code lte} -- numeric; the operand is written
 *       as a String because YAML would otherwise decide the type for it, and a field whose
 *       value does not parse as a number makes the rule false rather than an error</li>
 *   <li>{@code present} -- whether the field is there at all, true or false</li>
 * </ul>
 *
 * <p>The field name is the <em>source's</em> name for it (a FIX tag number, a JSON key, a
 * dotted path into a nested object): filtering runs before the transforms rename anything.
 */
public class FilterRule {

    /** The field this rule reads; a FIX tag, a JSON key, or a dotted path. */
    @NotBlank
    private String field;

    /** Passes when the value equals this string. */
    private String equals;

    /** Passes when the value differs from this string. */
    private String notEquals;

    /** Passes when the value is one of these strings. */
    private List<String> in;

    /** Passes when the whole value matches this regular expression. */
    private String matches;

    /** Passes when the value parses as a number and is greater than this one. */
    private String gt;

    /** Passes when the value parses as a number and is greater than or equal to this one. */
    private String gte;

    /** Passes when the value parses as a number and is less than this one. */
    private String lt;

    /** Passes when the value parses as a number and is less than or equal to this one. */
    private String lte;

    /** Passes when the field's presence matches this: {@code true} present, {@code false} absent. */
    private Boolean present;

    /**
     * The operators this rule names.
     *
     * <p>The validator's whole job on a rule: not one is a rule that says nothing, more than
     * one is a rule whose meaning depends on evaluation order. Both are configuration mistakes
     * that would otherwise surface as a topic quietly missing records.
     *
     * @return the configured operator names, e.g. {@code ["in"]}
     */
    public Set<String> configuredOperators() {
        Set<String> operators = new LinkedHashSet<>();
        if (equals != null) {
            operators.add("equals");
        }
        if (notEquals != null) {
            operators.add("not-equals");
        }
        if (in != null) {
            operators.add("in");
        }
        if (matches != null) {
            operators.add("matches");
        }
        if (gt != null) {
            operators.add("gt");
        }
        if (gte != null) {
            operators.add("gte");
        }
        if (lt != null) {
            operators.add("lt");
        }
        if (lte != null) {
            operators.add("lte");
        }
        if (present != null) {
            operators.add("present");
        }
        return operators;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getEquals() {
        return equals;
    }

    public void setEquals(String equals) {
        this.equals = equals;
    }

    public String getNotEquals() {
        return notEquals;
    }

    public void setNotEquals(String notEquals) {
        this.notEquals = notEquals;
    }

    public List<String> getIn() {
        return in;
    }

    public void setIn(List<String> in) {
        this.in = in == null ? null : new ArrayList<>(in);
    }

    public String getMatches() {
        return matches;
    }

    public void setMatches(String matches) {
        this.matches = matches;
    }

    public String getGt() {
        return gt;
    }

    public void setGt(String gt) {
        this.gt = gt;
    }

    public String getGte() {
        return gte;
    }

    public void setGte(String gte) {
        this.gte = gte;
    }

    public String getLt() {
        return lt;
    }

    public void setLt(String lt) {
        this.lt = lt;
    }

    public String getLte() {
        return lte;
    }

    public void setLte(String lte) {
        this.lte = lte;
    }

    public Boolean getPresent() {
        return present;
    }

    public void setPresent(Boolean present) {
        this.present = present;
    }
}
