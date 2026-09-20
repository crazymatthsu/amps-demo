package com.demo.amps.connectors.resource;

/**
 * A named, shared, lifecycle-managed object that transforms look things up in: a reference
 * table loaded from a database, a KDB client answering market data by RIC, a gRPC stub.
 *
 * <p>The seam between the framework and whatever a code transform needs beyond the field
 * map. A {@code RecordTransform} is stateless by contract and runs on a source's reader
 * thread; the connection it enriches from is neither, and it wants starting before the first
 * record and stopping after the last. So the connection is a resource, the
 * {@link ResourceRegistry} starts and stops it around the connectors, and the transform holds
 * it -- found once by name and type, at construction, so a wrong name fails at boot.
 *
 * <p>Two ways to have one. A generic kind with configuration ({@code resources[].jdbc})
 * is built by a {@link ResourceFactory} from its module; anything else is a bean the
 * application declares, which the registry collects and treats exactly the same. Either way
 * the resource owns its own reconnect and retry, like a {@code RecordSource} does: a
 * {@link #start()} that throws is logged and alerted once, and it is the resource's job to
 * keep trying if it means to.
 *
 * <p>Deliberately not {@link AutoCloseable}: Spring closes an {@code AutoCloseable} bean
 * itself during context destruction, which is later than the registry's {@link #stop()} and
 * would run every resource's shutdown twice, out of phase with the connectors that use it.
 */
public interface AppResource {

    /** The name transforms and commands address this resource by. Unique in the registry. */
    String name();

    /**
     * Connect, load, or whatever makes {@link #isAvailable()} true. Called once, before the
     * connectors start.
     *
     * @throws Exception if it could not; logged and alerted, and the other resources still
     *     start. A resource that means to retry does so on its own thread
     */
    void start() throws Exception;

    /** Disconnect and release. Called once, after the connectors stopped. Idempotent. */
    void stop();

    /**
     * Whether a lookup right now would answer from real data. Drives the status line, and a
     * transform's decision to pass a record through unenriched rather than guess.
     */
    boolean isAvailable();

    /** Whether {@link #reload()} means anything here. A client has nothing to reload. */
    default boolean isReloadable() {
        return false;
    }

    /**
     * Re-read whatever this resource holds, on demand -- what a {@code reload} command asks
     * for. A failed reload keeps what was loaded before.
     *
     * @throws Exception if the reload failed; the previous contents stay in force
     * @throws UnsupportedOperationException if {@link #isReloadable()} is {@code false}
     */
    default void reload() throws Exception {
        throw new UnsupportedOperationException("resource '" + name() + "' is not reloadable");
    }

    /**
     * One line for the periodic status log, starting with the name.
     *
     * @return e.g. {@code instruments AVAILABLE rows=1234 loaded=2026-09-19T14:00:00Z}
     */
    default String status() {
        return name() + (isAvailable() ? " AVAILABLE" : " UNAVAILABLE");
    }
}
