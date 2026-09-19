package com.demo.amps.connectors.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/**
 * How to reach the AMPS instance every connector in this application publishes into.
 *
 * <p>One server block for the whole application, but <em>one client per connector</em>: each
 * connector logs on with its own name and its own message type, because in AMPS the message
 * type is a property of the connection URI ({@code /amps/fix} vs {@code /amps/json}), not of
 * the topic. A FIX connector and a JSON connector in the same application therefore cannot
 * share a connection, however much they share a server.
 *
 * <p>The durability settings here are the publisher half of the at-least-once contract. With
 * a {@link PublishStore#MEMORY} or {@link PublishStore#FILE} store the client keeps every
 * publish until AMPS acknowledges it as persisted and replays anything in doubt after a
 * reconnect; a batch is acknowledged back to its source only once {@code flush()} has returned
 * within {@link #getFlushTimeout()}. {@link PublishStore#NONE} turns that off and makes the
 * connector fire-and-forget.
 */
public class AmpsServerProperties {

    /** Where the client keeps publishes it has not yet seen persisted. */
    public enum PublishStore {
        /**
         * In the client's heap. Survives a reconnect, not a restart -- which is the right
         * trade for a connector whose source can replay (Kafka, a JDBC snapshot).
         */
        MEMORY,
        /**
         * On disk under {@link AmpsServerProperties#getPublishStoreDir()}, one file per client
         * name. Survives a restart too, at the cost of a synchronous write per publish.
         */
        FILE,
        /**
         * No store: publishes are fire-and-forget and {@code flush()} only waits for the
         * socket. Nothing is replayed after a disconnect, so the connector degrades to
         * at-most-once.
         */
        NONE
    }

    /** AMPS host. */
    @NotBlank
    private String host = "localhost";

    /** AMPS client port -- the one the flow's Transport declares. */
    @Min(1)
    @Max(65535)
    private int port = 9007;

    /** Transport scheme: {@code tcp} or {@code tcps}. */
    @NotBlank
    private String transport = "tcp";

    /**
     * Client names are {@code <prefix>-<connector name>}. The name is the identity AMPS and
     * the publish store use to correlate a client across runs -- a random one would replay
     * nothing -- so it is derived from configuration rather than generated.
     */
    @NotBlank
    private String clientNamePrefix = "amps-connectors";

    /** How long {@code logon} waits before giving up and letting the reconnect loop retry. */
    @NotNull
    private Duration logonTimeout = Duration.ofSeconds(10);

    /** Backoff between HAClient reconnect attempts. */
    @NotNull
    private Duration reconnectDelay = Duration.ofSeconds(5);

    /** Where unpersisted publishes live. */
    @NotNull
    private PublishStore publishStore = PublishStore.MEMORY;

    /**
     * Directory for {@link PublishStore#FILE} stores; the file is
     * {@code <dir>/<client name>.publish}. Under {@code build/} by default so a developer run
     * leaves nothing behind in the source tree.
     */
    @NotBlank
    private String publishStoreDir = "build/client-state/amps-connectors";

    /**
     * How long a batch waits for its publishes to be acknowledged as persisted. A timeout is
     * not data loss -- the publish store still replays -- it just means the batch's records
     * are not acknowledged back to their source yet, so they may be re-read.
     */
    @NotNull
    private Duration flushTimeout = Duration.ofSeconds(10);

    /**
     * When {@code > 0}, the client coalesces publishes into batches of this many bytes before
     * writing them ({@code Client.setPublishBatching}). Measurably faster for small messages
     * at high rates; {@code 0} (the default) sends each publish as it is made, which is what a
     * low-rate feed wants.
     */
    @Min(0)
    private int publishBatchBytes = 0;

    /** How long a partial client-side publish batch waits before being written anyway. */
    @NotNull
    private Duration publishBatchDelay = Duration.ofMillis(10);

    /**
     * The connection URI for a message type.
     *
     * <p>The message type belongs to the URI, not to the publish call: a client logged on via
     * {@code /amps/fix} publishes FIX-typed topics, and a JSON connector in the same
     * application needs its own connection to {@code /amps/json}.
     *
     * @param messageType {@code json}, {@code fix} or {@code nvfix}
     * @return e.g. {@code tcp://localhost:9007/amps/json}
     */
    public String uri(String messageType) {
        return transport + "://" + host + ":" + port + "/amps/" + messageType;
    }

    /**
     * The AMPS client name for a connector.
     *
     * @param connectorName the connector's configured name
     * @return e.g. {@code amps-connectors-orders-kafka}
     */
    public String clientName(String connectorName) {
        return clientNamePrefix + "-" + connectorName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getClientNamePrefix() {
        return clientNamePrefix;
    }

    public void setClientNamePrefix(String clientNamePrefix) {
        this.clientNamePrefix = clientNamePrefix;
    }

    public Duration getLogonTimeout() {
        return logonTimeout;
    }

    public void setLogonTimeout(Duration logonTimeout) {
        this.logonTimeout = logonTimeout;
    }

    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(Duration reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }

    public PublishStore getPublishStore() {
        return publishStore;
    }

    public void setPublishStore(PublishStore publishStore) {
        this.publishStore = publishStore;
    }

    public String getPublishStoreDir() {
        return publishStoreDir;
    }

    public void setPublishStoreDir(String publishStoreDir) {
        this.publishStoreDir = publishStoreDir;
    }

    public Duration getFlushTimeout() {
        return flushTimeout;
    }

    public void setFlushTimeout(Duration flushTimeout) {
        this.flushTimeout = flushTimeout;
    }

    public int getPublishBatchBytes() {
        return publishBatchBytes;
    }

    public void setPublishBatchBytes(int publishBatchBytes) {
        this.publishBatchBytes = publishBatchBytes;
    }

    public Duration getPublishBatchDelay() {
        return publishBatchDelay;
    }

    public void setPublishBatchDelay(Duration publishBatchDelay) {
        this.publishBatchDelay = publishBatchDelay;
    }
}
