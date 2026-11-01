package com.demo.amps.fix42.state.config;

/** The HAClient's publish store (docs/04 §6, decision 4). */
public enum PublishStoreType {

    /** A memory-mapped file: a message the FIX engine acknowledged survives a process crash. Production. */
    FILE,

    /** On the heap: survives an AMPS restart or a network drop, not a crash of this process. Demo and tests. */
    MEMORY,

    /** None: the stateless publisher's at-most-once, and no server-side rejection is ever reported. */
    NONE
}
