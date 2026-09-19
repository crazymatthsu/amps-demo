/**
 * The Hazelcast driver for {@code amps-connectors}.
 *
 * <p>{@code HazelcastRecordSource} implements
 * {@link com.demo.amps.connectors.source.RecordSource} over a Hazelcast topic or a Hazelcast
 * map, {@code HazelcastSourceFactory} claims the connectors that configure
 * {@code source.hazelcast}, and {@code HazelcastSourceAutoConfiguration} registers it. The
 * source owns the client, the backoff and the lifecycle; which structure is read is a
 * {@code HazelcastSubscription} -- {@code TopicSubscription} or {@code MapSubscription} --
 * chosen once from {@code topic:} or {@code map:}, of which a connector configures exactly one.
 *
 * <p>Always {@code HazelcastClient.newHazelcastClient}, never an embedded member: a connector
 * that joined the cluster would own a share of its partitions, so restarting the connector
 * would migrate data and a connector bug would become a cluster bug.
 *
 * <p>A <strong>topic</strong> is a stream of payloads with no key and no removal. A plain one
 * is fire-and-forget -- a subscriber sees only what is published after it subscribes -- while
 * a reliable topic is ringbuffer-backed, so {@code reliable-from: OLDEST} replays whatever the
 * buffer still holds. That is the only way this structure recovers messages published while
 * the connector was down.
 *
 * <p>A <strong>map</strong> is keyed state, which is the shape a SOW already has: the entry
 * key becomes the record's key, added and updated become upserts, and removed, evicted and
 * expired become deletes with an empty body. Entry events are at-most-once and unordered
 * across partitions, so the map is also read in full on every (re)connect ({@code snapshot},
 * on by default, optionally narrowed by a {@code predicate}) -- the listener is registered
 * first and the snapshot runs second, which trades a harmless duplicate upsert for the
 * guarantee that nothing written during the read is missed. A cleared or evicted map names no
 * keys, so it is counted and logged rather than turned into guessed deletes.
 *
 * <p>Neither structure has a position to rewind to, so records from either carry no
 * {@link com.demo.amps.connectors.source.Acknowledgment}.
 */
package com.demo.amps.connectors.hazelcast;
