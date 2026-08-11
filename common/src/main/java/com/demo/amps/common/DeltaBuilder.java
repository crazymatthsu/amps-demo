package com.demo.amps.common;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Computes the smallest protobuf message that, when merged into {@code before},
 * yields {@code after} -- i.e. the payload for an AMPS {@code delta_publish}.
 *
 * <p>AMPS merges a delta into the stored SOW record field by field on the server,
 * so only the changed fields ever cross the network and only the changed fields
 * are written to the transaction log. For the instrument snapshots in this demo
 * that is the difference between re-sending a ~1.3 KB record on every tick and
 * sending ~134 bytes, measured by the journal-lab demo.
 *
 * <p>Three semantics are worth knowing before relying on this:
 *
 * <ol>
 *   <li><b>Nested messages merge recursively.</b> Changing {@code quote.bid} sends
 *       {@code {"symbol":"X","quote":{"bid":1.23}}} and leaves {@code quote.ask}
 *       untouched in the SOW.</li>
 *   <li><b>Arrays are replaced, not merged.</b> Changing one entry of
 *       {@code book} re-sends the whole ladder. That is an AMPS/JSON-merge
 *       property, not a limitation of this class -- there is no positional merge
 *       for JSON arrays. Model hot repeated data as nested messages keyed by name
 *       if you need per-element deltas.</li>
 *   <li><b>Clearing a field cannot be expressed.</b> A merge can set a field, not
 *       remove one. When {@link Delta#fullPublishRequired()} is true the caller
 *       must fall back to a full publish; the demos do exactly that.</li>
 * </ol>
 */
public final class DeltaBuilder {

    private DeltaBuilder() {
    }

    /**
     * The result of a diff.
     *
     * @param message              a message populated with only the changed fields
     *                             plus the always-included key fields
     * @param changedFields        descriptors that must be printed even if they
     *                             hold default values
     * @param fullPublishRequired  true when the change cannot be expressed as a
     *                             merge (a field was cleared)
     */
    public record Delta(Message message, Set<FieldDescriptor> changedFields, boolean fullPublishRequired) {

        /** True when nothing but the key fields would be sent. */
        public boolean isEmpty() {
            return changedFields.isEmpty();
        }

        /** The JSON body to hand to {@code Client.deltaPublish}. */
        public String json() {
            return JsonCodec.toDeltaJson(message, changedFields);
        }
    }

    /**
     * Diffs two messages of the same type.
     *
     * @param before        the record as AMPS currently holds it
     * @param after         the desired state
     * @param keyFieldNames top-level field names that must always be present so
     *                      AMPS can resolve the SOW key (e.g. {@code orderId})
     */
    public static Delta between(Message before, Message after, Collection<String> keyFieldNames) {
        if (!before.getDescriptorForType().equals(after.getDescriptorForType())) {
            throw new IllegalArgumentException("cannot diff "
                    + before.getDescriptorForType().getFullName() + " against "
                    + after.getDescriptorForType().getFullName());
        }

        Message.Builder builder = after.newBuilderForType();
        Set<FieldDescriptor> changed = new LinkedHashSet<>();
        boolean cleared = diffInto(before, after, builder, changed);

        // The key must travel with every delta: it is how AMPS finds the record to
        // merge into. It is included whether or not it changed (it never should).
        for (String keyFieldName : keyFieldNames) {
            FieldDescriptor key = after.getDescriptorForType().findFieldByName(keyFieldName);
            if (key == null) {
                throw new IllegalArgumentException("no field '" + keyFieldName + "' on "
                        + after.getDescriptorForType().getFullName());
            }
            builder.setField(key, after.getField(key));
            changed.add(key);
        }

        return new Delta(builder.build(), Set.copyOf(changed), cleared);
    }

    /**
     * Applies a delta to a base record using the same rules AMPS applies on the
     * server: fields carried by the delta overwrite, everything else is left alone,
     * nested messages merge recursively, arrays replace.
     *
     * <p>The demos use this to predict what the SOW should hold after a delta
     * publish and then compare that prediction against what the server actually
     * returns, which is a far more convincing check than eyeballing the output.
     *
     * <p>Note that neither {@code JsonFormat.Parser.merge} nor protobuf's own
     * {@code Builder.mergeFrom} can stand in here. The JSON parser rejects a
     * builder whose scalar fields are already set, and {@code mergeFrom} skips
     * source fields holding default values, so a delta resetting a counter to zero
     * would be silently dropped. See {@code DeltaBuilderTest}.
     */
    public static Message apply(Message base, Delta delta) {
        Message.Builder builder = base.toBuilder();
        applyInto(builder, delta.message(), delta.changedFields());
        return builder.build();
    }

    private static void applyInto(Message.Builder target, Message source, Set<FieldDescriptor> included) {
        for (FieldDescriptor field : source.getDescriptorForType().getFields()) {
            if (!field.isRepeated() && field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (!source.hasField(field)) {
                    continue;
                }
                Message child = (Message) source.getField(field);
                Message.Builder childBuilder = target.hasField(field)
                        ? ((Message) target.getField(field)).toBuilder()
                        : child.newBuilderForType();
                applyInto(childBuilder, child, included);
                target.setField(field, childBuilder.build());
                continue;
            }

            // Scalars, enums and arrays: carried by the delta only if the diff
            // marked them as changed. Anything else keeps the stored value.
            if (included.contains(field)) {
                target.setField(field, source.getField(field));
            }
        }
    }

    /** Returns true if any field was cleared and the delta is therefore unusable. */
    private static boolean diffInto(Message before,
                                    Message after,
                                    Message.Builder target,
                                    Set<FieldDescriptor> changed) {
        boolean cleared = false;

        for (FieldDescriptor field : after.getDescriptorForType().getFields()) {
            if (field.isRepeated()) {
                // Includes map fields. Compared and replaced as a whole.
                List<?> oldValue = (List<?>) before.getField(field);
                List<?> newValue = (List<?>) after.getField(field);
                if (!oldValue.equals(newValue)) {
                    target.setField(field, newValue);
                    changed.add(field);
                }
                continue;
            }

            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                boolean hadIt = before.hasField(field);
                boolean hasIt = after.hasField(field);

                if (hadIt && hasIt) {
                    Message oldChild = (Message) before.getField(field);
                    Message newChild = (Message) after.getField(field);
                    if (oldChild.equals(newChild)) {
                        continue;
                    }
                    Message.Builder childDelta = newChild.newBuilderForType();
                    cleared |= diffInto(oldChild, newChild, childDelta, changed);
                    target.setField(field, childDelta.build());
                    // The parent field itself is a message; it is present because we
                    // just set it, so it does not need forcing into the output.
                } else if (!hadIt && hasIt) {
                    // Brand new submessage: send it whole, and force every leaf so
                    // defaults inside it are not silently dropped.
                    Message newChild = (Message) after.getField(field);
                    target.setField(field, newChild);
                    collectLeaves(newChild, changed);
                } else if (hadIt) {
                    // Present before, absent now. A merge cannot remove a field.
                    cleared = true;
                }
                continue;
            }

            // Scalars and enums.
            if (field.hasPresence()) {
                boolean hadIt = before.hasField(field);
                boolean hasIt = after.hasField(field);
                if (hadIt && !hasIt) {
                    cleared = true;
                    continue;
                }
                if (hasIt && (!hadIt || !Objects.equals(before.getField(field), after.getField(field)))) {
                    target.setField(field, after.getField(field));
                    changed.add(field);
                }
                continue;
            }

            // Implicit-presence proto3 scalar: compare values directly. hasField()
            // is not allowed on these, and "unset" is indistinguishable from
            // "set to the default value" by design.
            Object oldValue = before.getField(field);
            Object newValue = after.getField(field);
            if (!Objects.equals(oldValue, newValue)) {
                target.setField(field, newValue);
                changed.add(field);
            }
        }

        return cleared;
    }

    /** Records every populated leaf descriptor so JSON printing keeps defaults. */
    private static void collectLeaves(Message message, Set<FieldDescriptor> into) {
        for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
            if (field.isRepeated()) {
                if (!((List<?>) message.getField(field)).isEmpty()) {
                    into.add(field);
                }
            } else if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (message.hasField(field)) {
                    collectLeaves((Message) message.getField(field), into);
                }
            } else {
                into.add(field);
            }
        }
    }
}
