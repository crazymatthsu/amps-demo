package com.demo.amps.common;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Protobuf-as-schema, JSON-on-the-wire.
 *
 * <p>The {@code .proto} files are the contract: they define field names, types and
 * evolution rules, and they generate the Java classes the demos work with. What
 * actually crosses the socket is canonical protobuf JSON, because that is what
 * AMPS indexes, filters, keys and delta-merges. Binary protobuf is never used --
 * AMPS could carry it as an opaque blob, but then every server-side feature that
 * makes AMPS interesting (content filtering, SOW keys, delta merge, projections)
 * would be blind to the payload.
 *
 * <p>Two printer choices matter and both are deliberate:
 *
 * <ul>
 *   <li>{@code alwaysPrintFieldsWithNoPresence()} -- without it, protobuf omits
 *       fields holding default values, so an order with {@code quantity = 0} would
 *       serialize without {@code quantity} at all. A subsequent AMPS filter of
 *       {@code /quantity < 10} would not match the record, and a SOW consumer
 *       reading the field would see it missing rather than zero. Full publishes
 *       must always carry the complete field set.</li>
 *   <li>lowerCamelCase names (the default; {@code preservingProtoFieldNames()} is
 *       NOT enabled) -- so {@code order_id} is {@code orderId} on the wire, which
 *       is what the SOW {@code <Key>/orderId</Key>} in the server config expects.</li>
 * </ul>
 */
public final class JsonCodec {

    /** Compact, complete JSON: what a full publish sends. */
    private static final JsonFormat.Printer FULL_PRINTER = JsonFormat.printer()
            .alwaysPrintFieldsWithNoPresence()
            .omittingInsignificantWhitespace();

    /** Indented, complete JSON: for console output only. */
    private static final JsonFormat.Printer PRETTY_PRINTER = JsonFormat.printer()
            .alwaysPrintFieldsWithNoPresence();

    /**
     * Lenient parser. AMPS hands back exactly what a publisher sent, and a
     * publisher on a newer schema may include fields this consumer does not know
     * yet; failing the parse would make schema evolution a lock-step deployment.
     */
    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

    private JsonCodec() {
    }

    /** Serializes a complete message, including fields at their default values. */
    public static String toJson(MessageOrBuilder message) {
        try {
            return FULL_PRINTER.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("cannot serialize " + message.getClass().getName(), e);
        }
    }

    /** Same as {@link #toJson}, indented for humans. */
    public static String toPrettyJson(MessageOrBuilder message) {
        try {
            return PRETTY_PRINTER.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("cannot serialize " + message.getClass().getName(), e);
        }
    }

    /**
     * Serializes a partially-populated message, forcing the named fields to appear
     * even when they hold default values.
     *
     * <p>This is what makes delta publishing correct. A delta that sets
     * {@code filledQuantity} back to {@code 0} must actually contain
     * {@code "filledQuantity":0}; if the field were omitted, AMPS would merge
     * nothing and the SOW record would keep the stale value.
     *
     * @param message      a message holding only the fields to send
     * @param alwaysInclude the descriptors of those fields
     */
    public static String toDeltaJson(MessageOrBuilder message, Set<FieldDescriptor> alwaysInclude) {
        try {
            return JsonFormat.printer()
                    .includingDefaultValueFields(alwaysInclude)
                    .omittingInsignificantWhitespace()
                    .print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("cannot serialize delta for "
                    + message.getClass().getName(), e);
        }
    }

    /** Parses JSON from AMPS into the supplied builder, ignoring unknown fields. */
    public static void merge(String json, Message.Builder into) {
        try {
            PARSER.merge(json, into);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("cannot parse JSON into "
                    + into.getDescriptorForType().getFullName() + ": " + json, e);
        }
    }

    /** Convenience for the common "parse a whole record" case. */
    @SuppressWarnings("unchecked")
    public static <T extends Message> T fromJson(String json, T prototype) {
        Message.Builder builder = prototype.newBuilderForType();
        merge(json, builder);
        return (T) builder.build();
    }

    /** UTF-8 byte length -- what actually travels and what the journal stores. */
    public static int byteSize(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }
}
