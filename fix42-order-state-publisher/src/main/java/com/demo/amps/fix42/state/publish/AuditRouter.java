package com.demo.amps.fix42.state.publish;

import com.demo.amps.fix42.state.config.Mode;
import com.demo.amps.fix42.state.config.OrderStateProperties;
import com.demo.amps.fix42.state.config.OrderStateProperties.Route;
import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.FixWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Which pass-through topics a message goes to, and what it carries there
 * (docs/02 §5): the message <b>whole</b>, as received, plus tag 13011 as a
 * trailer when it resolved to a chain -- after any tag 10 it carries, so a
 * framed message's BodyLength and CheckSum stay valid for the bytes they
 * cover. One {@code sow} with {@code /13011 = '<key>'} per audit topic is then
 * the order's whole history.
 *
 * <p>The stateless planner's per-payload key check survives, <b>per topic</b>
 * (R21, S10): a message lacking one topic's key tag -- a {@code 20=3} with no
 * 17 -- skips that topic, still goes to the topics whose key it carries, and
 * goes whole to {@code fix42/unrouted} instead; it is still applied to the
 * blotter. A route's {@code key-guard} does the same for sentinel values
 * ({@code 37=NONE}, {@code 17=0}), without the catch-all copy: a sentinel is a
 * value the venue meant. In strict mode a missing key, like an unrouted type,
 * raises.
 */
public final class AuditRouter {

    private final OrderStateProperties properties;
    private final Mode mode;
    private final String catchAllTopic;
    private final int keyTag;

    public AuditRouter(OrderStateProperties properties) {
        this.properties = properties;
        this.mode = properties.mode();
        this.keyTag = properties.audit().keyTag();
        Route last = properties.routes().getLast();
        this.catchAllTopic = last.catchAll() && !last.topics().isEmpty() ? last.topics().getFirst() : null;
    }

    /** One planned pass-through publish. */
    public record Publish(String topic, byte[] payload, int length) {
        public String printable() {
            return new String(payload, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\u0001', '|');
        }
    }

    /** What {@link #plan} decided for one message. */
    public record Plan(Route route, List<Publish> publishes, boolean keyTagConflict, boolean unrouted) {
    }

    /**
     * The route {@code msgType} takes, first match wins.
     *
     * @throws UnroutedMessageException in strict mode, when only the catch-all
     *         (or nothing) would take it
     */
    public Route match(String msgType) {
        for (Route route : properties.routes()) {
            if (route.catchAll()) {
                break;
            }
            if (route.matches(msgType)) {
                return route;
            }
        }
        if (mode == Mode.STRICT) {
            throw new UnroutedMessageException("no route for 35=" + msgType + " (strict mode)");
        }
        Route last = properties.routes().getLast();
        if (!last.catchAll()) {
            throw new UnroutedMessageException("no route for 35=" + msgType + " and no catch-all route");
        }
        return last;
    }

    /**
     * The pass-through publishes of {@code message} on {@code route}, with
     * {@code key} -- the OrderKey it resolved to, or null -- as the trailer.
     *
     * @throws UnroutedMessageException in strict mode, for a topic whose key the message lacks
     */
    public Plan plan(FixFrame message, Route route, String key) {
        boolean conflict = keyTag != 0 && message.has(keyTag);
        String trailer = conflict || keyTag == 0 ? null : key;
        List<Publish> publishes = new ArrayList<>(route.topics().size() + 1);
        boolean unrouted = route.catchAll();
        for (String topic : route.topics()) {
            Skip skip = check(message, route, topic);
            if (skip == Skip.MISSING_KEY) {
                if (mode == Mode.STRICT) {
                    throw new UnroutedMessageException("35=" + message.value(FixTags.MSG_TYPE) + " lacks the key of "
                            + topic + " " + properties.keysOf(topic) + " (strict mode): " + message.printable());
                }
                unrouted = true;
                continue;
            }
            if (skip == Skip.GUARDED) {
                continue;
            }
            publishes.add(whole(topic, message, trailer));
        }
        if (unrouted && !route.catchAll() && catchAllTopic != null) {
            publishes.add(whole(catchAllTopic, message, trailer));
        }
        return new Plan(route, publishes, conflict, unrouted);
    }

    /** {@code fix42/malformed}'s payload: the bytes base64'd in 9032, the reason in 9033. */
    public static Publish malformed(String topic, byte[] payload, int offset, int length, String reason) {
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(java.util.Arrays.copyOfRange(payload, offset, offset + length));
        FixWriter out = new FixWriter(encoded.length() + reason.length() + 16);
        out.field(FixTags.Record.MALFORMED_BYTES, encoded);
        out.field(FixTags.Record.MALFORMED_REASON, reason.isEmpty() ? "unparseable" : reason);
        return new Publish(topic, java.util.Arrays.copyOf(out.buffer(), out.size()), out.size());
    }

    /** The topic a lenient-mode catch-all publishes to, or null when none is configured. */
    public String catchAllTopic() {
        return catchAllTopic;
    }

    private enum Skip { NONE, MISSING_KEY, GUARDED }

    private Skip check(FixFrame message, Route route, String topic) {
        for (int tag : properties.keysOf(topic)) {
            int index = message.indexOf(tag);
            if (index < 0 || message.valueLengthAt(index) == 0) {
                return Skip.MISSING_KEY;
            }
            if (route.guarded(topic, tag, message.valueAt(index))) {
                return Skip.GUARDED;
            }
        }
        return Skip.NONE;
    }

    private Publish whole(String topic, FixFrame message, String key) {
        int length = message.length();
        boolean terminated = length > 0 && message.buffer()[message.offset() + length - 1] == FixMessage.SOH;
        int trailerLength = key == null ? 0 : Integer.toString(keyTag).length() + key.length() + 2;
        byte[] payload = new byte[length + (terminated ? 0 : 1) + trailerLength];
        System.arraycopy(message.buffer(), message.offset(), payload, 0, length);
        int size = length;
        if (!terminated) {
            payload[size++] = FixMessage.SOH;
        }
        if (key != null) {
            byte[] field = FixWriter.encodeField(keyTag, key);
            System.arraycopy(field, 0, payload, size, field.length);
            size += field.length;
        }
        return new Publish(topic, payload, size);
    }
}
