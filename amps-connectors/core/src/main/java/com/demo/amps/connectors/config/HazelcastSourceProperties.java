package com.demo.amps.connectors.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The Hazelcast side of one connector: which cluster, and which topic on it.
 *
 * <p>Lives under {@code source.hazelcast}; its presence is what selects the Hazelcast source
 * ({@link SourceProperties}).
 *
 * <p>Always a Hazelcast <em>client</em>, never an embedded member: a connector that joined the
 * cluster as a member would take a share of its partitions, so restarting the connector would
 * migrate data and a connector bug would be a cluster bug. The message object's
 * {@code toString()} is the payload, and {@code publishTime}/{@code member} ride along as
 * attributes.
 *
 * <p>{@link #isReliable()} picks which Hazelcast structure is read. A plain topic is
 * fire-and-forget: a subscriber only ever sees what is published after it subscribed.
 * A reliable topic is backed by a ringbuffer, so {@link ReliableFrom#OLDEST} replays whatever
 * the buffer still holds -- which is what makes a restart pick up the messages published while
 * the connector was down.
 */
public class HazelcastSourceProperties {

    /** Where a reliable-topic listener starts reading. */
    public enum ReliableFrom {
        /** Only messages published after subscribing (ringbuffer sequence {@code -1}). */
        NEWEST,
        /** Everything the ringbuffer still holds (sequence {@code 0}), then onwards. */
        OLDEST
    }

    /** Cluster name the client logs on with; must match the members'. */
    @NotBlank
    private String clusterName = "dev";

    /** Member addresses the client tries, as {@code host:port}. */
    @NotEmpty
    private List<String> members = new ArrayList<>(List.of("localhost:5701"));

    /** The topic to subscribe to. */
    @NotBlank
    private String topic;

    /** Whether the topic is a reliable (ringbuffer-backed) topic rather than a plain one. */
    private boolean reliable = false;

    /** {@link #isReliable()} only: where the listener starts. */
    @NotNull
    private ReliableFrom reliableFrom = ReliableFrom.NEWEST;

    /** How long the client waits for a member before giving up an attempt. */
    @NotNull
    private Duration connectionTimeout = Duration.ofSeconds(5);

    /** How long to wait before reconnecting after the client loses the cluster. */
    @NotNull
    private Duration reconnectDelay = Duration.ofSeconds(5);

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> members) {
        this.members = members == null ? new ArrayList<>() : members;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public boolean isReliable() {
        return reliable;
    }

    public void setReliable(boolean reliable) {
        this.reliable = reliable;
    }

    public ReliableFrom getReliableFrom() {
        return reliableFrom;
    }

    public void setReliableFrom(ReliableFrom reliableFrom) {
        this.reliableFrom = reliableFrom;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(Duration reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }
}
