package com.demo.amps.fix42.state.publish;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Store;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link PublishSink} over an AMPS client -- in production the
 * {@code HAClient} with a publish store that {@code AmpsClientConfiguration}
 * builds.
 *
 * <p>With a store, {@code publish} returns once the message is in the store,
 * not when the server acknowledges it; {@link #flush} is {@code publishFlush},
 * which waits for the store to drain (the client's {@code flush} does nothing
 * useful here). Topic names are encoded once and kept.
 */
public final class AmpsPublishSink implements PublishSink {

    private final Client client;
    private final Map<String, byte[]> topics = new HashMap<>();

    public AmpsPublishSink(Client client) {
        this.client = client;
    }

    public Client client() {
        return client;
    }

    @Override
    public long publish(String topic, byte[] payload, int offset, int length) throws Exception {
        byte[] name = topicBytes(topic);
        return client.publish(name, 0, name.length, payload, offset, length);
    }

    @Override
    public long deltaPublish(String topic, byte[] payload, int offset, int length) throws Exception {
        byte[] name = topicBytes(topic);
        return client.deltaPublish(name, 0, name.length, payload, offset, length);
    }

    @Override
    public void flush(long timeoutMs) throws Exception {
        client.publishFlush(timeoutMs);
    }

    @Override
    public long unpersisted() {
        Store store = client.getPublishStore();
        return store == null ? 0 : store.unpersistedCount();
    }

    @Override
    public long lastPersisted() {
        Store store = client.getPublishStore();
        try {
            return store == null ? Long.MAX_VALUE : store.getLastPersisted();
        } catch (Exception e) {
            return 0;
        }
    }

    private byte[] topicBytes(String topic) {
        return topics.computeIfAbsent(topic, t -> t.getBytes(StandardCharsets.ISO_8859_1));
    }
}
