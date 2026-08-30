package com.demo.amps.hazelcast;

import com.hazelcast.map.MapLoader;
import com.hazelcast.map.MapStoreFactory;
import java.util.Properties;

/**
 * The one line of wiring a Hazelcast configuration needs: declare this
 * factory on a map's {@code map-store} and set {@code amps.topic} in its
 * properties -- zero code per cache, which is the point of the tier design.
 *
 * <pre>
 * map:
 *   orders:
 *     map-store:
 *       enabled: true
 *       initial-mode: EAGER
 *       factory-class-name: com.demo.amps.hazelcast.AmpsMapStoreFactory
 *       properties:
 *         amps.topic: hz.persistent
 * </pre>
 *
 * <p>Properties (all but the topic optional):
 *
 * <ul>
 *   <li>{@code amps.topic} -- the tier topic this map persists to. The tier
 *       is a POLICY choice: everything on one topic shares its durability,
 *       expiration and journal settings.</li>
 *   <li>{@code amps.uri} -- AMPS URI; defaults to the repo-standard
 *       {@code DemoConfig.uri()} (env/system-property driven).</li>
 *   <li>{@code amps.timeoutMs} -- command timeout.</li>
 *   <li>{@code amps.flushPerWrite} -- default {@code true}; set false for
 *       write-behind maps to skip the per-store flush round trip.</li>
 *   <li>{@code amps.clientName} -- stable AMPS client name (enables publish
 *       store replay across member restarts); default is generated.</li>
 * </ul>
 *
 * <p>Subclass and override {@link #codecFor} to give particular maps typed
 * POJO codecs instead of untyped JSON values.
 */
public class AmpsMapStoreFactory implements MapStoreFactory<String, Object> {

    static final String PROP_TOPIC = "amps.topic";
    static final String PROP_URI = "amps.uri";
    static final String PROP_TIMEOUT_MS = "amps.timeoutMs";
    static final String PROP_FLUSH_PER_WRITE = "amps.flushPerWrite";
    static final String PROP_CLIENT_NAME = "amps.clientName";

    @Override
    public MapLoader<String, Object> newMapStore(String mapName, Properties properties) {
        requiredTopic(properties, mapName); // fail at configuration time, not first use
        return new AmpsHazelcastMapStore<>(codecFor(mapName, properties));
    }

    /** The codec for one map's values; override for typed POJO maps. */
    protected ValueCodec<Object> codecFor(String mapName, Properties properties) {
        return GsonValueCodec.untyped();
    }

    static String requiredTopic(Properties properties, String mapName) {
        String topic = properties.getProperty(PROP_TOPIC);
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("map '" + mapName + "' is missing the '"
                    + PROP_TOPIC + "' map-store property -- every persisted map must name "
                    + "its tier topic (e.g. hz.persistent)");
        }
        return topic;
    }
}
