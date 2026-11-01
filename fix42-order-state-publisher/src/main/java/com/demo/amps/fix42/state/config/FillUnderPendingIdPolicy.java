package com.demo.amps.fix42.state.config;

/** Whether a fill carrying a pending amend's ClOrdID answers it (docs/03 §6.4, 06 E8). */
public enum FillUnderPendingIdPolicy {

    /** Not an answer: the FIX state matrices' shape, and what the mock venue emits. The default. */
    IGNORE,

    /**
     * The fill answers the REPLACE when its 38/44 equal the proposed terms --
     * evidence the amend took effect and its {@code 150=5} was missed. Never
     * an inferred REPLACE, which has no terms.
     */
    ANSWERS_REPLACE_IF_TERMS_MATCH
}
