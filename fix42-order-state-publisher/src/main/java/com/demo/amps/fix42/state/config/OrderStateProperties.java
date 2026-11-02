package com.demo.amps.fix42.state.config;

import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.machine.MachineOptions;
import com.demo.amps.fix42.state.publish.PublisherOptions;
import com.demo.amps.fix42.state.publish.RoutingOptions;
import com.demo.amps.fix42.state.recovery.RecoveryOptions;
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
 * <p>The state machine decides, in code, which of its own fields reach the
 * blotter: the venue status, the quantities, the chain, the pending set. What
 * is here is the connection, the policy knobs a venue dialect bundles, and the
 * routes: which topics each message goes to, which of its tags they receive
 * ({@code tags}, {@code changeable-tags}), and which of its tags the blotter
 * record carries beside the machine's fields ({@code projection}) -- the
 * same keys as {@code fix42-publisher}'s rulebook (docs/07 §5).
 * {@link #validate()} runs at startup and refuses to boot on a configuration
 * that would fail later at the server, or that would let a route write a
 * field the machine owns.
 *
 * <p>Spring's, and so outside the core: the core takes plain option records,
 * which {@link #machineOptions()}, {@link #publisherOptions()},
 * {@link #routingOptions()} and {@link #recoveryOptions()} map this onto
 * (docs/07 §3).
 *
 * @param amps       the connection and the publish store
 * @param mode       {@code strict} raises on an unrouted or unparseable
 *                   message; {@code lenient} records it on the catch-all topics
 * @param orderState the blotter and the machine's policies
 * @param audit      the pass-through topics' key trailer
 * @param topicKeys  the SOW key of every topic, mirroring the server flow
 * @param routes     routing, first match wins: topics, tag selection, projection
 */
@ConfigurationProperties(prefix = "fix42-order-state")
public record OrderStateProperties(Amps amps,
                                   @DefaultValue("lenient") Mode mode,
                                   OrderState orderState,
                                   Audit audit,
                                   Map<String, List<Integer>> topicKeys,
                                   List<Route> routes) {

    /** The catch-all message type of a lenient-mode route. */
    public static final String ANY_TYPE = com.demo.amps.fix42.state.publish.Route.ANY_TYPE;

    /** Where lenient mode records unparseable bytes, when {@code topic-keys} declares it. */
    private static final String MALFORMED_TOPIC = "fix42/malformed";

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
                             @DefaultValue("2") int schemaVersion,
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

    /**
     * @param keyTag            appended to every pass-through publish as a
     *                          trailer; 0 disables
     * @param fullPublishTopics topic-name patterns ({@code *} matches anything)
     *                          that always receive the message whole, as a
     *                          {@code publish}, whatever a route's {@code tags}
     *                          select: the audit trail records what was
     *                          received (docs/07 §5)
     */
    public record Audit(@DefaultValue("13011") int keyTag,
                        @DefaultValue("*_audit") List<String> fullPublishTopics) {
    }

    /**
     * One route, as bound from the yml; {@link #toRoute()} maps it onto the
     * core's {@code publish.Route}, which the router reads. The matching and
     * tag keys are {@code fix42-publisher}'s (docs/07 §5).
     *
     * @param name           for logs and errors
     * @param msgTypes       tag 35 values, or {@code *} for the lenient catch-all
     * @param execTypes      tag 150 values; empty for any
     * @param execTransTypes tag 20 values; empty for any
     * @param tags           with {@code changeableTags}, the tags {@code topics}
     *                       receive; both empty sends the message whole
     * @param changeableTags the business fields a desk tunes, kept apart from
     *                       the identifying {@code tags} as in fix42-publisher
     * @param topics         destinations, plus the key trailer
     * @param keyGuard       per topic, per key tag, the sentinel values that skip
     *                       that topic: {@code 37=NONE} would collapse execs onto
     *                       one record, {@code 17=0} execs_audit
     * @param applyState     whether the route's messages feed the state machine
     * @param projection     the blotter record's extra tags for this route's
     *                       messages; null for the machine's default
     */
    public record Route(String name,
                        @DefaultValue List<String> msgTypes,
                        @DefaultValue List<String> execTypes,
                        @DefaultValue List<String> execTransTypes,
                        @DefaultValue List<Integer> tags,
                        @DefaultValue List<Integer> changeableTags,
                        @DefaultValue List<String> topics,
                        Map<String, Map<Integer, List<String>>> keyGuard,
                        @DefaultValue("true") boolean applyState,
                        Projection projection) {

        /** The plain route the {@code AuditRouter} reads. */
        public com.demo.amps.fix42.state.publish.Route toRoute() {
            com.demo.amps.fix42.state.machine.Projection compiled = null;
            if (projection != null) {
                try {
                    compiled = projection.compile();
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("route '" + name + "': projection refused: " + e.getMessage(), e);
                }
            }
            return new com.demo.amps.fix42.state.publish.Route(name, msgTypes, execTypes, execTransTypes, topics,
                    selectedTags(), keyGuard, applyState, compiled);
        }

        /** {@code tags} then {@code changeableTags}, de-duplicated, in rule order. */
        public List<Integer> selectedTags() {
            Set<Integer> selected = new LinkedHashSet<>(tags);
            selected.addAll(changeableTags);
            return List.copyOf(selected);
        }

        /** On tag 35 alone. Read without {@link #toRoute()}, which refuses a bad projection. */
        public boolean matches(String msgType) {
            return msgTypes.contains(msgType) || catchAll();
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

    /**
     * What the blotter record carries from a route's messages beside the
     * machine's own fields, as {@code fix42-publisher}'s {@code projection}
     * block says it (docs/07 §5). There is one destination, the blotter, so
     * no {@code projected-topics}.
     *
     * @param tags     copied verbatim; non-empty replaces the machine's default
     *                 pass-through for the route, empty keeps it
     * @param copyTags source tag to destination tag: the source's value under
     *                 the destination's number
     * @param setTags  destination tag to a literal value; never empty
     */
    public record Projection(@DefaultValue List<Integer> tags,
                             @DefaultValue Map<Integer, Integer> copyTags,
                             @DefaultValue Map<Integer, String> setTags) {

        /** The core's compiled projection. */
        public com.demo.amps.fix42.state.machine.Projection compile() {
            return new com.demo.amps.fix42.state.machine.Projection(tags, copyTags, setTags);
        }

        /** Every tag the projection can write. */
        public Set<Integer> producedTags() {
            Set<Integer> produced = new LinkedHashSet<>(tags);
            produced.addAll(copyTags.values());
            produced.addAll(setTags.keySet());
            return produced;
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

    /** The publisher's options: the mode, the blotter, the malformed topic if declared, the flush timeout. */
    public PublisherOptions publisherOptions() {
        return new PublisherOptions(mode, orderState.topic(),
                topicKeys.containsKey(MALFORMED_TOPIC) ? MALFORMED_TOPIC : null, amps.timeoutMs());
    }

    /** The audit router's options: the mode, the key trailer, the topic keys and the routes. */
    public RoutingOptions routingOptions() {
        return new RoutingOptions(mode, audit.keyTag(), topicKeys, routes.stream().map(Route::toRoute).toList(),
                audit.fullPublishTopics());
    }

    /** The startup loader's options: {@code order-state}'s recovery knobs and schema version. */
    public RecoveryOptions recoveryOptions() {
        return new RecoveryOptions(orderState.recovery(), orderState.schemaVersion(),
                orderState.loadOpenOrdersOnly(), orderState.allowIncompleteCache());
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

    /** Whether {@code topic} always receives the message whole ({@code audit.full-publish-topics}). */
    public boolean fullPublish(String topic) {
        return RoutingOptions.fullPublish(audit.fullPublishTopics(), topic);
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
        // Both leave orders with a tombstone and no cache body. A late report
        // for one is rebuilt from its blotter record, and only the miss lookup
        // reads that record: without it the report alone becomes a MIDSTREAM
        // record delta-published over the real one (docs/11 F11).
        boolean evicts = orderState.terminalRetention().compareTo(Duration.ZERO) > 0;
        if (!orderState.missLookup() && (evicts || orderState.loadOpenOrdersOnly())) {
            String setting = evicts && orderState.loadOpenOrdersOnly()
                    ? "terminal-retention and order-state.load-open-orders-only"
                    : evicts ? "terminal-retention" : "load-open-orders-only";
            problems.add("order-state." + setting + " need order-state.miss-lookup: true. An order left "
                    + "with only a tombstone is rebuilt from its blotter record when a late report arrives, "
                    + "and the miss lookup is what reads that record; without it the report would "
                    + "overwrite the record's chain, counters and origin (docs/11 F11)");
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
                        problems.add("route '" + route.name() + "' sends to the blotter; only the machine writes "
                                + "it -- use projection to choose the tags its record carries");
                    }
                }
                checkSelection(route, problems);
                checkProjection(route, problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("fix42-order-state configuration refused:\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    /**
     * {@code tags}/{@code changeable-tags}: at least one topic they apply to,
     * the key of every such topic among them, and never the trailer's tag. A
     * full-publish topic gets the message whole, so the selection does not
     * reach it.
     */
    private void checkSelection(Route route, List<String> problems) {
        String where = "route '" + route.name() + "'";
        if (route.catchAll() && (!route.execTypes().isEmpty() || !route.execTransTypes().isEmpty())) {
            problems.add(where + " matches every type, so exec-types/exec-trans-types would never apply");
        }
        List<Integer> selected = route.selectedTags();
        if (selected.isEmpty()) {
            return;
        }
        if (selected.stream().anyMatch(tag -> tag == null || tag <= 0)) {
            problems.add(where + ": tags must be positive numbers, not " + selected);
        }
        if (audit.keyTag() != 0 && selected.contains(audit.keyTag())) {
            problems.add(where + " selects " + audit.keyTag() + ", the trailer the publisher appends itself");
        }
        List<String> selecting = route.topics().stream().filter(topic -> !fullPublish(topic)).toList();
        if (selecting.isEmpty() && !route.topics().isEmpty()) {
            problems.add(where + " lists tags, but every topic it sends to " + route.topics()
                    + " is a full publish (audit.full-publish-topics " + audit.fullPublishTopics()
                    + "): the selection would never apply -- remove it");
        }
        for (String topic : selecting) {
            for (int key : keysOf(topic)) {
                if (!selected.contains(key)) {
                    problems.add(where + " sends " + topic + " a selection without its key tag " + key
                            + ": AMPS refuses a SOW publish that lacks its key; add " + key + " to tags");
                }
            }
        }
    }

    /**
     * {@code projection}: only on a route that feeds the machine, never a
     * field the machine writes or the internal ranges (the core's own
     * check), and never a tag {@code strip-tags} keeps off the record.
     */
    private void checkProjection(Route route, List<String> problems) {
        Projection projection = route.projection();
        if (projection == null) {
            return;
        }
        String where = "route '" + route.name() + "' projection";
        if (!route.applyState() || route.catchAll()) {
            problems.add(where + ": the route does not feed the state machine, so nothing would reach the blotter");
        }
        for (String problem : com.demo.amps.fix42.state.machine.Projection.problems(projection.tags(),
                projection.copyTags(), projection.setTags())) {
            problems.add(where + ": " + problem);
        }
        Set<Integer> stripped = new LinkedHashSet<>(projection.producedTags());
        stripped.retainAll(orderState.stripTags());
        if (!stripped.isEmpty()) {
            problems.add(where + " writes " + stripped + ", which order-state.strip-tags keeps off the record: "
                    + "remove them from one list or the other");
        }
    }
}
