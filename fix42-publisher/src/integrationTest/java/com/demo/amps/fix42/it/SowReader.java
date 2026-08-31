package com.demo.amps.fix42.it;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.fix42.fix.FixMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads whole SOW topics back as parsed FIX messages, for assertions. */
final class SowReader {

    private final Client client;
    private final long timeoutMs;

    SowReader(Client client, long timeoutMs) {
        this.client = client;
        this.timeoutMs = timeoutMs;
    }

    /** Every record currently stored in {@code topic}. */
    List<FixMessage> records(String topic) throws Exception {
        List<FixMessage> records = new ArrayList<>();
        Command command = new Command("sow").setTopic(topic).setTimeout(timeoutMs);
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    records.add(FixMessage.parse(message.getData()));
                }
            }
        }
        return records;
    }

    /**
     * The SERVER-GENERATED key of every record matching {@code filter}.
     *
     * <p>Distinct from any field in the payload: on a topic whose key comes
     * from the chaining key generator this is the chain's identity, which no
     * single tag carries.
     */
    List<String> sowKeys(String topic, String filter) throws Exception {
        List<String> keys = new ArrayList<>();
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
                    keys.add(message.getSowKey());
                }
            }
        }
        return keys;
    }

    /**
     * Every record in {@code topic}, indexed by the value of {@code keyTag}.
     *
     * <p>Indexing by a business field rather than the server's SOW key keeps
     * assertions readable: {@code byClOrdId.get("PARENT-AAPL-3")} says what it
     * means, where a 64-bit generated key does not.
     */
    Map<String, FixMessage> recordsBy(String topic, int keyTag) throws Exception {
        Map<String, FixMessage> indexed = new LinkedHashMap<>();
        for (FixMessage record : records(topic)) {
            indexed.put(record.value(keyTag), record);
        }
        return indexed;
    }
}
