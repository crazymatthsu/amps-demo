package com.demo.amps.connectors.hazelcast;

import com.demo.amps.connectors.source.RecordHandler;
import com.hazelcast.core.HazelcastInstance;

/**
 * What a Hazelcast connector actually reads -- a topic or a map -- behind one seam.
 *
 * <p>{@link HazelcastRecordSource} owns everything that is the same for both: building the
 * client, reporting {@link HazelcastRecordSource#isConnected()}, backing off and rebuilding
 * after the client stops, and shutting the whole thing down. What differs is only which
 * structure is subscribed to and what a message from it means, and that is this interface.
 *
 * <p>The split is not tidiness. A topic message is a payload with no key and no way to say
 * that something stopped existing; a map entry is a key, a value and an event type that
 * includes removal. Bridging them through one listener with a pile of conditionals would make
 * every line read as if it applied to both.
 *
 * <p>Implementations are called from the source's own thread for {@link #subscribe} and
 * {@link #refresh}, and from whichever thread reaches {@code disconnect()} first for
 * {@link #unsubscribe} -- so {@code unsubscribe()} has to be idempotent and has to be safe
 * while a {@code subscribe()} is still emitting records.
 */
interface HazelcastSubscription {

    /**
     * Attach to the structure and start delivering records.
     *
     * <p>Runs on the source thread, and may keep running for a while: a map subscription's
     * snapshot is emitted here, on this thread, so the pipeline back-pressures it the same way
     * it back-pressures a live event.
     *
     * @param client the connected client
     * @param handler where records go
     */
    void subscribe(HazelcastInstance client, RecordHandler handler);

    /**
     * Re-read whatever the structure holds, after Hazelcast reconnected the client by itself.
     *
     * <p>A blip is invisible to the connect loop -- the client stays the same instance and
     * re-registers its listeners -- but it is not invisible to the feed: events raised while
     * the client was away were not queued for it. A structure that can be re-read repairs
     * itself here; one that cannot does nothing, which is why the default is empty.
     *
     * @param client the same client, reconnected
     * @param handler where records go
     */
    default void refresh(HazelcastInstance client, RecordHandler handler) {
        // A topic has no contents: there is nothing to re-read, and replaying a ringbuffer
        // that Hazelcast has already resumed would duplicate a stream nobody can de-duplicate.
    }

    /** Remove the listener. Idempotent, and called from close() as well as the source thread. */
    void unsubscribe();

    /**
     * What this subscription reads, for the log line that says the connector is up.
     *
     * @return e.g. {@code reliable topic 'events' from OLDEST}, {@code map 'positions'}
     */
    String describe();
}
