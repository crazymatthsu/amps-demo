package com.demo.amps.fix42.state.publish;

import com.demo.amps.fix42.state.config.Mode;
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
 *
 * <p>A route that lists {@code tags} (or {@code changeable-tags}) sends only
 * those, in rule order, plus the trailer, as a {@code delta_publish} --
 * {@code fix42-publisher}'s DELTA route (docs/07 §5). The key check above still reads the
 * message: the startup check has proven every topic's key is among the
 * selected tags. The catch-all copy of a message that lacked a key is always
 * whole: it is there to be diagnosed.
 *
 * <p>Routes by topic name only: the names are what the {@link PublishSink} is
 * handed, and a sink other than AMPS maps them to whatever it writes.
 */
public final class AuditRouter {

    private final RoutingOptions options;
    private final Mode mode;
    private final String catchAllTopic;
    private final int keyTag;

    public AuditRouter(RoutingOptions options) {
        this.options = options;
        this.mode = options.mode();
        this.keyTag = options.keyTag();
        Route last = options.routes().getLast();
        this.catchAllTopic = last.catchAll() && !last.topics().isEmpty() ? last.topics().getFirst() : null;
    }

    /**
     * One planned pass-through publish.
     *
     * @param delta whether it goes as a {@code delta_publish}: a route's
     *              selection merges into the topic's record, as
     *              fix42-publisher's DELTA routes do; the whole message is
     *              a {@code publish}
     */
    public record Publish(String topic, byte[] payload, int length, boolean delta) {

        public Publish(String topic, byte[] payload, int length) {
            this(topic, payload, length, false);
        }

        public String printable() {
            return new String(payload, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\u0001', '|');
        }
    }

    /** What {@link #plan} decided for one message. */
    public record Plan(Route route, List<Publish> publishes, boolean keyTagConflict, boolean unrouted) {
    }

    /**
     * The route {@code message} takes, on its 35, 150 and 20; first match wins.
     *
     * @throws UnroutedMessageException in strict mode, when only the catch-all
     *         (or nothing) would take it
     */
    public Route match(FixFrame message) {
        for (Route route : options.routes()) {
            if (route.catchAll()) {
                break;
            }
            if (route.matches(message)) {
                return route;
            }
        }
        return unmatched(describe(message));
    }

    /**
     * The route a message of type {@code msgType} takes, on tag 35 alone:
     * the first route for the type, whatever 150 or 20 it would ask for.
     *
     * @throws UnroutedMessageException in strict mode, when only the catch-all
     *         (or nothing) would take it
     */
    public Route match(String msgType) {
        for (Route route : options.routes()) {
            if (route.catchAll()) {
                break;
            }
            if (route.matches(msgType)) {
                return route;
            }
        }
        return unmatched("35=" + msgType);
    }

    private Route unmatched(String what) {
        if (mode == Mode.STRICT) {
            throw new UnroutedMessageException("no route for " + what + " (strict mode)");
        }
        Route last = options.routes().getLast();
        if (!last.catchAll()) {
            throw new UnroutedMessageException("no route for " + what + " and no catch-all route");
        }
        return last;
    }

    private static String describe(FixFrame message) {
        String execType = message.value(FixTags.EXEC_TYPE);
        String execTransType = message.value(FixTags.EXEC_TRANS_TYPE);
        return "35=" + message.value(FixTags.MSG_TYPE)
                + (execType.isEmpty() ? "" : " 150=" + execType)
                + (execTransType.isEmpty() ? "" : " 20=" + execTransType);
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
                            + topic + " " + options.keysOf(topic) + " (strict mode): " + message.printable());
                }
                unrouted = true;
                continue;
            }
            if (skip == Skip.GUARDED) {
                continue;
            }
            publishes.add(route.selects() ? selected(topic, message, route, trailer) : whole(topic, message, trailer));
        }
        if (unrouted && !route.catchAll() && catchAllTopic != null) {
            publishes.add(whole(catchAllTopic, message, trailer));
        }
        return new Plan(route, publishes, conflict, unrouted);
    }

    /** {@code fix42/malformed}'s payload: the bytes base64'd in 12032, the reason in 12033. */
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
        for (int tag : options.keysOf(topic)) {
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

    /**
     * The route's selected tags the message carries, in rule order, then the
     * trailer: a delta, so a topic keyed by OrderID keeps the fields an
     * earlier report wrote and this one does not carry.
     */
    private Publish selected(String topic, FixFrame message, Route route, String key) {
        FixWriter out = new FixWriter(Math.max(64, message.length() + 32));
        for (int tag : route.selectedTags()) {
            int index = message.indexOf(tag);
            if (index >= 0 && message.valueLengthAt(index) > 0 && !out.contains(tag)) {
                message.copyFieldAt(index, out);
            }
        }
        if (key != null) {
            out.field(keyTag, key);
        }
        return new Publish(topic, java.util.Arrays.copyOf(out.buffer(), out.size()), out.size(), true);
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
