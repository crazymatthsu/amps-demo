package com.demo.amps.fix42.state.amps;

import com.crankuptheamps.client.FailedWriteHandler;
import com.crankuptheamps.client.Message;
import com.demo.amps.fix42.state.publish.RepairQueue;

/**
 * The AMPS client's {@link FailedWriteHandler}: what the server refused,
 * translated into the core {@link RepairQueue} (docs/04 §6, S2).
 *
 * <p>Runs on the client's receive thread, so it never touches a cache entry:
 * it copies the refused message's topic, sequence and payload into a
 * {@link RepairQueue.Failure} and returns, and the publisher repairs it on its
 * own thread. {@code Message.Reason.Duplicate} -- the server's answer to a
 * store replay after a reconnect -- is counted as a duplicate instead, never
 * repaired.
 */
public final class AmpsFailedWriteHandler implements FailedWriteHandler {

    private final RepairQueue repairs;

    public AmpsFailedWriteHandler(RepairQueue repairs) {
        this.repairs = repairs;
    }

    @Override
    public void failedWrite(Message message, int reason) {
        long sequence = message.isSequenceNull() ? 0 : message.getSequence();
        if (reason == Message.Reason.Duplicate) {
            repairs.onDuplicate(sequence);
            return;
        }
        String topic = message.isTopicNull() ? "" : message.getTopic();
        byte[] copy = new byte[0];
        if (!message.isDataNull()) {
            com.crankuptheamps.client.fields.Field data = message.getDataRaw();
            copy = java.util.Arrays.copyOfRange(data.buffer, data.position, data.position + data.length);
        }
        repairs.onRefused(new RepairQueue.Failure(topic, sequence, copy, reasonName(reason),
                message.getCommand() != Message.Command.Publish));
    }

    static String reasonName(int reason) {
        for (java.lang.reflect.Field field : Message.Reason.class.getFields()) {
            try {
                if (field.getType() == int.class && field.getInt(null) == reason) {
                    return field.getName();
                }
            } catch (IllegalAccessException ignored) {
                // not ours to read; fall through to the number
            }
        }
        return Integer.toString(reason);
    }
}
