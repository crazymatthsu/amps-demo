package com.demo.amps.ha;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.ha.api.RecoveryPointTopic;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The records a {@link com.crankuptheamps.client.SOWRecoveryPointAdapter}
 * keeps in its SOW topic, and how to read them back the way an operator
 * would: a throwaway client and a {@code sow} query.
 *
 * <p>The adapter writes one JSON document per (client name, subscription id)
 * -- the two fields the topic is keyed on -- carrying the bookmark the
 * subscription resumes from:
 *
 * <pre>{"clientName":"ha-demo-consumer","subId":"ha-demo-consumer","bookmark":"17434824616529380601|1200|"}</pre>
 *
 * <p>The bookmark is a comma-separated list when the store holds a position
 * per publisher it has heard from. The field names are the adapter's
 * defaults; the instance configs key the topic on the first two.
 */
public final class RecoveryPoints {

    public static final String CLIENT_NAME_FIELD = RecoveryPointTopic.DEFAULT_CLIENT_NAME_FIELD;
    public static final String SUB_ID_FIELD = RecoveryPointTopic.DEFAULT_SUB_ID_FIELD;
    public static final String BOOKMARK_FIELD = RecoveryPointTopic.DEFAULT_BOOKMARK_FIELD;

    private static final int LOGON_TIMEOUT_MS = 10_000;

    private RecoveryPoints() {
    }

    /** One record: where {@code clientName}'s subscription {@code subId} resumes. */
    public record Entry(String clientName, String subId, String bookmark) {

        /** Parses an adapter record; {@code null} when {@code json} is not one. */
        public static Entry parse(String json) {
            JsonElement element;
            try {
                element = JsonParser.parseString(json);
            } catch (JsonParseException e) {
                return null;
            }
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            String clientName = string(object, CLIENT_NAME_FIELD);
            String subId = string(object, SUB_ID_FIELD);
            String bookmark = string(object, BOOKMARK_FIELD);
            if (clientName == null || subId == null || bookmark == null) {
                return null;
            }
            return new Entry(clientName, subId, bookmark);
        }

        /** The bookmarks in {@link #bookmark()}: one, or several when it is a list. */
        public List<String> bookmarks() {
            return Arrays.stream(bookmark.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        private static String string(JsonObject object, String name) {
            JsonElement value = object.get(name);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    ? value.getAsString() : null;
        }
    }

    /**
     * Every record on {@code topic} at the instance at {@code uri} -- or only
     * those of {@code clientName} when it is not {@code null} -- read with a
     * plain client that connects, queries and disconnects.
     */
    public static List<Entry> read(String uri, String topic, String clientName) throws AMPSException {
        Client client = new Client("ha-recovery-points-" + System.nanoTime());
        try {
            client.connect(uri);
            client.logon(LOGON_TIMEOUT_MS);
            List<Entry> entries = new ArrayList<>();
            try (MessageStream stream = clientName == null
                    ? client.sow(topic)
                    : client.sow(topic, "/" + CLIENT_NAME_FIELD + " = '" + clientName + "'")) {
                for (Message message : stream) {
                    if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                        Entry entry = Entry.parse(message.getData());
                        if (entry != null) {
                            entries.add(entry);
                        }
                    }
                }
            }
            return entries;
        } finally {
            client.close();
        }
    }
}
