package com.demo.amps.fix42.state.config;

/** What happens to a message no route claims, or bytes that do not parse (docs/03 §9, decision 5). */
public enum Mode {

    /**
     * Raise, and stop: right for tests and development, where a missing rule
     * should fail the first run that hits it. The {@code demo} profile.
     */
    STRICT,

    /**
     * Record it whole on {@code fix42/unrouted} or {@code fix42/malformed} --
     * journalled, so the day can be replayed into a later version -- count
     * it, and carry on. Production's.
     */
    LENIENT
}
