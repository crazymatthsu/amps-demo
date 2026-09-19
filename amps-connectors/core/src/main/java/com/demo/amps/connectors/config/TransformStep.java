package com.demo.amps.connectors.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One reshaping step, applied to the decoded field map.
 *
 * <p>Steps run in the order they are written, and each sets exactly <em>one</em> kind; the
 * validator rejects a step that sets none or several. That is deliberate: a step is a line in
 * a pipeline, and ordering is the whole point -- a {@code rename} before a {@code keep} and a
 * {@code keep} before a {@code rename} are different programs, and merging kinds into one step
 * would leave which happened first up to a field-declaration order nobody can see.
 *
 * <pre>{@code
 * transforms:
 *   - values: { "54": { "1": BUY, "2": SELL } }
 *   - rename: { "55": symbol }
 *   - set: { source: kafka }
 *   - keep: [ "11", symbol, "54", "38" ]
 * }</pre>
 *
 * <p>Any transform at all disables {@code passthrough: AUTO} -- once the field map has been
 * edited, the original payload bytes are no longer what the connector means to publish.
 */
public class TransformStep {

    /** Projection: keep only these fields, in this order. Everything else is dropped. */
    private List<String> keep;

    /** Remove these fields, keeping the rest. */
    private List<String> drop;

    /** Rename fields: {@code old-name -> new-name}. */
    private Map<String, String> rename;

    /** Set fields to literal values; an existing value is overwritten. */
    private Map<String, String> set;

    /**
     * Rewrite codes to values, per field: {@code field -> (code -> value)}. A code the table
     * does not list passes through unchanged -- an unknown enum value is a reason to look at
     * the feed, not a reason to lose the record.
     */
    private Map<String, Map<String, String>> values;

    /**
     * Compute fields from SpEL expressions over {@code #f}, as {@code new-field -> expression}.
     * The result is stored as it comes out -- Number, String or Boolean -- so a JSON encoder
     * writes a computed notional as a number rather than a quoted string.
     */
    private Map<String, String> derive;

    /**
     * The name of a {@code RecordTransform} bean in the application context. The hook for the
     * enrichment that is genuinely code -- a lookup, a decode this framework does not know --
     * and the reason a custom application under {@code apps/} exists at all.
     */
    private String bean;

    /**
     * The kinds this step sets.
     *
     * <p>The validator's whole job on a step: none is a step that does nothing, several is a
     * step whose order of operations is invisible.
     *
     * @return the configured kind names, e.g. {@code ["rename"]}
     */
    public Set<String> configuredKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        if (keep != null) {
            kinds.add("keep");
        }
        if (drop != null) {
            kinds.add("drop");
        }
        if (rename != null) {
            kinds.add("rename");
        }
        if (set != null) {
            kinds.add("set");
        }
        if (values != null) {
            kinds.add("values");
        }
        if (derive != null) {
            kinds.add("derive");
        }
        if (bean != null) {
            kinds.add("bean");
        }
        return kinds;
    }

    public List<String> getKeep() {
        return keep;
    }

    public void setKeep(List<String> keep) {
        this.keep = keep == null ? null : new ArrayList<>(keep);
    }

    public List<String> getDrop() {
        return drop;
    }

    public void setDrop(List<String> drop) {
        this.drop = drop == null ? null : new ArrayList<>(drop);
    }

    public Map<String, String> getRename() {
        return rename;
    }

    public void setRename(Map<String, String> rename) {
        this.rename = rename == null ? null : new LinkedHashMap<>(rename);
    }

    public Map<String, String> getSet() {
        return set;
    }

    public void setSet(Map<String, String> set) {
        this.set = set == null ? null : new LinkedHashMap<>(set);
    }

    public Map<String, Map<String, String>> getValues() {
        return values;
    }

    public void setValues(Map<String, Map<String, String>> values) {
        this.values = values == null ? null : new LinkedHashMap<>(values);
    }

    public Map<String, String> getDerive() {
        return derive;
    }

    public void setDerive(Map<String, String> derive) {
        this.derive = derive == null ? null : new LinkedHashMap<>(derive);
    }

    public String getBean() {
        return bean;
    }

    public void setBean(String bean) {
        this.bean = bean;
    }
}
