package com.demo.amps.fix42.state.publish;

import com.demo.amps.fix42.state.fix.FixMessage;
import java.util.ArrayList;
import java.util.List;

/** A {@link PublishSink} that records what it was asked to send, and can be told to throw. */
final class RecordingSink implements PublishSink {

    record Sent(String command, String topic, FixMessage message, long sequence) {
        @Override
        public String toString() {
            return command + " " + topic + " " + message.printable();
        }
    }

    final List<Sent> sent = new ArrayList<>();
    private long sequence;
    /** Throw on the n-th publish from now (1-based); 0 never. */
    int throwOn;

    @Override
    public long publish(String topic, byte[] payload, int offset, int length) {
        return record("publish", topic, payload, offset, length);
    }

    @Override
    public long deltaPublish(String topic, byte[] payload, int offset, int length) {
        return record("delta_publish", topic, payload, offset, length);
    }

    private long record(String command, String topic, byte[] payload, int offset, int length) {
        if (throwOn > 0 && --throwOn == 0) {
            throw new IllegalStateException("the store refused the publish (test)");
        }
        sent.add(new Sent(command, topic, FixMessage.parse(payload, offset, length), ++sequence));
        return sequence;
    }

    @Override
    public void flush(long timeoutMs) {
    }

    @Override
    public long unpersisted() {
        return 0;
    }

    @Override
    public long lastPersisted() {
        return sequence;
    }

    List<String> topics() {
        return sent.stream().map(s -> s.command() + " " + s.topic()).toList();
    }

    Sent last() {
        return sent.getLast();
    }
}
