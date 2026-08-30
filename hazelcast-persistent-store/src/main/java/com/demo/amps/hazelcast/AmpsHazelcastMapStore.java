package com.demo.amps.hazelcast;

import com.demo.amps.common.DemoConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.MapLoaderLifecycleSupport;
import com.hazelcast.map.MapStore;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bridge: Hazelcast's {@link MapStore} SPI implemented on an AMPS tier
 * topic, one instance per map per member, all sharing one AMPS connection.
 *
 * <p>Everything interesting happens around this class, not in it -- which is
 * the point. Hazelcast owns the cache semantics (partition ownership, initial
 * load distribution, write-behind queueing, offloading store calls from
 * partition threads), {@link AmpsTierStore} owns the AMPS semantics, and this
 * adapter only routes SPI calls to the tier under its map's name:
 *
 * <ul>
 *   <li>restart/failover recovery is Hazelcast calling {@link #loadAllKeys()}
 *       once and then {@link #loadAll(Collection)} on each partition owner --
 *       set {@code initial-mode: EAGER} to hydrate at startup;</li>
 *   <li>{@code store} is invoked on a key's partition owner only, so one
 *       writer per key in normal operation;</li>
 *   <li>{@code IMap.clear()} arrives as {@link #deleteAll(Collection)} and
 *       wipes this map's records from AMPS -- {@code evictAll()} does not;</li>
 *   <li>expired/evicted entries are NOT deleted here (Hazelcast never calls
 *       delete for them), so pair TTL'd maps with a tier whose AMPS
 *       {@code <Expiration>} matches, or they resurrect on restart.</li>
 * </ul>
 *
 * <p>Configuration arrives via {@link MapLoaderLifecycleSupport#init}: see
 * {@link AmpsMapStoreFactory} for the property names.
 */
public class AmpsHazelcastMapStore<V> implements MapStore<String, V>, MapLoaderLifecycleSupport {

    private static final Logger log = LoggerFactory.getLogger(AmpsHazelcastMapStore.class);

    private final ValueCodec<V> codec;
    private volatile TierStore<V> tier;
    private volatile String mapName;
    private volatile String uri;

    public AmpsHazelcastMapStore(ValueCodec<V> codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** For unit tests: a fully-wired adapter over an arbitrary tier store. */
    AmpsHazelcastMapStore(TierStore<V> tier, String mapName) {
        this.codec = null;
        this.tier = tier;
        this.mapName = mapName;
    }

    @Override
    public void init(HazelcastInstance instance, Properties properties, String mapName) {
        this.mapName = mapName;
        String topic = AmpsMapStoreFactory.requiredTopic(properties, mapName);
        this.uri = properties.getProperty(AmpsMapStoreFactory.PROP_URI, DemoConfig.uri());
        long timeoutMillis = Long.parseLong(properties.getProperty(
                AmpsMapStoreFactory.PROP_TIMEOUT_MS, String.valueOf(DemoConfig.timeoutMillis())));
        boolean flushPerWrite = Boolean.parseBoolean(properties.getProperty(
                AmpsMapStoreFactory.PROP_FLUSH_PER_WRITE, "true"));
        String clientName = properties.getProperty(AmpsMapStoreFactory.PROP_CLIENT_NAME);

        this.tier = decorate(new AmpsTierStore<>(
                SharedAmpsClients.acquire(uri, clientName), topic, codec,
                timeoutMillis, flushPerWrite));
        log.info("map '{}' persisting to tier topic '{}' at {} ({})",
                mapName, topic, uri, flushPerWrite ? "flush per write" : "write-behind barrier");
    }

    /** Seam for tests to observe tier-store traffic (e.g. counting decorators). */
    protected TierStore<V> decorate(TierStore<V> tier) {
        return tier;
    }

    @Override
    public void destroy() {
        if (uri != null) {
            SharedAmpsClients.release(uri);
        }
    }

    // ------------------------------------------------------------------
    // MapLoader: hydration and read-through, per Hazelcast's protocol.
    // ------------------------------------------------------------------

    @Override
    public V load(String key) {
        return tier().load(mapName, key);
    }

    @Override
    public Map<String, V> loadAll(Collection<String> keys) {
        return tier().loadAll(mapName, keys);
    }

    @Override
    public Iterable<String> loadAllKeys() {
        return tier().loadKeys(mapName);
    }

    // ------------------------------------------------------------------
    // MapStore: write-through / write-behind persistence.
    // ------------------------------------------------------------------

    @Override
    public void store(String key, V value) {
        tier().store(mapName, key, value);
    }

    @Override
    public void storeAll(Map<String, V> entries) {
        tier().storeAll(mapName, entries);
    }

    @Override
    public void delete(String key) {
        tier().delete(mapName, key);
    }

    @Override
    public void deleteAll(Collection<String> keys) {
        tier().deleteAll(mapName, keys);
    }

    private TierStore<V> tier() {
        TierStore<V> current = tier;
        if (current == null) {
            throw new IllegalStateException("MapStore for '" + mapName + "' used before init() "
                    + "-- was it configured with MapLoaderLifecycleSupport intact?");
        }
        return current;
    }
}
