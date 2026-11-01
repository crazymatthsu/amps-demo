package com.demo.amps.fix42.state.config;

import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.machine.MachineOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The publisher's configuration, bound from {@code application.yml}
 * (docs/02 §6).
 *
 * <p>Unlike {@code fix42-publisher}'s rulebook, nothing here says which tags
 * reach the blotter: that is the state machine's, in code. What is here is
 * the connection, the policy knobs a venue dialect bundles, and the
 * pass-through routing of the audit topics. {@link #validate()} runs at
 * startup and refuses to boot on a configuration that would fail later at
 * the server.
 *
 * @param amps       the connection and the publish store
 * @param mode       {@code strict} raises on an unrouted or unparseable
 *                   message; {@code lenient} records it on the catch-all topics
 * @param orderState the blotter and the machine's policies
 * @param audit      the pass-through topics' key trailer
 * @param topicKeys  the SOW key of every topic, mirroring the server flow
 * @param routes     pass-through routing, first match wins
 */
@ConfigurationProperties(prefix = "fix42-order-state")
public record OrderStateProperties(Amps amps,
                                   @DefaultValue("lenient") Mode mode,
                                   OrderState orderState,
                                   Audit audit,
                                   Map<String, List<Integer>> topicKeys,
                                   List<Route> routes) {

    /** The catch-all message type of a lenient-mode route. */
    public static final String ANY_TYPE = "*";

    /**
     * @param uri              must select the fix message type: a URI ending {@code /fix}
     * @param clientName       stable across restarts: AMPS de-duplicates a store replay by it
     * @param publishStore     FILE in production, MEMORY for the demo and tests
     * @param publishStoreDir  where a FILE store lives, named after the client
     */
    public record Amps(@DefaultValue("tcp://127.0.0.1:9007/amps/fix") String uri,
                       @DefaultValue("fix42-order-state-publisher") String clientName,
                       @DefaultValue("10000") long timeoutMs,
                       @DefaultValue("FILE") PublishStoreType publishStore,
                       @DefaultValue("build/client-state/fix42-order-state") String publishStoreDir,
                       @DefaultValue("1000") long reconnectDelayMs) {
    }

    /**
     * The blotter topic and the machine's options; each knob is documented in
     * {@code application.yml} and docs/07 §1.
     */
    public record OrderState(@DefaultValue("sow/fix42/order_state") String topic,
                             @DefaultValue("13011") int keyTag,
                             @DefaultValue("1") int schemaVersion,
                             @DefaultValue("WHEN_NOT_TERMINAL") CancelRejectStatusPolicy cancelRejectStatus,
                             @DefaultValue("APPLY_ECONOMICS") LateFillPolicy lateFillAfterTerminal,
                             @DefaultValue("ADOPT_VENUE_IF_LATER") NonFillAfterTerminalPolicy nonFillAfterTerminal,
                             @DefaultValue("IGNORE") FillUnderPendingIdPolicy fillUnderPendingId,
                             @DefaultValue("true") boolean staleGuard,
                             @DefaultValue("true") boolean transactTimeGuard,
                             @DefaultValue("8") int execIdHistory,
                             @DefaultValue({"18", "40", "44", "59", "99", "100", "110", "111", "126"})
                             List<Integer> orderTermTags,
                             @DefaultValue({"8", "9", "10", "34", "43", "49", "50", "52", "56", "57", "97", "115",
                                     "116", "122", "128", "129"})
                             List<Integer> stripTags,
                             @DefaultValue("50") int chainIdsPublished,
                             @DefaultValue("PT0S") Duration terminalRetention,
                             @DefaultValue("SOW") Recovery recovery,
                             @DefaultValue("false") boolean loadOpenOrdersOnly,
                             @DefaultValue("false") boolean allowIncompleteCache,
                             @DefaultValue("true") boolean missLookup,
                             @DefaultValue("false") boolean missLookupByOrderId,
                             @DefaultValue("false") boolean publishUnchanged,
                             @DefaultValue("default") String dialect,
                             Map<String, Dialect> dialects) {
    }

    /**
     * A named bundle of the dialect knobs, one per venue (docs/07 §1). A knob
     * left out keeps the {@code order-state} value.
     */
    public record Dialect(CancelRejectStatusPolicy cancelRejectStatus,
                          LateFillPolicy lateFillAfterTerminal,
                          NonFillAfterTerminalPolicy nonFillAfterTerminal,
                          FillUnderPendingIdPolicy fillUnderPendingId,
                          Boolean staleGuard,
                          Boolean transactTimeGuard) {
    }

    /** @param keyTag appended to every pass-through publish as a trailer; 0 disables */
    public record Audit(@DefaultValue("13011") int keyTag) {
    }

    /**
     * One pass-through route.
     *
     * @param name       for logs and errors
     * @param msgTypes   tag 35 values, or {@code *} for the lenient catch-all
     * @param topics     destinations; the message goes whole, plus the key trailer
     * @param keyGuard   per topic, per key tag, the sentinel values that skip
     *                   that topic: {@code 37=NONE} would collapse execs onto
     *                   one record, {@code 17=0} execs_audit
     * @param applyState whether the route's messages feed the state machine
     */
    public record Route(String name,
                        @DefaultValue List<String> msgTypes,
                        @DefaultValue List<String> topics,
                        Map<String, Map<Integer, List<String>>> keyGuard,
                        @DefaultValue("true") boolean applyState) {

        public boolean matches(String msgType) {
            return msgTypes.contains(msgType) || msgTypes.contains(ANY_TYPE);
        }

        public boolean catchAll() {
            return msgTypes.contains(ANY_TYPE);
        }

        /** Whether {@code value} of {@code tag} is a sentinel that skips {@code topic}. */
        public boolean guarded(String topic, int tag, String value) {
            if (keyGuard == null) {
                return false;
            }
            Map<Integer, List<String>> guards = keyGuard.get(topic);
            return guards != null && guards.getOrDefault(tag, List.of()).contains(value);
        }
    }

    // ---- derived ----------------------------------------------------------------

    /** The machine's options: {@code order-state}'s knobs, overlaid with the selected dialect. */
    public MachineOptions machineOptions() {
        OrderState s = orderState;
        Dialect d = selectedDialect();
        return new MachineOptions(
                d != null && d.cancelRejectStatus() != null ? d.cancelRejectStatus() : s.cancelRejectStatus(),
                d != null && d.lateFillAfterTerminal() != null ? d.lateFillAfterTerminal() : s.lateFillAfterTerminal(),
                d != null && d.nonFillAfterTerminal() != null ? d.nonFillAfterTerminal() : s.nonFillAfterTerminal(),
                d != null && d.fillUnderPendingId() != null ? d.fillUnderPendingId() : s.fillUnderPendingId(),
                d != null && d.staleGuard() != null ? d.staleGuard() : s.staleGuard(),
                d != null && d.transactTimeGuard() != null ? d.transactTimeGuard() : s.transactTimeGuard(),
                s.execIdHistory(), Set.copyOf(s.orderTermTags()), Set.copyOf(s.stripTags()),
                s.chainIdsPublished(), s.schemaVersion(), s.publishUnchanged());
    }

    private Dialect selectedDialect() {
        if ("default".equals(orderState.dialect())) {
            return null;
        }
        return orderState.dialects() == null ? null : orderState.dialects().get(orderState.dialect());
    }

    /** The key tags of {@code topic}; empty for a non-SOW topic. */
    public List<Integer> keysOf(String topic) {
        return topicKeys.getOrDefault(topic, List.of());
    }

    /**
     * Refuses a configuration that would fail at the server or in the machine:
     * the problems are cheap to find here and miserable to find from a
     * rejected publish.
     *
     * @throws IllegalStateException listing every problem found
     */
    public void validate() {
        List<String> problems = new ArrayList<>();
        if (amps == null || !amps.uri().endsWith("/fix")) {
            problems.add("amps.uri must select the 'fix' message type (end /amps/fix); every topic here is fix-typed");
        }
        if (orderState.keyTag() != FixTags.Record.ROOT_CL_ORD_ID) {
            problems.add("order-state.key-tag must be " + FixTags.Record.ROOT_CL_ORD_ID
                    + ": it is the tag the machine writes the key in");
        }
        if (audit.keyTag() != 0 && audit.keyTag() != orderState.keyTag()) {
            problems.add("audit.key-tag (" + audit.keyTag() + ") must equal order-state.key-tag ("
                    + orderState.keyTag() + ") or be 0: an audit trail filed under a different key "
                    + "cannot be looked up by it (docs/02 §5)");
        }
        if (!List.of(orderState.keyTag()).equals(topicKeys.get(orderState.topic()))) {
            problems.add("topic-keys must declare " + orderState.topic() + " keyed by [" + orderState.keyTag() + "]");
        }
        if (!"default".equals(orderState.dialect()) && selectedDialect() == null) {
            problems.add("order-state.dialect '" + orderState.dialect() + "' is not defined under dialects");
        }
        if (orderState.chainIdsPublished() < 1 || orderState.execIdHistory() < 0) {
            problems.add("chain-ids-published must be >= 1 and exec-id-history >= 0");
        }
        if (routes == null || routes.isEmpty()) {
            problems.add("no routes: every message type needs somewhere to go");
        } else {
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < routes.size(); i++) {
                Route route = routes.get(i);
                if (!names.add(route.name())) {
                    problems.add("route name '" + route.name() + "' is used twice");
                }
                if (route.catchAll() && i != routes.size() - 1) {
                    problems.add("route '" + route.name() + "' matches every type and must be last");
                }
                for (String topic : route.topics()) {
                    if (!topicKeys.containsKey(topic)) {
                        problems.add("route '" + route.name() + "' sends to " + topic
                                + ", which has no topic-keys entry (use [] for a non-SOW topic)");
                    }
                    if (topic.equals(orderState.topic())) {
                        problems.add("route '" + route.name() + "' sends to the blotter; only the machine writes it");
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("fix42-order-state configuration refused:\n  - "
                    + String.join("\n  - ", problems));
        }
    }
}
