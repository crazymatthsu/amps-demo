package com.demo.amps.fix42.publish;

import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixFrame;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.fix.FixWriter;
import com.demo.amps.fix42.mock.OrderScope;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
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
 * <p>Two entry points share one implementation. {@link #plan(FixFrame, PublishPlan)}
 * is the hot path: it reads the message through a {@link FixFrame} over the
 * raw bytes, writes each payload into the caller's {@link PublishPlan}, and
 * allocates nothing. {@link #plan(FixMessage)} is the readable path, for tests
 * and for callers that want to see what went out: it renders the message, runs
 * the hot path, and materializes the result as {@link PublishInstruction}s.
 * Everything the rulebook determines -- which tags a route selects, its topic
 * names and their key tags per scope, the projection's copy and literal
 * tables, the literals pre-encoded as bytes -- is worked out once, here in the
 * constructor.
 */
@Component
public class PublishPlanner {

    private static final Logger log = LoggerFactory.getLogger(PublishPlanner.class);

    private static final OrderScope[] SCOPES = OrderScope.values();

    private final CompiledRoute[] routes;
    private final int planCapacity;
    private final ThreadLocal<Facade> facade;

    public PublishPlanner(Fix42Properties properties) {
        Map<String, List<Integer>> topicKeys =
                properties.topicKeys() == null ? Map.of() : properties.topicKeys();
        List<Fix42Properties.Route> declared = properties.routes();
        routes = new CompiledRoute[declared.size()];
        int most = 1;
        for (int i = 0; i < routes.length; i++) {
            routes[i] = new CompiledRoute(declared.get(i), topicKeys);
            most = Math.max(most, routes[i].topics.length + routes[i].projectedTopics.length);
        }
        int capacity = most;
        planCapacity = capacity;
        facade = ThreadLocal.withInitial(() -> new Facade(new PublishPlan(capacity)));
    }

    /**
     * A plan with room for every publish any route here can produce, for
     * {@link #plan(FixFrame, PublishPlan)}. One per publishing thread.
     */
    public PublishPlan newPlan() {
        return new PublishPlan(planCapacity);
    }

    /**
     * Plans the publishes for one message into {@code plan}, allocating nothing.
     *
     * <p>Delta payloads are written into the plan's writers straight from the
     * frame's bytes; a whole-message payload is the frame's own buffer. The
     * plan's entries stay valid until the next call with that plan, or until
     * {@code message} is rewrapped.
     *
     * @throws IllegalStateException when no route matches. Silently dropping a
     *         message would leave a gap in a SOW that looks like a bug in AMPS;
     *         failing here names the message type that has no rule.
     */
    public void plan(FixFrame message, PublishPlan plan) {
        plan.clear();
        CompiledRoute route = match(message);
        if (route == null) {
            throw noRoute(message);
        }
        OrderScope scope = scopeOf(message);

        if (route.mode == PublishMode.FULL) {
            addPublishes(plan, route, route.topics, scope, message, null,
                    message.buffer(), message.offset(), message.length());
        } else {
            FixWriter selected = plan.selectedPayload();
            for (int tag : route.selectedTags) {
                message.copyField(tag, selected);
            }
            addPublishes(plan, route, route.topics, scope, message, selected,
                    selected.buffer(), 0, selected.size());
        }

        if (route.projectedTopics.length > 0) {
            FixWriter projected = plan.projectedPayload();
            project(route, message, projected);
            addPublishes(plan, route, route.projectedTopics, scope, message, projected,
                    projected.buffer(), 0, projected.size());
        }
    }

    /**
     * Plans the publishes for one message and returns them as instructions.
     *
     * <p>The readable path. It renders the message, plans it through
     * {@link #plan(FixFrame, PublishPlan)} and parses each payload back into a
     * {@link FixMessage}, so it exercises exactly the code the hot path runs,
     * at the cost of the allocations the hot path avoids.
     *
     * @throws IllegalStateException when no route matches; see {@link #plan(FixFrame, PublishPlan)}
     */
    public List<PublishInstruction> plan(FixMessage message) {
        Facade scratch = facade.get();
        scratch.source.clear();
        message.writeTo(scratch.source);
        plan(scratch.frame.wrap(scratch.source), scratch.plan);
        return materialize(scratch.plan, message, scratch.source.buffer());
    }

    /**
     * The plan's entries as instructions; allocates. A payload the hot path
     * wrote -- a selection or a projection -- is parsed back from its bytes.
     * A payload that is the message itself (its bytes are {@code sourceBytes},
     * the rendering of {@code source}) is {@code source}, unparsed.
     */
    static List<PublishInstruction> materialize(PublishPlan plan, FixMessage source,
                                                byte[] sourceBytes) {
        PublishInstruction[] instructions = new PublishInstruction[plan.size()];
        for (int i = 0; i < instructions.length; i++) {
            PublishPlan.Publish publish = plan.get(i);
            FixMessage payload = publish.payload() == sourceBytes
                    ? source
                    : FixMessage.parse(publish.payload(), publish.payloadOffset(),
                            publish.payloadLength());
            instructions[i] = new PublishInstruction(publish.topic(), publish.mode(), payload,
                    publish.routeName());
        }
        return List.of(instructions);
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

    /** See {@link #scopeOf(FixMessage)}. */
    public OrderScope scopeOf(FixFrame message) {
        return message.has(FixTags.PARENT_ORDER_ID) ? OrderScope.CHILD : OrderScope.PARENT;
    }

    private CompiledRoute match(FixFrame message) {
        for (CompiledRoute candidate : routes) {
            if (candidate.matches(message)) {
                return candidate;
            }
        }
        return null;
    }

    private static IllegalStateException noRoute(FixFrame message) {
        String msgType = message.value(FixTags.MSG_TYPE);
        String execType = message.value(FixTags.EXEC_TYPE);
        String execTransType = message.value(FixTags.EXEC_TRANS_TYPE);
        return new IllegalStateException(
                "no fix42 route matches 35=" + msgType
                        + (execType.isEmpty() ? "" : " with 150=" + execType)
                        + (execTransType.isEmpty() ? "" : " and 20=" + execTransType)
                        + ": " + message.printable()
                        + "\nAdd a route for it in application.yml, or a catch-all for "
                        + "this message type, so nothing is dropped unnoticed.");
    }

    /**
     * Adds one publish per topic, unless the payload lacks the topic's key.
     *
     * <p>{@code Fix42Properties.validate()} already proved the route's tag list
     * <i>can</i> carry the key; this checks the actual payload does. The two
     * differ for optional fields -- a rule may legitimately select tag 41,
     * which the first message of a chain does not have.
     *
     * @param written the writer holding the payload, or null when the payload
     *                is the whole message
     */
    private static void addPublishes(PublishPlan plan, CompiledRoute route, ResolvedTopic[] topics,
                                     OrderScope scope, FixFrame source, FixWriter written,
                                     byte[] payload, int payloadOffset, int payloadLength) {
        int s = scope.ordinal();
        for (ResolvedTopic topic : topics) {
            int missing = written == null
                    ? missingKey(topic.keys[s], source)
                    : missingKey(topic.keys[s], written);
            if (missing >= 0) {
                log.error(
                        "route {} would publish to {} without its key field (tag {}); "
                                + "AMPS would reject it, so this message is not sent: {}",
                        route.name, topic.names[s], missing, source.printable());
            } else {
                plan.add().set(topic.names[s], topic.bytes[s], route.mode, route.name,
                        payload, payloadOffset, payloadLength);
            }
        }
    }

    private static int missingKey(int[] keys, FixFrame payload) {
        for (int key : keys) {
            if (!payload.has(key)) {
                return key;
            }
        }
        return -1;
    }

    private static int missingKey(int[] keys, FixWriter payload) {
        for (int key : keys) {
            if (!payload.contains(key)) {
                return key;
            }
        }
        return -1;
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
     * <p>Precedence: a literal beats a copy, a copy beats the verbatim field,
     * so a route can select tag 38 and still stamp {@code 9010=0} over any
     * copy of it. Each tag is written once, at the first position the rules
     * give it: 35 first, then the verbatim tags in rule order, then copies and
     * literals whose tags are new.
     */
    private static void project(CompiledRoute route, FixFrame source, FixWriter out) {
        if (source.has(FixTags.MSG_TYPE)) {
            emit(route, source, out, FixTags.MSG_TYPE);
        }
        if (route.projectionTags.length > 0) {
            for (int tag : route.projectionTags) {
                emit(route, source, out, tag);
            }
        } else if (route.mode == PublishMode.DELTA) {
            for (int tag : route.selectedTags) {
                emit(route, source, out, tag);
            }
        } else {
            for (int i = 0, n = source.count(); i < n; i++) {
                emit(route, source, out, source.tagAt(i));
            }
        }
        for (int tag : route.copyTo) {
            emit(route, source, out, tag);
        }
        for (int tag : route.setTags) {
            emit(route, source, out, tag);
        }
    }

    /** Writes {@code tag} once, with the value the projection rules give it. */
    private static void emit(CompiledRoute route, FixFrame source, FixWriter out, int tag) {
        if (out.contains(tag)) {
            return;
        }
        int literal = indexOf(route.setTags, tag);
        if (literal >= 0) {
            byte[] encoded = route.setTagBytes[literal];
            if (encoded != null) {
                out.raw(tag, encoded);
            }
            return;
        }
        for (int i = route.copyTo.length - 1; i >= 0; i--) {
            if (route.copyTo[i] == tag && source.copyFieldAs(route.copyFrom[i], tag, out)) {
                return;
            }
        }
        source.copyField(tag, out);
    }

    private static int indexOf(int[] tags, int tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == tag) {
                return i;
            }
        }
        return -1;
    }

    private static int[] toArray(Collection<Integer> tags) {
        int[] array = new int[tags.size()];
        int i = 0;
        for (Integer tag : tags) {
            array[i++] = tag;
        }
        return array;
    }

    /** Per-thread scratch for {@link #plan(FixMessage)}. */
    private static final class Facade {

        final FixWriter source = new FixWriter(512);
        final FixFrame frame = new FixFrame();
        final PublishPlan plan;

        Facade(PublishPlan plan) {
            this.plan = plan;
        }
    }

    /** A route with everything the rulebook determines worked out once. */
    private static final class CompiledRoute {

        final String name;
        final PublishMode mode;
        final String[] msgTypes;
        final String[] execTypes;
        final String[] execTransTypes;
        /** Identity tags then changeable tags, de-duplicated, in rule order. */
        final int[] selectedTags;
        final ResolvedTopic[] topics;
        final ResolvedTopic[] projectedTopics;
        /** The projection's verbatim tags; empty means "the payload's own tags". */
        final int[] projectionTags;
        final int[] copyFrom;
        final int[] copyTo;
        final int[] setTags;
        /** Each literal as {@code tag=value<SOH>}; null for an empty value, which removes the tag. */
        final byte[][] setTagBytes;

        CompiledRoute(Fix42Properties.Route route, Map<String, List<Integer>> topicKeys) {
            name = route.name();
            mode = route.mode();
            msgTypes = route.msgTypes().toArray(new String[0]);
            execTypes = route.execTypes().toArray(new String[0]);
            execTransTypes = route.execTransTypes().toArray(new String[0]);
            selectedTags = toArray(route.selectedTags());
            topics = resolve(route.topics(), topicKeys);
            projectedTopics = resolve(route.projectedTopics(), topicKeys);

            Fix42Properties.Projection projection = route.projection();
            if (projection == null) {
                projectionTags = new int[0];
                copyFrom = new int[0];
                copyTo = new int[0];
                setTags = new int[0];
                setTagBytes = new byte[0][];
            } else {
                projectionTags = toArray(projection.tags());
                copyFrom = toArray(projection.copyTags().keySet());
                copyTo = toArray(projection.copyTags().values());
                setTags = toArray(projection.setTags().keySet());
                setTagBytes = new byte[setTags.length][];
                int i = 0;
                for (Map.Entry<Integer, String> literal : projection.setTags().entrySet()) {
                    String value = literal.getValue();
                    setTagBytes[i++] = value == null || value.isEmpty()
                            ? null
                            : FixWriter.encodeField(literal.getKey(), value);
                }
            }
        }

        /** The same test as {@code Route.matches}, read off the frame without a String. */
        boolean matches(FixFrame message) {
            return matchesAny(message, FixTags.MSG_TYPE, msgTypes)
                    && (execTypes.length == 0 || matchesAny(message, FixTags.EXEC_TYPE, execTypes))
                    && (execTransTypes.length == 0
                            || matchesAny(message, FixTags.EXEC_TRANS_TYPE, execTransTypes));
        }

        /** An absent tag reads as {@code ""}, as {@code FixMessage.value} renders it. */
        private static boolean matchesAny(FixFrame message, int tag, String[] options) {
            int index = message.indexOf(tag);
            for (String option : options) {
                if (index < 0 ? option.isEmpty() : message.valueAtEquals(index, option)) {
                    return true;
                }
            }
            return false;
        }

        private static ResolvedTopic[] resolve(List<String> patterns,
                                               Map<String, List<Integer>> topicKeys) {
            ResolvedTopic[] resolved = new ResolvedTopic[patterns.size()];
            for (int i = 0; i < resolved.length; i++) {
                resolved[i] = new ResolvedTopic(patterns.get(i), topicKeys);
            }
            return resolved;
        }
    }

    /** A topic pattern resolved for every scope, with each resolved topic's key tags. */
    private static final class ResolvedTopic {

        final String[] names = new String[SCOPES.length];
        final byte[][] bytes = new byte[SCOPES.length][];
        final int[][] keys = new int[SCOPES.length][];

        ResolvedTopic(String pattern, Map<String, List<Integer>> topicKeys) {
            for (OrderScope scope : SCOPES) {
                String name = pattern.replace(Fix42Properties.SCOPE_PLACEHOLDER, scope.token());
                names[scope.ordinal()] = name;
                bytes[scope.ordinal()] = name.getBytes(StandardCharsets.ISO_8859_1);
                keys[scope.ordinal()] = toArray(topicKeys.getOrDefault(name, List.of()));
            }
        }
    }
}
