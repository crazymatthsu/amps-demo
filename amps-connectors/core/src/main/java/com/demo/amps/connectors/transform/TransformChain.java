package com.demo.amps.connectors.transform;

import com.demo.amps.connectors.config.TransformStep;
import com.demo.amps.connectors.decode.Fields;
import com.demo.amps.connectors.filter.FieldExpressions;
import com.demo.amps.connectors.source.SourceRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.expression.Expression;

/**
 * The connector's {@code transforms:} list, compiled once and folded over every record.
 *
 * <p>Steps run in the order they were written and each one gets its own copy of the field map,
 * so a step never edits what the step before it is still described by, and a
 * {@link RecordTransform} bean cannot corrupt the chain by mutating its argument. The fold
 * stops at the first step that returns {@code null}, which is the spelling for "drop this
 * record" -- the connector counts that apart from a rejection, because it is a decision rather
 * than a failure.
 *
 * <p>The built-in kinds, all compiled by {@link #compile}:
 *
 * <ul>
 *   <li>{@code keep} -- projection, in the order listed. Naming a repeated field keeps every
 *       occurrence of it ({@code 55}, {@code 55#2}…), because they are one field repeated
 *       rather than several fields</li>
 *   <li>{@code drop} -- the complement, with the same occurrence rule</li>
 *   <li>{@code rename} -- in place, so the field keeps its position in wire order, and an
 *       occurrence suffix survives the rename ({@code 55#2} becomes {@code symbol#2})</li>
 *   <li>{@code set} -- literal values, overwriting whatever was there</li>
 *   <li>{@code values} -- a code table per field; a code the table does not list passes
 *       through unchanged, because an unknown enum value is a reason to look at the feed, not
 *       a reason to lose the record</li>
 *   <li>{@code derive} -- a SpEL expression per new field, evaluated over the record as the
 *       earlier steps left it, and stored with whatever type it produced</li>
 * </ul>
 */
public final class TransformChain {

    private final List<RecordTransform> transforms;

    /**
     * @param transforms the compiled steps, in order
     */
    public TransformChain(List<RecordTransform> transforms) {
        this.transforms = List.copyOf(transforms);
    }

    /**
     * Compile a connector's steps, resolving any {@code bean:} step against the registry.
     *
     * @param steps the connector's {@code transforms:} list
     * @param registry the application's transform beans
     * @return the chain to fold over each record
     * @throws IllegalStateException naming the first unknown bean and what is registered
     */
    public static TransformChain of(List<TransformStep> steps, TransformRegistry registry) {
        return new TransformChain(registry.resolve(steps));
    }

    /** Whether there is anything to do -- which is also what decides {@code passthrough: AUTO}. */
    public boolean isEmpty() {
        return transforms.isEmpty();
    }

    /** How many steps run per record. */
    public int size() {
        return transforms.size();
    }

