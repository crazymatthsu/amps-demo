package com.demo.amps.cache;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The AMPS plumbing shared by the two store implementations: publish with a
 * flush barrier, SOW query, and the two delete shapes (exact record by data,
 * bulk by filter).
 *
 * <p>Every write here ends with a server round trip -- {@code publishFlush}
 * after publishes, the acknowledgement for deletes -- so when a store method
 * returns, the SOW reflects the change. That is what lets a DIFFERENT process
 * (the failover case this module demonstrates) hydrate immediately and see
 * everything a completed {@code put} wrote. AMPS publishes are otherwise
 * asynchronous, and a cache whose {@code put} raced its own recovery would be
 * a subtle liar. The cost is one round trip per mutating call; batch writes
 * ({@code storeAll}) pay it once, and a throughput-sensitive user would move
 * to a publish store (see {@code AmpsConnections.connectWithPublishStore})
 * rather than weaken the barrier.
 *
 * <p>Public because hazelcast-persistent-store builds its tier store on this
 * same plumbing rather than re-implementing it; it is still infrastructure,
 * not a user-facing API.
 */
public final class SowOps {

    private final Client client;
    private final String topic;
    private final long timeoutMillis;

    public SowOps(Client client, String topic, long timeoutMillis) {
        this.client = client;
        this.topic = topic;
        this.timeoutMillis = timeoutMillis;
    }

    public String topic() {
        return topic;
    }

    /** Publishes one record and blocks until the server has processed it. */
    public void publish(JsonObject record) {
        try {
            client.publish(topic, record.toString());
            client.publishFlush(timeoutMillis);
        } catch (AMPSException e) {
            throw new CacheStoreException("publish to '" + topic + "' failed", e);
        }
    }

    /**
     * Publishes one record WITHOUT the flush barrier. For write-behind callers
     * (Hazelcast's, notably) that have already decoupled the writer from the
     * caller and accept the weaker read-your-writes in exchange for not paying
     * a round trip per store.
     */
    public void publishWithoutBarrier(JsonObject record) {
        try {
            client.publish(topic, record.toString());
        } catch (AMPSException e) {
            throw new CacheStoreException("publish to '" + topic + "' failed", e);
        }
    }

    /** Publishes a batch, paying the flush barrier once at the end. */
    public void publishAll(Collection<JsonObject> records) {
        if (records.isEmpty()) {
            return;
        }
        try {
            for (JsonObject record : records) {
                client.publish(topic, record.toString());
            }
            client.publishFlush(timeoutMillis);
        } catch (AMPSException e) {
            throw new CacheStoreException("batch publish to '" + topic + "' failed", e);
        }
    }

    /**
     * Runs a SOW query and hands each record to {@code consumer} as parsed
     * JSON. A null {@code filter} means every record in the topic.
     */
    public void query(String filter, Consumer<JsonObject> consumer) {
        Command command = new Command("sow").setTopic(topic).setTimeout(timeoutMillis);
        if (filter != null) {
            command.setFilter(filter);
        }
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    consumer.accept(JsonParser.parseString(message.getData()).getAsJsonObject());
                }
            }
        } catch (AMPSException e) {
            throw new CacheStoreException("sow query on '" + topic + "' failed", e);
        }
    }

    /**
     * Deletes the single record whose SOW key matches the key fields of
     * {@code keyFields} -- the "delete by data" form, which lets the server
     * compute the key exactly as it did on publish. No filter expression is
     * involved, so this path has no quoting constraints at all.
     */
    public void deleteByData(JsonObject keyFields) {
        CountDownLatch acknowledged = new CountDownLatch(1);
        try {
            client.sowDeleteByData(message -> acknowledged.countDown(),
                    topic, keyFields.toString(), timeoutMillis);
            if (!acknowledged.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new CacheStoreException("sow_delete on '" + topic
                        + "' was not acknowledged within " + timeoutMillis + "ms");
            }
        } catch (AMPSException e) {
            throw new CacheStoreException("sow_delete on '" + topic + "' failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CacheStoreException("interrupted waiting for sow_delete on '" + topic + "'", e);
        }
    }

    /** Deletes every record matching {@code filter}; returns the server's count. */
    public long deleteByFilter(String filter) {
        try {
            Message ack = client.sowDelete(topic, filter, timeoutMillis);
            return ack.getRecordsDeleted();
        } catch (AMPSException e) {
            throw new CacheStoreException("sow_delete on '" + topic + "' failed", e);
        }
    }
}
