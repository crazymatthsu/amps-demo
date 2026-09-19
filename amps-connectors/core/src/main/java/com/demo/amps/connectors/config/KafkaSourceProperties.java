package com.demo.amps.connectors.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Kafka side of one connector: which cluster and topic, and how the topic is read.
 *
 * <p>Lives under {@code source.kafka}; its presence is what selects the Kafka source
 * ({@link SourceProperties}).
 *
 * <p>Kafka is the transport that makes the at-least-once contract visible. The consumer runs
 * with {@code enable.auto.commit=false} and commits nothing until the connector says a record
 * reached AMPS: offsets are committed from the poll thread, for acknowledged records only, so
 * a crash between a publish and its flush re-reads the records rather than losing them. That
 * is also why {@link #getGroupId()} is mandatory -- the group is where the position lives.
 *
 * <p>A null-valued record is a tombstone and becomes a {@code DELETE}; the message key becomes
 * the record's key, and {@code topic}/{@code partition}/{@code offset} ride along as
 * attributes.
 */
public class KafkaSourceProperties {

    /** Where to resume from when the consumer group has no committed offset. */
    public enum From {
        /** Replay the topic from its oldest retained record. */
        EARLIEST,
        /** Start at the end, ignoring everything already published. */
        LATEST
    }

    /** {@code host:port} list of bootstrap brokers. */
    @NotBlank
    private String bootstrapServers;

    /** The Kafka topic to consume. */
    @NotBlank
    private String topic;

    /**
     * Consumer group. Mandatory, because it is what holds this connector's position between
     * restarts -- an unnamed group would re-publish the topic from scratch every boot.
     */
    @NotBlank
    private String groupId;

    /** Where a consumer with no committed offset starts ({@code auto.offset.reset}). */
    @NotNull
    private From from = From.EARLIEST;

    /** How long a poll blocks waiting for records before returning empty. */
    @NotNull
    private Duration pollTimeout = Duration.ofMillis(500);

    /** Upper bound on the records one poll returns, and so on one pipeline pass. */
    @Min(1)
    private int maxPollRecords = 500;

    /** How long to wait before retrying after a failed or dropped connection. */
    @NotNull
    private Duration reconnectDelay = Duration.ofSeconds(5);

    /**
     * Raw consumer properties, applied last and passed through untouched.
     *
     * <p>The escape hatch for everything this class does not name -- security, SSL, fetch
     * sizing -- so a cluster that needs an unusual setting does not need a new field here.
     * Credentials belong in environment placeholders, never in the config tree.
     */
    @NotNull
    private Map<String, String> properties = new LinkedHashMap<>();

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public From getFrom() {
        return from;
    }

    public void setFrom(From from) {
        this.from = from;
    }

    public Duration getPollTimeout() {
        return pollTimeout;
    }

    public void setPollTimeout(Duration pollTimeout) {
        this.pollTimeout = pollTimeout;
    }

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }

    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(Duration reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties == null ? new LinkedHashMap<>() : properties;
    }
}
