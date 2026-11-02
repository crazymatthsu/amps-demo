package com.demo.amps.fix42.state.amps;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.recovery.RecordSource;
import java.util.function.Consumer;

/**
 * The {@link RecordSource} over AMPS: the blotter's SOW, which holds each
 * record merged -- the full publish with every delta since applied over it
 * (docs/04 §2).
 *
 * <p>One {@code sow} query over the topic for a load, and a {@code top_n} of
 * one for the emptiness check {@code recovery: NONE} makes. Run after logon,
 * so the old instance's bytes cannot still be in flight behind the snapshot
 * (docs/04 §5).
 */
public final class SowRecordSource implements RecordSource {

    private final Client client;
    private final String topic;
    private final long timeoutMs;

    public SowRecordSource(Client client, String topic, long timeoutMs) {
        this.client = client;
        this.topic = topic;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String name() {
        return topic;
    }

    @Override
    public boolean isEmpty() throws Exception {
        Command command = new Command("sow").setTopic(topic).setTopN(1).setTimeout(timeoutMs);
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void forEach(Consumer<FixMessage> action) throws Exception {
        Command command = new Command("sow").setTopic(topic).setTimeout(timeoutMs);
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() != Message.Command.SOW || message.isDataNull()) {
                    continue;
                }
                action.accept(FixMessage.parse(message.getData()));
            }
        }
    }
}
