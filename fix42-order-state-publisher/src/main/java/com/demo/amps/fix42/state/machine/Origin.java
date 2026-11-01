package com.demo.amps.fix42.state.machine;

/** How a chain came to exist: tag 12018. */
public enum Origin {
    /** Opened by its own 35=D. */
    D,
    /** First seen through a report or a request; the D, if any, has not arrived. */
    MIDSTREAM,
    /** Opened mid-stream, then joined by its late D. */
    D_LATE,
    /** Rebuilt from a record whose encodings did not parse; loaded with an empty set. */
    UNRECOVERED
}
