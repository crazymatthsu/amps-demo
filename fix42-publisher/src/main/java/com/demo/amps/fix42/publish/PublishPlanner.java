package com.demo.amps.fix42.publish;

import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.mock.OrderScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 */
@Component
public class PublishPlanner {

    private static final Logger log = LoggerFactory.getLogger(PublishPlanner.class);

    private final Fix42Properties properties;

    public PublishPlanner(Fix42Properties properties) {
        this.properties = properties;
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

        Fix42Properties.Route route = properties.routes().stream()
                .filter(candidate -> candidate.matches(msgType, execType, execTransType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no fix42 route matches 35=" + msgType
                                + (execType.isEmpty() ? "" : " with 150=" + execType)
                                + (execTransType.isEmpty() ? "" : " and 20=" + execTransType)
                                + ": " + message.printable()
                                + "\nAdd a route for it in application.yml, or a catch-all for "
                                + "this message type, so nothing is dropped unnoticed."));

        FixMessage payload = route.mode() == PublishMode.FULL
                ? message
                : message.select(route.selectedTags());

        OrderScope scope = scopeOf(message);
        List<PublishInstruction> instructions = new ArrayList<>();
        addInstructions(instructions, route, route.topics(), payload, scope, message);

        if (!route.projectedTopics().isEmpty()) {
            FixMessage projected = project(route, message, payload);
            addInstructions(instructions, route, route.projectedTopics(), projected, scope, message);
        }
        return List.copyOf(instructions);
    }

    private void addInstructions(List<PublishInstruction> instructions, Fix42Properties.Route route,
                                 List<String> patterns, FixMessage payload, OrderScope scope,
                                 FixMessage source) {
        for (String pattern : patterns) {
            String topic = pattern.replace(Fix42Properties.SCOPE_PLACEHOLDER, scope.token());
            missingKey(topic, payload).ifPresentOrElse(
                    missing -> log.error(
                            "route {} would publish to {} without its key field (tag {}); "
                                    + "AMPS would reject it, so this message is not sent: {}",
                            route.name(), topic, missing, source.printable()),
                    () -> instructions.add(
                            new PublishInstruction(topic, route.mode(), payload, route.name())));
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
    private FixMessage project(Fix42Properties.Route route, FixMessage source, FixMessage payload) {
        Fix42Properties.Projection projection = route.projection();
        FixMessage base = projection.tags().isEmpty() ? payload : source.select(projection.tags());

        FixMessage.Builder builder = FixMessage.ofType(source.msgType());
        base.asMap().forEach(builder::set);
        projection.copyTags().forEach((from, to) -> source.get(from).ifPresent(
                value -> builder.set(to, value)));
        projection.setTags().forEach(builder::set);
        return builder.build();
    }

    /**
     * Parent or child, from tag 9000 alone.
     *
     * <p>Only affects topics carrying {@code {scope}}. The exec and reject
     * TOPICS are fixed, so scope does not choose those -- but a venue message
     * that resolves a pending request also projects onto the order blotter,
     * and that topic is scoped. So execution reports and cancel rejects on a
     * child chain must carry tag 9000 as well; see {@code OrderChain.execution}
     * for why that is a venue/OMS dependency rather than a free choice.
     */
    public OrderScope scopeOf(FixMessage message) {
        return message.has(FixTags.PARENT_ORDER_ID) ? OrderScope.CHILD : OrderScope.PARENT;
    }

    /**
     * The configured key tag this payload is missing, if any.
     *
     * <p>{@code Fix42Properties.validate()} already proved the route's tag list
     * <i>can</i> carry the key; this checks the actual message did. The two
     * differ for optional fields -- a rule may legitimately select tag 41,
     * which the first message of a chain does not have.
     */
    private Optional<Integer> missingKey(String topic, FixMessage payload) {
        List<Integer> keys = properties.topicKeys().getOrDefault(topic, List.of());
        return keys.stream().filter(key -> !payload.has(key)).findFirst();
    }
}
