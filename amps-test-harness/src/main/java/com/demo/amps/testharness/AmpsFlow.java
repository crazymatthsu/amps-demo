package com.demo.amps.testharness;

/**
 * Everything that differs between one module's AMPS test instance and
 * another's.
 *
 * <p>Three modules start a container for their integration tests, and until
 * this record existed each carried its own near-identical copy of the harness.
 * Comparing them, the whole of the variation was four values -- and one of
 * those is the package the copy happened to live in. What remains is here.
 *
 * @param flow        the directory under {@code server/config/flows} whose
 *                    {@code amps-config.xml} the server runs
 * @param shortName   the tag in container and data-directory names, so a
 *                    stray container says which suite left it behind
 * @param messageType the message type the client URI selects: {@code fix} for
 *                    a FIX-typed topic, {@code json} for a JSON one. Getting
 *                    this wrong is not a connection error -- the client
 *                    connects and then cannot parse anything.
 */
public record AmpsFlow(String flow, String shortName, String messageType) {

    /**
     * The flows this repository defines, mirroring {@code server/config/flows}.
     *
     * <p>Kept here rather than in each consumer so the set is enumerable in one
     * place, the same way the directory listing is.
     */
    public static final AmpsFlow FIX42_CHAINING = new AmpsFlow("fix42-chaining", "fix42", "fix");

    public static final AmpsFlow CACHE = new AmpsFlow("cache", "cache", "json");

    public static final AmpsFlow HAZELCAST = new AmpsFlow("hazelcast", "hz", "json");

    /** Where this flow's config lives, relative to the repository root. */
    public String configDir() {
        return "server/config/flows/" + flow;
    }
}
