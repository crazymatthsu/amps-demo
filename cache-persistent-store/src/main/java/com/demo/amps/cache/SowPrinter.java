package com.demo.amps.cache;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;

/**
 * Demo-only: dump a cache topic's raw SOW records to the console, so the
 * "AMPS really is the store" step shows wire truth rather than the cache's
 * own opinion of itself.
 */
final class SowPrinter {

    private SowPrinter() {
    }

    static void print(Client client, String topic) {
        Console.info("      sow query on '%s':", topic);
        forEachRecord(client, topic, data -> Console.info("        %s", data));
    }

    static int count(Client client, String topic) {
        int[] count = {0};
        forEachRecord(client, topic, data -> count[0]++);
        return count[0];
    }

    private interface RecordSink {
        void accept(String data);
    }

    private static void forEachRecord(Client client, String topic, RecordSink sink) {
        Command command = new Command("sow").setTopic(topic)
                .setTimeout(DemoConfig.timeoutMillis());
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    sink.accept(message.getData());
                }
            }
        } catch (AMPSException e) {
            throw new CacheStoreException("sow query on '" + topic + "' failed", e);
        }
    }
}
