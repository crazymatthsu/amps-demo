package com.demo.amps.fix42.state.spi;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.machine.Outcome;

/**
 * Tier two of docs/07: behaviour the policy knobs do not cover, without a
 * fork of the machine. Hooks are Spring beans; every one found is called in
 * {@code @Order} sequence, and each method has a no-op default.
 *
 * <p>What a hook must not do: <b>touch the fields the machine owns</b>
 * ({@link RecordFields} refuses them), <b>keep state of its own</b> (replay
 * converges only because the machine is deterministic), <b>block</b> (it runs
 * on the publishing thread), or <b>throw</b> -- a hook that throws is a bug,
 * and the machine treats it as one: nothing is committed, the exception
 * propagates out of the feed adapter, and the feed stops at that message
 * (R20a). A hook that wants to skip a message returns
 * {@link Decision#ignore(String)}.
 */
public interface OrderStateHook {

    /** Before resolution. Return the key of an existing chain to override identity, or null. */
    default String resolveKey(FixFrame message, IndexView index) {
        return null;
    }

    /** Before the rules run. May veto the message or hand back a normalised one. */
    default Decision beforeApply(FixFrame message, OrderStateView state) {
        return Decision.proceed();
    }

    /** After the rules ran and the dirty mask is final. May add record fields in 12100-12999. */
    default void afterApply(FixFrame message, OrderStateView before, OrderStateView after, Outcome outcome,
                            RecordFields extra) {
    }

    /** When the machine ignored a message, with the reason. */
    default void onIgnored(FixFrame message, OrderStateView state, IgnoreReason reason) {
    }

    /** When a chain is created, before the first publish. May seed record fields in 12100-12999. */
    default void onCreate(FixFrame message, OrderStateView state, RecordFields extra) {
    }
}
