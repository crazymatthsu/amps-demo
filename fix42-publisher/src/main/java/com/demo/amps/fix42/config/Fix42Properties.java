package com.demo.amps.fix42.config;

import com.demo.amps.fix42.mock.OrderScope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The publisher's entire rulebook, bound from {@code application.yml}.
 *
 * <p>Which tags leave this process is configuration, not code. A route says
 * "for this message type, send these fields to these topics", and
 * {@code application.yml} is where the answer lives -- so changing what an
 * amend carries is a config edit, not a recompile.
 *
 * <p>{@link #validate()} runs at startup and refuses to boot on a rule that
 * would fail later at the server. The one that matters most: <b>a SOW publish
 * that lacks its topic's key field is rejected by AMPS</b>, so a selection rule
 * that omits a key tag produces messages the server silently will not store.
 * That is a miserable thing to debug from the outside and a two-line check
 * from the inside.
 *
 * @param amps      connection settings
 * @param topicKeys the key field of every topic, mirroring the server flow config
 * @param routes    matched in order; the first route matching a message wins
 */
@ConfigurationProperties(prefix = "fix42")
public record Fix42Properties(Amps amps, Map<String, List<Integer>> topicKeys, List<Route> routes) {

    /**
     * Where the placeholder in a topic pattern gets substituted with
     * {@code parent} or {@code child}.
     */
    public static final String SCOPE_PLACEHOLDER = "{scope}";

    /**
     * AMPS connection settings.
     *
     * @param uri        client URI; the trailing path selects the message type,
     *                   which must be {@code fix} for these topics
     * @param clientName the publisher's identity to the server
     * @param timeoutMs  command timeout, milliseconds
     */
    public record Amps(@DefaultValue("tcp://127.0.0.1:9007/amps/fix") String uri,
                       @DefaultValue("fix42-delta-publisher") String clientName,
                       @DefaultValue("10000") long timeoutMs) {
    }

    /**
     * One routing rule.
     *
     * @param name            what this rule is, for logs and error messages
     * @param msgTypes        tag 35 values this rule handles
     * @param execTypes       tag 150 values it handles; empty means "any".
     *                        This is what splits {@code 35=8} into new-ack,
     *                        partial-fill, fill and cancel-confirmed rules,
     *                        each carrying a different field set
     * @param mode            {@link PublishMode#FULL} or {@link PublishMode#DELTA}
     * @param tags            the identity and always-sent fields to extract
     * @param changeableTags  the mutable business fields to extract; kept
     *                        separate from {@code tags} because these are the
     *                        ones a desk actually tunes -- "what may an amend
     *                        change?" is a different question from "what
     *                        identifies it?"
     * @param topics          destinations; may contain {@value #SCOPE_PLACEHOLDER}
     */
    public record Route(String name,
                        @DefaultValue List<String> msgTypes,
                        @DefaultValue List<String> execTypes,
                        @DefaultValue("DELTA") PublishMode mode,
                        @DefaultValue List<Integer> tags,
                        @DefaultValue List<Integer> changeableTags,
                        @DefaultValue List<String> topics,
                        @DefaultValue List<String> projectedTopics,
                        Projection projection) {

        /** True when this rule handles the given message type and exec type. */
        public boolean matches(String msgType, String execType) {
            if (!msgTypes.contains(msgType)) {
                return false;
            }
            return execTypes.isEmpty() || execTypes.contains(execType);
        }

        /**
         * Every tag this route extracts: identity fields first, then the
         * changeable ones, de-duplicated and in declaration order so the
         * payload is stable and readable.
         */
        public List<Integer> selectedTags() {
            Set<Integer> selected = new LinkedHashSet<>(tags);
            selected.addAll(changeableTags);
            return List.copyOf(selected);
        }
    }

    /**
     * A second, differently-shaped payload for the same message.
     *
     * <p>This is what keeps a proposal out of the acked fields. An amend
     * carries its new quantity in tag 38; publishing that verbatim onto the
     * blotter overwrites the quantity the venue actually acked, and a merge
     * cannot put it back. A projection rewrites the payload on its way to the
     * blotter -- 38 becomes 9010, 44 becomes 9011 -- so the acked terms are
     * never touched and both values are readable at once.
     *
     * <p>The audit topics keep receiving the unprojected payload, because an
     * audit trail should record what was sent, not a rewrite of it.
     *
     * @param tags     what to copy verbatim from the source message. Empty
     *                 means "whatever the route itself selects" (the whole
     *                 message, for a FULL route)
     * @param copyTags source tag to destination tag. The source's VALUE is
     *                 written under the destination's number, which is how
     *                 38 -> 9010 works, and also how a reject restores tag 11
     *                 from tag 41
     * @param setTags  destination tag to literal value: the pending-state
     *                 flags, and the sentinels that clear them. Clearing is a
     *                 write rather than a removal because a delta publish
     *                 cannot remove a field
     */
    public record Projection(@DefaultValue List<Integer> tags,
                             @DefaultValue Map<Integer, Integer> copyTags,
                             @DefaultValue Map<Integer, String> setTags) {

        /** Every tag the projected payload can contain, for key validation. */
        public Set<Integer> producedTags() {
            Set<Integer> produced = new LinkedHashSet<>(tags);
            produced.addAll(copyTags.values());
            produced.addAll(setTags.keySet());
            return produced;
        }
    }

    /**
     * Checks the rulebook against itself and against the topics it names.
     *
     * @throws IllegalStateException with every problem found, not just the first
     */
    public void validate() {
        List<String> problems = new ArrayList<>();

        if (routes == null || routes.isEmpty()) {
            throw new IllegalStateException("fix42.routes is empty: the publisher would drop "
                    + "every message. Declare at least one route in application.yml.");
        }

        Set<String> names = new LinkedHashSet<>();
        for (Route route : routes) {
            String where = "fix42.routes[" + route.name() + "]";

            if (route.name() == null || route.name().isBlank()) {
                problems.add("a route has no name; names appear in logs and errors");
            } else if (!names.add(route.name())) {
                problems.add(where + ": duplicate route name");
            }
            if (route.msgTypes().isEmpty()) {
                problems.add(where + ": msg-types is empty, so this route can never match");
            }
            if (route.topics().isEmpty() && route.projectedTopics().isEmpty()) {
                problems.add(where + ": topics is empty, so matching messages would go nowhere");
            }
            if (!route.projectedTopics().isEmpty() && route.projection() == null) {
                problems.add(where + ": projected-topics needs a projection block saying how "
                        + "to reshape the payload");
            }
            if (route.projection() != null && route.projectedTopics().isEmpty()) {
                problems.add(where + ": a projection with no projected-topics is never applied");
            }
            if (route.mode() == PublishMode.DELTA && route.selectedTags().isEmpty()) {
                problems.add(where + ": mode DELTA with no tags would publish an empty message");
            }
            if (route.mode() == PublishMode.FULL
                    && !(route.tags().isEmpty() && route.changeableTags().isEmpty())) {
                problems.add(where + ": mode FULL publishes the whole message, so tags/"
                        + "changeable-tags are ignored -- remove them or switch to DELTA");
            }

            for (String topic : route.topics()) {
                for (String resolved : resolveAllScopes(topic)) {
                    problems.addAll(checkKeys(where, route, resolved, plainTags(route)));
                }
            }
            for (String topic : route.projectedTopics()) {
                for (String resolved : resolveAllScopes(topic)) {
                    problems.addAll(checkKeys(where + " (projected)", route, resolved,
                            projectedTags(route)));
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("fix42 publisher configuration is not usable:\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    /**
     * A delta publish must carry the destination topic's key field, or AMPS
     * cannot decide which record to merge into and rejects the message.
     */
    private List<String> checkKeys(String where, Route route, String topic,
                                   Set<Integer> producedTags) {
        List<Integer> keys = topicKeys == null ? null : topicKeys.get(topic);
        if (keys == null) {
            return List.of(where + ": topic '" + topic + "' has no entry under fix42.topic-keys, "
                    + "so its key field cannot be checked. Add one mirroring the <Key> in "
                    + "server/config/flows/fix42-chaining/amps-config.xml.");
        }
        if (producedTags == null) {
            // A FULL route with no projection sends the whole message; whether
            // it carries the key is a property of the message, checked per
            // message at publish time.
            return List.of();
        }
        Set<Integer> missing = new TreeSet<>(keys);
        missing.removeAll(producedTags);
        if (missing.isEmpty()) {
            return List.of();
        }
        return List.of(where + ": publishes to '" + topic + "' without its SOW key field "
                + missing + ". AMPS rejects a SOW publish that lacks its key, so these messages "
                + "would never be stored. Add " + missing + " to tags.");
    }

    /** Tags an unprojected payload can carry; null when the whole message goes. */
    private static Set<Integer> plainTags(Route route) {
        return route.mode() == PublishMode.FULL ? null : Set.copyOf(route.selectedTags());
    }

    /**
     * Tags a projected payload can carry.
     *
     * <p>A projection with no {@code tags} of its own inherits the route's
     * selection -- or, on a FULL route, the whole message, which cannot be
     * checked statically.
     */
    private static Set<Integer> projectedTags(Route route) {
        Projection projection = route.projection();
        if (projection == null) {
            return plainTags(route);
        }
        Set<Integer> produced = new LinkedHashSet<>(projection.producedTags());
        if (projection.tags().isEmpty()) {
            Set<Integer> inherited = plainTags(route);
            if (inherited == null) {
                return null;
            }
            produced.addAll(inherited);
        }
        return produced;
    }

    /** Both concrete topics when a pattern is scoped; the topic itself otherwise. */
    private static List<String> resolveAllScopes(String topic) {
        if (!topic.contains(SCOPE_PLACEHOLDER)) {
            return List.of(topic);
        }
        List<String> resolved = new ArrayList<>();
        for (OrderScope scope : OrderScope.values()) {
            resolved.add(topic.replace(SCOPE_PLACEHOLDER, scope.token()));
        }
        return List.copyOf(resolved);
    }
}
