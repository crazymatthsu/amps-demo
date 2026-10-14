package com.demo.amps.fix42.publish;

import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.mock.OrderScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a FIX message into the set of publishes it should produce.
 *
 * <p>Stateless on purpose. It reads only the message in front of it -- never a
 * chain history, never a map of what it saw earlier -- which is the point of
 * the whole design: chain identity is the AMPS chaining key generator's job,
 * so the publisher does not need to know that {@code PARENT-AAPL-3} continues
 * {@code PARENT-AAPL-1}. It just sends tags 11 and 41 and lets the server
 * resolve the record.
 *
 * <p>The one thing it does infer is scope, and it infers it from a field:
 * tag 9000 present means a child slice. That is why the mock feed stamps 9000
 * on every request a child chain originates rather than only on its
 * {@code 35=D} -- a stateless router cannot recover the association later.
 *
 * <p>Everything that can be derived from the rulebook alone -- the tag list a
 * route selects, its topic names for either scope, the key tags of each topic,
 * the projection's copy and literal tables -- is derived once, in the
 * constructor. Planning a message then walks arrays and allocates only the
 * payloads and instructions it returns.
 */
@Component
public class PublishPlanner {

    private static final Logger log = LoggerFactory.getLogger(PublishPlanner.class);

    private static final OrderScope[] SCOPES = OrderScope.values();

    private final CompiledRoute[] routes;

    public PublishPlanner(Fix42Properties properties) {
        Map<String, List<Integer>> topicKeys =
                properties.topicKeys() == null ? Map.of() : properties.topicKeys();
        List<Fix42Properties.Route> declared = properties.routes();
        routes = new CompiledRoute[declared.size()];
        for (int i = 0; i < routes.length; i++) {
            routes[i] = new CompiledRoute(declared.get(i), topicKeys);
        }
    }

    /**
     * Plans the publishes for one message.
     *
     * @throws IllegalStateException when no route matches. Silently dropping a
     *         message would leave a gap in a SOW that looks like a bug in AMPS;
     *         failing here names the message type that has no rule.
     */
    public List<PublishInstruction> plan(FixMessage message) {
        String msgType = message.msgType();
        String execType = message.value(FixTags.EXEC_TYPE);
        String execTransType = message.value(FixTags.EXEC_TRANS_TYPE);

        CompiledRoute route = match(msgType, execType, execTransType);
        if (route == null) {
            throw new IllegalStateException(
                    "no fix42 route matches 35=" + msgType
                            + (execType.isEmpty() ? "" : " with 150=" + execType)
                            + (execTransType.isEmpty() ? "" : " and 20=" + execTransType)
                            + ": " + message.printable()
                            + "\nAdd a route for it in application.yml, or a catch-all for "
                            + "this message type, so nothing is dropped unnoticed.");
        }

        FixMessage payload = route.mode == PublishMode.FULL
                ? message
                : message.select(route.selectedTags);

        OrderScope scope = scopeOf(message);
        List<PublishInstruction> instructions =
                new ArrayList<>(route.topics.length + route.projectedTopics.length);
        addInstructions(instructions, route, route.topics, payload, scope, message);

        if (route.projectedTopics.length > 0) {
            FixMessage projected = project(route, message, payload);
            addInstructions(instructions, route, route.projectedTopics, projected, scope, message);
        }
        return List.copyOf(instructions);
    }

    private CompiledRoute match(String msgType, String execType, String execTransType) {
        for (CompiledRoute candidate : routes) {
            if (candidate.route.matches(msgType, execType, execTransType)) {
                return candidate;
            }
        }
        return null;
    }

    private void addInstructions(List<PublishInstruction> instructions, CompiledRoute route,
                                 ResolvedTopic[] topics, FixMessage payload, OrderScope scope,
                                 FixMessage source) {
        for (ResolvedTopic topic : topics) {
            String name = topic.names[scope.ordinal()];
            int missing = missingKey(topic.keys[scope.ordinal()], payload);
            if (missing >= 0) {
                log.error(
                        "route {} would publish to {} without its key field (tag {}); "
                                + "AMPS would reject it, so this message is not sent: {}",
                        route.name, name, missing, source.printable());
            } else {
                instructions.add(new PublishInstruction(name, route.mode, payload, route.name));
            }
        }
    }

