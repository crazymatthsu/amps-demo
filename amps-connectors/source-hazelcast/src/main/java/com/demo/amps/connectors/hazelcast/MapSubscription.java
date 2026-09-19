package com.demo.amps.connectors.hazelcast;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.demo.amps.connectors.source.RecordHandler;
import com.demo.amps.connectors.source.SourceRecord;
import com.google.gson.Gson;
import com.hazelcast.cluster.Member;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import com.hazelcast.map.IMapEvent;
import com.hazelcast.map.MapEvent;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryEvictedListener;
import com.hazelcast.map.listener.EntryExpiredListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import com.hazelcast.map.listener.MapClearedListener;
import com.hazelcast.map.listener.MapEvictedListener;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Hazelcast {@code IMap} read as keyed state: the live entry events, plus the contents.
 *
 * <p>A map is the transport that already agrees with a SOW. Its entries have identity, so the
 * key travels rather than being dug out of the payload; they can stop existing, so a removal
 * is a {@code sow_delete} rather than something the connector has to infer; and the map can be
 * read, so a connector that was not running does not have to be told what it missed.
 *
 * <h2>Listener first, snapshot second</h2>
 *
 * <p>{@link #subscribe} registers the entry listener <em>before</em> it reads the map, and the
 * order is the whole design:
 *
 * <ul>
 *   <li>Snapshot first would leave a window between the last key read and the listener's
 *       registration in which a put is delivered to nobody and the SOW is wrong until that key
 *       is written again -- which for a slow-moving cache can be forever.</li>
 *   <li>Listener first leaves the opposite window, where a key is delivered twice: once as the
 *       event and once as the snapshot row. A duplicate upsert on a SOW is the same record
 *       written twice, which costs a publish and changes nothing.</li>
 * </ul>
 *
 * <p>The one thing to know about that window is that the snapshot's value can be <em>older</em>
 * than an event already delivered for the same key: the event carries the value as of the put,
 * while the snapshot reads whatever the map holds when the read reaches that partition, and
 * the two can cross. A key written twice in the second it takes to read a large map can
 * therefore land in the SOW as the earlier of the two values. Where that matters, the repair
 * is the next write of the key -- or a map whose values carry their own version and a
 * connector that filters on it.
 *
 * <h2>At-most-once, and unordered across partitions</h2>
 *
 * <p>Entry events are fire-and-forget. Hazelcast does not queue them for a client that is not
 * there, does not replay them, and does not acknowledge them: an event raised while the client
 * was disconnected -- or dropped by a full event queue on the member -- is simply gone, which
 * is why records from here carry no
 * {@link com.demo.amps.connectors.source.Acknowledgment}. Ordering holds per key (one key
 * lives on one partition and its events are delivered in order), and holds for nothing else:
 * two keys on two partitions arrive in whatever order their members' event threads produce.
 *
 * <p>The snapshot is what makes that survivable. Every (re)connect re-reads the map, so a lost
 * event costs the SOW accuracy only until the next connect rather than permanently -- and a
 * connector configured {@code snapshot: false} is one that has given that up on purpose.
 * A removal missed this way is the exception the snapshot cannot repair: reading a map that no
 * longer contains a key says nothing about a SOW record that still does.
 *
 * <h2>What reaches the pipeline</h2>
 *
 * <table border="1">
 *   <caption>Map events as records</caption>
 *   <tr><th>Hazelcast</th><th>record</th><th>{@code event} attribute</th></tr>
 *   <tr><td>added / updated</td><td>{@code SourceRecord.of(value, key)}</td>
 *       <td>{@code ADDED} / {@code UPDATED}</td></tr>
 *   <tr><td>removed / evicted / expired</td><td>{@code SourceRecord.delete("", key)}</td>
 *       <td>{@code REMOVED} / {@code EVICTED} / {@code EXPIRED}</td></tr>
 *   <tr><td>the snapshot's rows</td><td>{@code SourceRecord.of(value, key)}</td>
 *       <td>{@code SNAPSHOT}</td></tr>
 *   <tr><td>map cleared / map evicted</td><td>none -- a WARN and a counter</td><td></td></tr>
 * </table>
 *
 * <p>A cleared or evicted map is a map-wide event: Hazelcast reports how many entries went,
 * and not one of the keys. Fabricating deletes from the connector's own idea of what the map
 * held would be a guess that removes SOW records on the strength of it, so the event is
 * counted ({@link #mapWideEvents()}) and logged instead, and the operator decides whether the
 * SOW should be emptied too.
 */
final class MapSubscription implements HazelcastSubscription {

    private static final Logger log = LoggerFactory.getLogger(MapSubscription.class);

    /** Keys per {@code getAll} round trip: a whole large map in one call is a whole map in one heap. */
    private static final int SNAPSHOT_CHUNK = 500;

    /** Thread-safe and stateless; one instance rather than one per value. */
    private static final Gson GSON = new Gson();

    private final ConnectorProperties connector;
    private final HazelcastSourceProperties source;

    /**
     * The configured predicate, compiled once: the listener and the snapshot are narrowed by
     * the same object, so the live feed and the replay cannot disagree about what is in scope.
     * {@code null} reads the whole map.
     */
    private final Predicate<Object, Object> predicate;

    /** Map-wide clears and evictions seen: the count of times keys vanished unnamed. */
    private final AtomicLong mapWideEvents = new AtomicLong();

    /**
     * The subscribed map, and the read that says whether this subscription is still live: a
     * snapshot in progress checks it between chunks so {@code close()} is not queued behind
     * the rest of a large map.
     */
    private volatile IMap<Object, Object> map;

    private volatile UUID listener;

    MapSubscription(ConnectorProperties connector) {
        this.connector = connector;
        this.source = connector.getSource().getHazelcast();
        String sql = source.getPredicate();
        this.predicate = sql == null || sql.isBlank() ? null : Predicates.sql(sql);
    }

    @Override
    public void subscribe(HazelcastInstance client, RecordHandler handler) {
        IMap<Object, Object> subscribed = client.getMap(source.getMap());
        this.map = subscribed;
        EntryListener entries = new EntryListener(handler);
        // includeValue: without it an added/updated event carries the key alone, and the
        // connector would have to read the value back -- a second round trip per event, and
        // one that returns whatever the map holds by then rather than what the event was about.
        this.listener = predicate == null
                ? subscribed.addEntryListener(entries, true)
                : subscribed.addEntryListener(entries, predicate, true);
        if (source.snapshotEnabled()) {
            snapshot(client, handler);
        }
    }

    @Override
    public void refresh(HazelcastInstance client, RecordHandler handler) {
        if (!source.snapshotEnabled() || this.map == null) {
            return;
        }
        log.info("[{}] re-reading Hazelcast map '{}' after a reconnect: entry events raised "
                        + "while the client was away were not queued for it",
                connector.getName(), source.getMap());
        try {
            snapshot(client, handler);
        } catch (RuntimeException e) {
            // A failed re-read is not a reason to tear down a listener that is working again:
            // the next reconnect, or the next write of each key, repairs the same gap.
            log.warn("[{}] re-reading Hazelcast map '{}' failed: {}",
                    connector.getName(), source.getMap(), e.toString());
            log.debug("[{}] map re-read failed", connector.getName(), e);
        }
    }

    @Override
    public void unsubscribe() {
        IMap<Object, Object> subscribed = this.map;
        UUID registration = this.listener;
        // Nulled first: a snapshot running on the source thread reads this between chunks and
        // stops, rather than publishing the rest of a map the connector has finished with.
        this.map = null;
        this.listener = null;
        if (subscribed != null && registration != null) {
            try {
                subscribed.removeEntryListener(registration);
            } catch (RuntimeException e) {
                // The client may already be down, which removes the listener anyway.
                log.debug("[{}] removing the Hazelcast entry listener failed",
                        connector.getName(), e);
            }
        }
    }

    @Override
    public String describe() {
        return "map '" + source.getMap() + "'"
                + (source.getPredicate() == null ? "" : " matching [" + source.getPredicate() + "]")
                + (source.snapshotEnabled() ? " with a snapshot on connect" : " with no snapshot");
    }

    /** How many times the whole map was cleared or evicted under this subscription. */
    long mapWideEvents() {
        return mapWideEvents.get();
    }

    // ---- the snapshot -------------------------------------------------------------------

    /**
     * Emit every entry the connector is subscribed to as an upsert, on the source's thread.
     *
     * <p>Two ways of reading it, and the difference is who narrows the result. With a
     * predicate the cluster has already reduced the map to the slice this connector wants, so
     * {@code entrySet(predicate)} is one query returning that slice. Without one the answer is
     * the whole map, and pulling a large one across in a single call would put all of it in
     * this JVM's heap at once -- so the keys come first and the values in chunks, which also
     * gives {@code close()} somewhere to interrupt.
     */
    private void snapshot(HazelcastInstance client, RecordHandler handler) {
        IMap<Object, Object> subscribed = this.map;
        if (subscribed == null) {
            return;
        }
        String member = connectedMember(client);
        long emitted = 0;
        if (predicate != null) {
            for (Map.Entry<Object, Object> entry : subscribed.entrySet(predicate)) {
                if (this.map == null) {
                    return;
                }
                emit(entry.getKey(), entry.getValue(), member, handler);
                emitted++;
            }
        } else {
            List<Object> keys = new ArrayList<>(subscribed.keySet());
            for (int from = 0; from < keys.size(); from += SNAPSHOT_CHUNK) {
                if (this.map == null) {
                    return;
                }
                Set<Object> chunk = new LinkedHashSet<>(
                        keys.subList(from, Math.min(from + SNAPSHOT_CHUNK, keys.size())));
                for (Map.Entry<Object, Object> entry : subscribed.getAll(chunk).entrySet()) {
                    emit(entry.getKey(), entry.getValue(), member, handler);
                    emitted++;
                }
            }
        }
        log.info("[{}] read {} entries from Hazelcast map '{}'",
                connector.getName(), emitted, source.getMap());
    }

    /** One snapshot row. Failures are per-entry: a map is not abandoned over one value. */
    private void emit(Object key, Object value, String member, RecordHandler handler) {
        try {
            handler.onRecord(SourceRecord.of(text(value), String.valueOf(key))
                    .withAttributes(attributes("SNAPSHOT", member)));
        } catch (RuntimeException e) {
            log.error("[{}] failed to handle Hazelcast map entry '{}'",
                    connector.getName(), key, e);
        }
    }

    // ---- the listener -------------------------------------------------------------------

    /**
     * One listener for every entry event the map can raise, plus the two map-wide ones.
     *
     * <p>One registration rather than seven: they share a key space and an ordering, and
     * registering them separately would make "added then removed" arrive through two
     * subscriptions with nothing keeping them in order.
     *
     * <p>Every callback runs on a Hazelcast event thread, and the pipeline runs inside
     * {@link RecordHandler#onRecord} -- so a handler that blocks blocks this subscription,
     * which is the back-pressure the framework wants.
     */
    private final class EntryListener implements
            EntryAddedListener<Object, Object>,
            EntryUpdatedListener<Object, Object>,
            EntryRemovedListener<Object, Object>,
            EntryEvictedListener<Object, Object>,
            EntryExpiredListener<Object, Object>,
            MapClearedListener,
            MapEvictedListener {

        private final RecordHandler handler;

        private EntryListener(RecordHandler handler) {
            this.handler = handler;
        }

        @Override
        public void entryAdded(EntryEvent<Object, Object> event) {
            upsert(event, "ADDED", handler);
        }

        @Override
        public void entryUpdated(EntryEvent<Object, Object> event) {
            upsert(event, "UPDATED", handler);
        }

        @Override
        public void entryRemoved(EntryEvent<Object, Object> event) {
            removed(event, "REMOVED", handler);
        }

        @Override
        public void entryEvicted(EntryEvent<Object, Object> event) {
            removed(event, "EVICTED", handler);
        }

        @Override
        public void entryExpired(EntryEvent<Object, Object> event) {
            removed(event, "EXPIRED", handler);
        }

        @Override
        public void mapCleared(MapEvent event) {
            mapWide("cleared", event);
        }

        @Override
        public void mapEvicted(MapEvent event) {
            mapWide("evicted", event);
        }
    }

    /** An entry that exists with this value. */
    private void upsert(EntryEvent<Object, Object> event, String kind, RecordHandler handler) {
        try {
            handler.onRecord(SourceRecord
                    .of(text(event.getValue()), String.valueOf(event.getKey()))
                    .withAttributes(attributes(kind, memberOf(event))));
        } catch (RuntimeException e) {
            // One bad record is not a reason to drop the subscription.
            log.error("[{}] failed to handle Hazelcast map {} event for key '{}'",
                    connector.getName(), kind, event.getKey(), e);
        }
    }

    /**
     * An entry that is gone, however it went.
     *
     * <p>Removed, evicted and expired are three different reasons and one consequence: the map
     * no longer holds the key. The body is empty rather than the old value -- the pipeline
     * skips the filter for an empty delete and addresses the record by key, which is exactly
     * what a PUBLISHER-keyed SOW topic needs and the only thing a delete can be sure of. The
     * reason survives in the {@code event} attribute, where a transform can see it.
     */
    private void removed(EntryEvent<Object, Object> event, String kind, RecordHandler handler) {
        try {
            handler.onRecord(SourceRecord.delete("", String.valueOf(event.getKey()))
                    .withAttributes(attributes(kind, memberOf(event))));
        } catch (RuntimeException e) {
            log.error("[{}] failed to handle Hazelcast map {} event for key '{}'",
                    connector.getName(), kind, event.getKey(), e);
        }
    }

    /**
     * A map-wide clear or eviction: counted and said out loud, never turned into deletes.
     *
     * <p>Hazelcast reports the number of entries affected and none of their keys, so the only
     * way to delete the corresponding SOW records would be from the connector's own memory of
     * what the map held -- a guess, applied destructively, by a process that may have started
     * after most of those entries were written. WARN once per event says the SOW and the map
     * have diverged and leaves the decision where it belongs.
     */
    private void mapWide(String what, MapEvent event) {
        mapWideEvents.incrementAndGet();
        log.warn("[{}] Hazelcast map '{}' was {} on {}: {} entries went with no keys reported, "
                        + "so no deletes were published -- the SOW still holds them",
                connector.getName(), source.getMap(), what, memberOf(event),
                event.getNumberOfEntriesAffected());
    }

    // ---- values, keys and metadata ------------------------------------------------------

    /**
     * A map value as the payload the pipeline decodes.
     *
     * <p>A {@code String} is already whatever the feed writes -- JSON, FIX, a line of text --
     * and passes through untouched. A {@link HazelcastJsonValue} is a string Hazelcast knows is
     * JSON (it indexes and queries inside it), so it is its own text. Anything else is a
     * {@code Map}, a {@code List} or a POJO the cluster stores as an object, and Gson renders
     * it as JSON -- which is why a map connector is configured {@code format: JSON} unless the
     * values really are strings in another format.
     *
     * @param value the value from an event or the snapshot
     * @return the payload; {@code ""} for a null value, which only a delete should carry
     */
    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof HazelcastJsonValue json) {
            return json.getValue();
        }
        return GSON.toJson(value);
    }

    /** The attributes every record from this subscription carries. */
    private Map<String, String> attributes(String event, String member) {
        Map<String, String> attributes = new LinkedHashMap<>(4);
        attributes.put(HazelcastRecordSource.ATTRIBUTE_MAP, source.getMap());
        attributes.put(HazelcastRecordSource.ATTRIBUTE_EVENT, event);
        if (member != null) {
            attributes.put(HazelcastRecordSource.ATTRIBUTE_MEMBER, member);
        }
        return attributes;
    }

    private static String memberOf(IMapEvent event) {
        return HazelcastRecordSource.addressOf(event.getMember());
    }

    /**
     * The member a snapshot row is attributed to.
     *
     * <p>A snapshot row is read <em>through</em> the client rather than raised by one member,
     * and a query spans every partition owner, so there is no publisher to name. The cluster
     * member the client lists first is what the attribute reports: enough to say which cluster
     * the row came from, and not a claim about which member owned the partition.
     */
    private static String connectedMember(HazelcastInstance client) {
        for (Member member : client.getCluster().getMembers()) {
            return HazelcastRecordSource.addressOf(member);
        }
        return null;
    }
}
