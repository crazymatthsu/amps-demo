package com.demo.amps.fix42.it;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads whole json-typed view topics back as parsed JSON objects, for
 * assertions.
 *
 * <p>The counterpart of {@link SowReader} for the exposure views. Those are
 * json-typed, so they cannot be read on the publisher's {@code /amps/fix}
 * connection: a connection speaks one message type, and a query for a topic
 * of another type is refused rather than translated. This reader therefore
 * takes its own client, connected to {@code /amps/json}.
 */
final class ViewReader {

    private final Client client;
    private final long timeoutMs;

    ViewReader(Client client, long timeoutMs) {
        this.client = client;
        this.timeoutMs = timeoutMs;
    }

    /** Every record currently held by {@code view}. */
    List<JsonObject> records(String view) throws Exception {
        return records(view, null);
    }

    /**
     * The records of {@code view} matching {@code filter}, or all of them when
     * it is null.
     *
     * <p>This is how a join view is selected from. {@code <Filter>} is not
     * supported on a multi-topic view, so a view that joins two others carries
     * every row, and a reader that wants only some of them (the breaks of the
     * reconciliation view) passes the condition on the query instead.
     */
    List<JsonObject> records(String view, String filter) throws Exception {
        List<JsonObject> records = new ArrayList<>();
        Command command = new Command("sow").setTopic(view).setTimeout(timeoutMs);
        if (filter != null) {
            command.setFilter(filter);
        }
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    records.add(JsonParser.parseString(message.getData()).getAsJsonObject());
                }
            }
        }
        return records;
    }

    /**
     * A whole-number field of a view record.
     *
     * <p>Through {@link BigDecimal} rather than {@code getAsLong()}: a SUM over
     * a fix-typed topic's text values may come back as {@code 12000} or as
     * {@code 12000.0}, and an assertion on a quantity should not care which.
     * A genuine fraction still fails loudly.
     */
    static long quantity(JsonObject record, String field) {
        JsonElement element = record.get(field);
        if (element == null || element.isJsonNull()) {
            throw new AssertionError("no numeric field " + field + " in " + record);
        }
        return element.getAsBigDecimal().longValueExact();
    }

    /**
     * A whole-number field of a view record, or {@code null} when the view
     * rendered it as JSON null -- which is what a join view's columns from the
     * second topic hold for a row the first topic has and the second does not.
     */
    static Long quantityOrNull(JsonObject record, String field) {
        JsonElement element = record.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsBigDecimal().longValueExact();
    }

    /**
     * A price field of a view record, or {@code null} when the view rendered
     * it as JSON null -- which is what {@code SUM(a) / SUM(b)} produces for a
     * group whose {@code b} sums to zero on a json-typed view.
     */
    static Double price(JsonObject record, String field) {
        JsonElement element = record.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsDouble();
    }

    /** A text field of a view record, tolerant of a numeric-looking value. */
    static String text(JsonObject record, String field) {
        JsonElement element = record.get(field);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        return element.getAsString();
    }
}