    /**
     * Reshapes a payload for the blotter: copy some tags verbatim, rewrite
     * others under a different tag number, and stamp literal values.
     *
     * <p>The rewrite is what keeps a proposal out of the acked fields. A 35=G
     * carries its proposed quantity in tag 38; {@code copy-tags: {38: 9010}}
     * publishes that value as tag 9010 instead, so the record's tag 38 -- the
     * quantity the venue acked -- is never touched. The same mechanism lets a
     * cancel-reject restore the working ClOrdID by copying tag 41 into tag 11.
     *
     * <p>Order matters: verbatim tags first, then copies, then literals, so a
     * literal always wins. That is what makes clearing reliable -- a route can
     * select tag 38 and still stamp {@code 9010=0} over any copy of it.
     */
    private FixMessage project(CompiledRoute route, FixMessage source, FixMessage payload) {
        FixMessage.Builder builder = FixMessage.ofType(source.msgType());
        if (route.projectionTags.length == 0) {
            builder.setAll(payload);
        } else {
            for (int tag : route.projectionTags) {
                String value = source.value(tag);
                if (!value.isEmpty()) {
                    builder.set(tag, value);
                }
            }
        }
        for (int i = 0; i < route.copyFrom.length; i++) {
            String value = source.value(route.copyFrom[i]);
            if (!value.isEmpty()) {
                builder.set(route.copyTo[i], value);
            }
        }
        for (int i = 0; i < route.setTags.length; i++) {
            builder.set(route.setTags[i], route.setValues[i]);
        }
        return builder.build();
    }

    /**
     * Parent or child, from tag 9000 alone.
     *
     * <p>Only affects topics carrying {@code {scope}}. The shipped rulebook has
     * none -- parents and children share {@code sow/fix42/orders} -- but a
     * rulebook that splits the blotter by scope has a consequence worth
     * knowing: a venue message that resolves a pending request also projects
     * onto the order blotter, so execution reports and cancel rejects on a
     * child chain must then carry tag 9000 as well. See
     * {@code OrderChain.execution} for why that is a venue/OMS dependency
     * rather than a free choice.
     */
    public OrderScope scopeOf(FixMessage message) {
        return message.has(FixTags.PARENT_ORDER_ID) ? OrderScope.CHILD : OrderScope.PARENT;
    }

    /**
     * The configured key tag this payload is missing, or {@code -1}.
     *
     * <p>{@code Fix42Properties.validate()} already proved the route's tag list
     * <i>can</i> carry the key; this checks the actual message did. The two
     * differ for optional fields -- a rule may legitimately select tag 41,
     * which the first message of a chain does not have.
     */
    private static int missingKey(int[] keys, FixMessage payload) {
        for (int key : keys) {
            if (!payload.has(key)) {
                return key;
            }
        }
        return -1;
    }

    /** A route with everything the rulebook determines worked out once. */
    private static final class CompiledRoute {

        final Fix42Properties.Route route;
        final String name;
        final PublishMode mode;
        /** Identity tags then changeable tags, de-duplicated, in rule order. */
        final List<Integer> selectedTags;
        final ResolvedTopic[] topics;
        final ResolvedTopic[] projectedTopics;
        /** The projection's verbatim tags; empty means "the payload's own tags". */
        final int[] projectionTags;
        final int[] copyFrom;
        final int[] copyTo;
        final int[] setTags;
        final String[] setValues;

        CompiledRoute(Fix42Properties.Route route, Map<String, List<Integer>> topicKeys) {
            this.route = route;
            this.name = route.name();
            this.mode = route.mode();
            this.selectedTags = route.selectedTags();
            this.topics = resolve(route.topics(), topicKeys);
            this.projectedTopics = resolve(route.projectedTopics(), topicKeys);

            Fix42Properties.Projection projection = route.projection();
            if (projection == null) {
                projectionTags = new int[0];
                copyFrom = new int[0];
                copyTo = new int[0];
                setTags = new int[0];
                setValues = new String[0];
            } else {
                projectionTags = toArray(projection.tags());
                copyFrom = toArray(projection.copyTags().keySet());
                copyTo = toArray(projection.copyTags().values());
                setTags = toArray(projection.setTags().keySet());
                setValues = projection.setTags().values().toArray(new String[0]);
            }
        }

        private static ResolvedTopic[] resolve(List<String> patterns,
                                               Map<String, List<Integer>> topicKeys) {
            ResolvedTopic[] resolved = new ResolvedTopic[patterns.size()];
            for (int i = 0; i < resolved.length; i++) {
                resolved[i] = new ResolvedTopic(patterns.get(i), topicKeys);
            }
            return resolved;
        }

        private static int[] toArray(java.util.Collection<Integer> tags) {
            int[] array = new int[tags.size()];
            int i = 0;
            for (Integer tag : tags) {
                array[i++] = tag;
            }
            return array;
        }
    }

    /** A topic pattern resolved for every scope, with each resolved topic's key tags. */
    private static final class ResolvedTopic {

        final String[] names = new String[SCOPES.length];
        final int[][] keys = new int[SCOPES.length][];

        ResolvedTopic(String pattern, Map<String, List<Integer>> topicKeys) {
            for (OrderScope scope : SCOPES) {
                String name = pattern.replace(Fix42Properties.SCOPE_PLACEHOLDER, scope.token());
                names[scope.ordinal()] = name;
                keys[scope.ordinal()] = CompiledRoute.toArray(topicKeys.getOrDefault(name, List.of()));
            }
        }
    }
}
