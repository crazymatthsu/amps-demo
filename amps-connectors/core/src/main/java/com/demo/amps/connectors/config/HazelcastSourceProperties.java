package com.demo.amps.connectors.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The Hazelcast side of one connector: which cluster, and which structure on it.
 *
 * <p>Lives under {@code source.hazelcast}; its presence is what selects the Hazelcast source
 * ({@link SourceProperties}).
 *
 * <p>Always a Hazelcast <em>client</em>, never an embedded member: a connector that joined the
 * cluster as a member would take a share of its partitions, so restarting the connector would
 * migrate data and a connector bug would be a cluster bug.
 *
 * <h2>{@link #getTopic()} or {@link #getMap()}, never both</h2>
 *
 * <p>The validator insists on exactly one of them, because the two are different feeds
 * answering different questions:
 *
 * <table border="1">
 *   <caption>The two structures</caption>
 *   <tr><th></th><th>{@code topic}</th><th>{@code map}</th></tr>
 *   <tr><td>what it is</td><td>a stream of messages</td><td>a cache: keyed state</td></tr>
 *   <tr><td>the record's key</td><td>none -- a message is a payload and nothing more</td>
 *       <td>the entry key, so a keyed AMPS topic needs nothing from the payload</td></tr>
 *   <tr><td>removals</td><td>none: a topic cannot say that a thing stopped existing</td>
 *       <td>remove / evict / expire become a {@code DELETE}</td></tr>
 *   <tr><td>what a restart sees</td>
 *       <td>nothing, unless {@link #isReliable()} -- and then only what the ringbuffer still
 *           holds</td>
 *       <td>the whole map, when {@link #snapshotEnabled()}</td></tr>
 *   <tr><td>the settings that apply</td><td>{@code reliable}, {@code reliable-from}</td>
 *       <td>{@code snapshot}, {@code predicate}</td></tr>
 * </table>
 *
 * <p>{@link #isReliable()} picks which topic structure is read. A plain topic is
 * fire-and-forget: a subscriber only ever sees what is published after it subscribed.
 * A reliable topic is backed by a ringbuffer, so {@link ReliableFrom#OLDEST} replays whatever
 * the buffer still holds -- which is what makes a restart pick up the messages published while
 * the connector was down.
 *
 * <p>A map is read as an entry listener plus, on every (re)connect, a snapshot of what is
 * already in it. Entry events are <em>at-most-once</em>: Hazelcast does not replay an event a
 * client missed while it was away, so the snapshot is not an optimisation but the repair.
 * Turning it off ({@code snapshot: false}) buys a faster start for a feed that can live with a
 * SOW that never converges.
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

    /** The topic to subscribe to, for a message feed; mutually exclusive with {@link #map}. */
    private String topic;

    /** The {@code IMap} to subscribe to, for keyed state; mutually exclusive with {@link #topic}. */
    private String map;

    /** {@link #topic} only: whether it is a reliable (ringbuffer-backed) topic rather than a plain one. */
    private boolean reliable = false;

    /** {@link #isReliable()} only: where the listener starts. */
    @NotNull
    private ReliableFrom reliableFrom = ReliableFrom.NEWEST;

    /**
     * {@link #map} only: whether to replay the map's current contents on every (re)connect.
     *
     * <p>A {@code Boolean} rather than a {@code boolean} so that writing {@code snapshot:} on a
     * <em>topic</em> connector is something the validator can see and refuse. A setting that
     * cannot mean anything where it was written should stop the application at startup rather
     * than be read by nobody; absent, it means {@code true} -- see {@link #snapshotEnabled()}.
     */
    private Boolean snapshot;

    /**
     * {@link #map} only: a Hazelcast SQL predicate ({@code Predicates.sql}) narrowing both the
     * entry listener and the snapshot, e.g. {@code "status = 'OPEN'"}.
     *
     * <p>The cluster evaluates it against the entry itself, so a connector that wants a slice
     * of a large map does not drag the rest of it across the network first -- and the listener
     * is narrowed by the same expression, so the live feed and the snapshot agree on what the
     * connector is bridging. Absent, the whole map is read.
     */
    private String predicate;

    /** How long the client waits for a member before giving up an attempt. */
    @NotNull
    private Duration connectionTimeout = Duration.ofSeconds(5);

    /** How long to wait before reconnecting after the client loses the cluster. */
    @NotNull
    private Duration reconnectDelay = Duration.ofSeconds(5);

    /**
     * Whether the snapshot runs, reading the absent setting as {@code true}.
     *
     * @return {@code false} only when {@code snapshot: false} was written
     */
    public boolean snapshotEnabled() {
        return snapshot == null || snapshot;
    }

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

    public String getMap() {
        return map;
    }

    public void setMap(String map) {
        this.map = map;
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

    public Boolean getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(Boolean snapshot) {
        this.snapshot = snapshot;
    }

    public String getPredicate() {
        return predicate;
    }

    public void setPredicate(String predicate) {
        this.predicate = predicate;
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
