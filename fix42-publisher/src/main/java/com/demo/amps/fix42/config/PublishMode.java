package com.demo.amps.fix42.config;

/**
 * How a route puts a message on the wire.
 *
 * <p>The distinction is not cosmetic: {@link #FULL} and {@link #DELTA} issue
 * different AMPS commands with different server-side behaviour, and choosing
 * wrongly either loses fields or overwrites ones nobody meant to touch.
 */
public enum PublishMode {

    /**
     * Publish the whole message with {@code publish}: the stored record becomes
     * exactly this message.
     *
     * <p>Correct for {@code 35=D}, which opens a chain -- there is no record to
     * merge into, and every later delta depends on the terms it carries.
     */
    FULL,

    /**
     * Publish a selected subset with {@code delta_publish}: AMPS merges these
     * fields into the stored record and leaves every other field alone.
     *
     * <p>This is what makes a chained topic a live blotter. An amend that
     * changes only price sends tag 44 (plus identity and timestamp), and the
     * symbol, side and account from the original {@code 35=D} are still there
     * afterwards because nothing overwrote them.
     */
    DELTA
}
