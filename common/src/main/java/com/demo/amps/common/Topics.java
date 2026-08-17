package com.demo.amps.common;

/**
 * Topic names used across the demos.
 *
 * <p>They fall into three groups, and the group determines which AMPS features
 * are available on them -- this is the single most important thing to internalise
 * about AMPS topics:
 *
 * <ul>
 *   <li><b>Undeclared (dynamic) topics</b> -- anything not named in the config.
 *       They spring into existence on first publish, cost nothing, and support
 *       pub/sub only. No SOW, so no query, no snapshot, no delta.</li>
 *   <li><b>SOW topics</b> -- declared in {@code <SOW>} with a {@code <Key>}. AMPS
 *       keeps the latest message per key, which unlocks query, query-and-subscribe,
 *       delta publish/subscribe, OOF, and expiration.</li>
 *   <li><b>Transaction-logged topics</b> -- declared in {@code <TransactionLog>}.
 *       Every message is journalled, which unlocks bookmark subscriptions and
 *       replay. Orthogonal to SOW: a topic can be one, the other, both, or
 *       neither.</li>
 * </ul>
 */
public final class Topics {

    private Topics() {
    }

    // ---- SOW + transaction log -------------------------------------------------

    /** Keyed on {@code /orderId}. Journalled. The workhorse of the SOW demos. */
    public static final String ORDERS = "orders";

    /** Keyed on {@code /symbol}. Journalled. Large records for the delta demos. */
    public static final String INSTRUMENTS = "instruments";

    // ---- SOW without a transaction log ----------------------------------------

    /**
     * Keyed on {@code /symbol} with an {@code <Expiration>}. Deliberately NOT
     * journalled: a volatile cache does not need replay, and keeping it out of
     * the journal is the cheapest transaction-log reduction there is.
     */
    public static final String QUOTE_CACHE = "quote-cache";

    // ---- Dynamic SOW topics (regex-matched in the config) ----------------------

    /**
     * Prefix for dynamic SOW topics. The config declares the pattern
     * {@code /^desk\.[A-Za-z0-9_-]+$/}, so {@code desk.equities} and
     * {@code desk.rates} each get their own SOW at first use with no config change
     * and no restart.
     */
    public static final String DESK_PREFIX = "desk.";

    public static String desk(String name) {
        return DESK_PREFIX + name;
    }

    // ---- Pure pub/sub dynamic topics ------------------------------------------

    /**
     * Prefix for fire-and-forget event topics. Nothing about these is declared
     * anywhere: {@code events.trade.us} works because AMPS lets publishers invent
     * topics. Subscribers reach them with a regex such as {@code ^events\..*}.
     */
    public static final String EVENTS_PREFIX = "events.";

    public static String event(String suffix) {
        return EVENTS_PREFIX + suffix;
    }

    // ---- FIX order-state pattern ----------------------------------------------

    /**
     * Wire-faithful FIX events, keyed on {@code /eventId} so every message is
     * its own SOW record: the SOW doubles as a queryable audit trail
     * ("show me every event for chain X" is a filter, not a replay). Journalled,
     * because this stream is the system of record the state topic is derived
     * from.
     */
    public static final String FIX_EVENTS = "fix.events";

    /**
     * Derived order state, keyed on {@code /chainId} -- the stable identity
     * FixOrderStateMachine maintains across cancel/replace. Deliberately NOT
     * journalled: it is derivable from {@link #FIX_EVENTS} by replay, so
     * journalling it would record the same information twice.
     */
    public static final String FIX_ORDERS = "fix.orders";

    // ---- Native FIX / NVFIX topics --------------------------------------------

    /**
     * Raw FIX payloads ({@code 35=8\x0111=A1\x01...}), MessageType {@code fix}.
     * Events keyed on the custom event-id tag 9001; orders keyed on OrderID
     * (tag 37) and receiving execution reports only, so the last record per key
     * IS the current order state. Only the events topic is journalled.
     */
    public static final String FIX_NATIVE_EVENTS = "fix.native.events";
    public static final String FIX_NATIVE_ORDERS = "fix.native.orders";

    /** The same pattern with named fields, MessageType {@code nvfix}. */
    public static final String NVFIX_NATIVE_EVENTS = "nvfix.native.events";
    public static final String NVFIX_NATIVE_ORDERS = "nvfix.native.orders";

    // ---- Journal-size laboratory ----------------------------------------------

    /** SOW + journalled. Receives whole-record publishes in the lab. */
    public static final String BENCH_FULL = "bench.full";

    /** SOW + journalled. Receives delta publishes in the lab. */
    public static final String BENCH_DELTA = "bench.delta";
}
