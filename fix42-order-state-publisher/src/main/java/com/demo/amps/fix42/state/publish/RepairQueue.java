package com.demo.amps.fix42.state.publish;

import com.crankuptheamps.client.FailedWriteHandler;
import com.crankuptheamps.client.Message;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the server refused, handed from the AMPS client's receive thread to
 * the publishing thread (docs/04 §6, S2).
 *
 * <p>The {@link FailedWriteHandler} runs on the client's thread and the cache
 * entries are the publishing thread's, so the handler never touches an entry:
 * it copies what it was told into the queue and returns. The publisher drains
 * it before each message, on an idle timer and at shutdown, and decides the
 * repair there -- a delta of every cached field for a rejected delta, the
 * kept payload for a rejected creation, the catch-all topic for a rejected
 * audit publish.
 *
 * <p>A {@code Duplicate} is the server's expected answer to a store replay
 * after a reconnect (the message was already there): logged and counted, never
 * repaired.
 */
public final class RepairQueue implements FailedWriteHandler {

    private static final Logger log = LoggerFactory.getLogger(RepairQueue.class);

    /**
     * One refused publish, copied off the client's thread.
     *
     * @param command {@code Message.Command.Publish} or {@code DeltaPublish}:
     *                a refused creation is re-sent whole, a refused delta is not
     */
    public record Failure(String topic, long sequence, byte[] data, int reason, int command) {

        public boolean wasPublish() {
            return command == Message.Command.Publish;
        }
    }

    private final ConcurrentLinkedQueue<Failure> failures = new ConcurrentLinkedQueue<>();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    @Override
    public void failedWrite(Message message, int reason) {
        long sequence = message.isSequenceNull() ? 0 : message.getSequence();
        if (reason == Message.Reason.Duplicate) {
            duplicates.incrementAndGet();
            log.info("publish {} dropped by the server as a duplicate (expected after a replay)", sequence);
            return;
        }
        String topic = message.isTopicNull() ? "" : message.getTopic();
        byte[] copy = new byte[0];
        if (!message.isDataNull()) {
            com.crankuptheamps.client.fields.Field data = message.getDataRaw();
            copy = java.util.Arrays.copyOfRange(data.buffer, data.position, data.position + data.length);
        }
        refused.incrementAndGet();
        log.warn("publish {} to {} refused by the server, reason {}: queued for repair", sequence, topic,
                reasonName(reason));
        failures.add(new Failure(topic, sequence, copy, reason, message.getCommand()));
    }

    /** Enqueues a failure directly: a client without a store, or a test. */
    public void add(Failure failure) {
        refused.incrementAndGet();
        failures.add(failure);
    }

    /** The next failure to repair, or null. */
    public Failure poll() {
        return failures.poll();
    }

    public boolean isEmpty() {
        return failures.isEmpty();
    }

    /** {@code publishDuplicatesOnReplay}. */
    public long duplicates() {
        return duplicates.get();
    }

    /** Everything the server refused for a reason other than a duplicate. */
    public long refused() {
        return refused.get();
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
