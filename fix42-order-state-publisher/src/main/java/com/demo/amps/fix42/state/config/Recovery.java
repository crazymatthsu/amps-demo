package com.demo.amps.fix42.state.config;

/** How the cache is rebuilt at startup (docs/04 §2). */
public enum Recovery {

    /** One sow query over the blotter: the record is the checkpoint. The default. */
    SOW,

    /** Start empty; refused against a non-empty blotter unless {@code allow-incomplete-cache}. */
    NONE
}
