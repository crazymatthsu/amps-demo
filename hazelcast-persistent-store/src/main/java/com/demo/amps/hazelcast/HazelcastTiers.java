package com.demo.amps.hazelcast;

/**
 * The tier topics the `hazelcast` server flow declares
 * ({@code server/config/flows/hazelcast/amps-config.xml}).
 *
 * <p>A tier is a persistence POLICY, not a cache: any number of Hazelcast
 * maps share a tier topic (the composite {@code /map} + {@code /key} SOW key
 * keeps them apart), and a map picks its tier with the {@code amps.topic}
 * map-store property. Add a tier only when you need a different policy, not
 * when you add a map.
 */
public final class HazelcastTiers {

    /**
     * Durable tier: {@code Durability persistent}, journalled. Maps whose
     * contents must survive both member restarts and server crashes.
     */
    public static final String PERSISTENT = "hz.persistent";

    /**
     * Volatile tier: {@code Durability transient} with a server-side
     * {@code <Expiration>}. For TTL'd, reconstructible maps -- and the
     * expiration is not decoration: Hazelcast never calls {@code delete()}
     * for entries IT expires, so the AMPS-side TTL is what stops them
     * resurrecting on the next restart.
     */
    public static final String VOLATILE = "hz.volatile";

    private HazelcastTiers() {
    }
}
