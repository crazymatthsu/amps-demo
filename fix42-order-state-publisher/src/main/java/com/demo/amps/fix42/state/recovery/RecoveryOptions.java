package com.demo.amps.fix42.state.recovery;

import com.demo.amps.fix42.state.config.Recovery;

/**
 * The {@link SowCacheLoader}'s constructor options, plain, so the loader
 * needs no configuration binder. {@code OrderStateProperties.recoveryOptions()}
 * maps the shipped {@code application.yml} onto it. Where the records come
 * from is the {@link RecordSource}, not an option.
 *
 * @param recovery             {@code SOW} rebuilds the cache from every
 *                             record; {@code NONE} starts empty
 * @param schemaVersion        the newest record schema this publisher reads;
 *                             a newer record refuses the start (R17)
 * @param loadOpenOrdersOnly   keep a body for open orders only, and a
 *                             tombstone for the terminal ones
 * @param allowIncompleteCache let {@code recovery: NONE} start against a
 *                             non-empty source (R4)
 */
public record RecoveryOptions(Recovery recovery, int schemaVersion, boolean loadOpenOrdersOnly,
                              boolean allowIncompleteCache) {
}
