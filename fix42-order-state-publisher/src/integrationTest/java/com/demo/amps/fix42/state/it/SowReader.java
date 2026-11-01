package com.demo.amps.fix42.state.it;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads SOW topics back, and replays journalled non-SOW topics, for assertions. */
final class SowReader {

    private final Client client;
    private final long timeoutMs;

    SowReader(Client client, long timeoutMs) {
        this.client = client;
        this.timeoutMs = timeoutMs;
    }

    /** Every record of {@code topic} matching {@code filter} (null for all). */
    List<FixMessage> records(String topic, String filter) throws Exception {
        List<FixMessage> records = new ArrayList<>();
        for (String data : raw(topic, filter)) {
            records.add(FixMessage.parse(data));
        }
        return records;
    }

    List<FixMessage> records(String topic) throws Exception {
        return records(topic, null);
    }

    /** Every record of {@code topic}, indexed by the value of {@code keyTag}. */
    Map<String, FixMessage> recordsBy(String topic, int keyTag) throws Exception {
        Map<String, FixMessage> indexed = new LinkedHashMap<>();
        for (FixMessage record : records(topic)) {
            indexed.put(record.value(keyTag), record);
        }
        return indexed;
    }

    /** One blotter record by its key, or null. */
    FixMessage record(String topic, int keyTag, String key) throws Exception {
        List<FixMessage> found = records(topic, "/" + keyTag + " = '" + key.replace("'", "''") + "'");
        return found.isEmpty() ? null : found.getFirst();
    }

    /** A json view's records. */
    List<JsonObject> json(String view, String filter) throws Exception {
        List<JsonObject> records = new ArrayList<>();
        for (String data : raw(view, filter)) {
            records.add(JsonParser.parseString(data).getAsJsonObject());
        }
        return records;
    }

    /**
     * Every message ever published to a journalled, non-SOW topic, replayed
     * from the transaction log by a bookmark subscription from the epoch.
     */
    List<FixMessage> journal(String topic, long quietMs) throws Exception {
        List<FixMessage> messages = new ArrayList<>();
        Command command = new Command("subscribe").setTopic(topic).setBookmark("0").setTimeout(timeoutMs);
        try (MessageStream stream = client.execute(command).timeout((int) quietMs)) {
            for (Message message : stream) {
                if (message == null) {
                    break;
                }
                if (message.getCommand() == Message.Command.Publish && !message.isDataNull()) {
                    messages.add(FixMessage.parse(message.getData()));
                }
            }
        }
        return messages;
    }

    private List<String> raw(String topic, String filter) throws Exception {
        List<String> records = new ArrayList<>();
        Command command = new Command("sow").setTopic(topic).setTimeout(timeoutMs);
        if (filter != null) {
            command.setFilter(filter);
        }
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    records.add(message.getData());
                }
            }
        }
        return records;
    }
}