    /**
     * Fold the steps over one record.
     *
     * @param record the record the fields came from, for its action and key
     * @param fields the decoded fields
     * @return the fields to publish, or {@code null} when a step dropped the record
     */
    public Map<String, Object> apply(SourceRecord record, Map<String, Object> fields) {
        Map<String, Object> current = fields;
        for (RecordTransform transform : transforms) {
            current = transform.apply(record, current);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Compile one built-in step.
     *
     * @param step a step that does not name a {@code bean}
     * @return the transform that performs it
     * @throws IllegalArgumentException if the step does not name exactly one kind
     */
    public static RecordTransform compile(TransformStep step) {
        Set<String> kinds = step.configuredKinds();
        if (kinds.size() != 1) {
            throw new IllegalArgumentException("transform step names "
                    + (kinds.isEmpty() ? "no kind" : "kinds " + kinds)
                    + ", but a step names exactly one kind");
        }
        if (step.getKeep() != null) {
            List<String> keep = List.copyOf(step.getKeep());
            return (record, fields) -> keep(fields, keep);
        }
        if (step.getDrop() != null) {
            List<String> drop = List.copyOf(step.getDrop());
            return (record, fields) -> drop(fields, drop);
        }
        if (step.getRename() != null) {
            Map<String, String> rename = Map.copyOf(step.getRename());
            return (record, fields) -> rename(fields, rename);
        }
        if (step.getSet() != null) {
            Map<String, String> set = new LinkedHashMap<>(step.getSet());
            return (record, fields) -> {
                Map<String, Object> result = new LinkedHashMap<>(fields);
                set.forEach((field, value) -> Fields.put(result, field, value));
                return result;
            };
        }
        if (step.getValues() != null) {
            Map<String, Map<String, String>> tables = new LinkedHashMap<>(step.getValues());
            return (record, fields) -> values(fields, tables);
        }
        // `derive` is the only kind left: configuredKinds() already refused a step with none.
        Map<String, Expression> derive = new LinkedHashMap<>();
        Map<String, String> text = new LinkedHashMap<>(step.getDerive());
        text.forEach((field, expression) -> derive.put(field, FieldExpressions.parse(expression)));
        return (record, fields) -> {
            Map<String, Object> result = new LinkedHashMap<>(fields);
            derive.forEach((field, expression) ->
                    Fields.put(result, field, FieldExpressions.evaluate(
                            expression, text.get(field), result)));
            return result;
        };
    }

    /** Projection: the named fields, in the order they were named, occurrences included. */
    private static Map<String, Object> keep(Map<String, Object> fields, List<String> names) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) {
            for (String key : Fields.occurrenceKeys(fields, name)) {
                Fields.put(result, key, Fields.get(fields, key));
            }
        }
        return result;
    }

    /** The complement of {@link #keep}: everything except the named fields. */
    private static Map<String, Object> drop(Map<String, Object> fields, List<String> names) {
        Map<String, Object> result = new LinkedHashMap<>(fields);
        for (String name : names) {
            for (String key : Fields.occurrenceKeys(fields, name)) {
                Fields.remove(result, key);
            }
        }
        return result;
    }

    /**
     * Rename in place, so a renamed field keeps its position in wire order.
     *
     * <p>Flat names are rewritten as the map is walked, which is what preserves the order, and
     * an occurrence suffix rides along: renaming {@code 55} to {@code symbol} also turns
     * {@code 55#2} into {@code symbol#2}, so a repeating group is still a group afterwards. A
     * dotted source path cannot be handled that way -- it lives inside a nested object -- so it
     * is moved in a second pass and lands at the end of its destination.
     */
    private static Map<String, Object> rename(
            Map<String, Object> fields, Map<String, String> rename) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            String key = field.getKey();
            String base = Fields.baseName(key);
            String to = rename.get(base);
            String renamed = to == null ? key : to + key.substring(base.length());
            if (to != null && to.indexOf('.') >= 0) {
                Fields.put(result, renamed, field.getValue());
            } else {
                result.put(renamed, field.getValue());
            }
        }
        for (Map.Entry<String, String> entry : rename.entrySet()) {
            String from = entry.getKey();
            if (from.indexOf('.') < 0 || !Fields.contains(result, from)) {
                continue;
            }
            Fields.put(result, entry.getValue(), Fields.remove(result, from));
        }
        return result;
    }

    /** Code tables: rewrite a listed code, pass an unlisted one through unchanged. */
    private static Map<String, Object> values(
            Map<String, Object> fields, Map<String, Map<String, String>> tables) {
        Map<String, Object> result = new LinkedHashMap<>(fields);
        for (Map.Entry<String, Map<String, String>> table : tables.entrySet()) {
            String field = table.getKey();
            for (String key : Fields.occurrenceKeys(result, field)) {
                String code = Fields.text(Fields.get(result, key));
                String mapped = code == null ? null : table.getValue().get(code);
                if (mapped != null) {
                    Fields.put(result, key, mapped);
                }
            }
        }
        return result;
    }
}
