package com.demo.amps.fix42.state.publish;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.machine.Projection;
import java.util.List;
import java.util.Map;

/**
 * One route, as the {@link AuditRouter} reads it: plain values, so the router
 * runs without a configuration binder. The shipped application binds
 * {@code OrderStateProperties.Route} from {@code application.yml} and maps it
 * here.
 *
 * <p>The topic names and the key guard are names the sink is handed, nothing
 * more: an AMPS sink publishes to them as topics, any other sink maps them to
 * whatever it writes (docs/07 §3).
 *
 * <p>Matching and tag selection follow {@code fix42-publisher}'s rulebook
 * (docs/07 §5): a route matches on 35 and, when it lists them, on 150 and 20;
 * {@code selectedTags} narrows what its topics receive; its
 * {@code projection} reshapes what the blotter record carries.
 *
 * @param name           for logs and errors
 * @param msgTypes       tag 35 values, or {@code *} for the lenient catch-all
 * @param execTypes      tag 150 values the route takes; empty for any. An
 *                       absent 150 reads as {@code ""}
 * @param execTransTypes tag 20 values the route takes; empty for any. An
 *                       absent 20 reads as {@code ""}
 * @param topics         destinations; the message goes whole -- or only its
 *                       {@code selectedTags} -- plus the key trailer
 * @param selectedTags   the tags {@code topics} receive, in this order; empty
 *                       for the whole message
 * @param keyGuard       per topic, per key tag, the sentinel values that skip
 *                       that topic: {@code 37=NONE} would collapse execs onto
 *                       one record, {@code 17=0} execs_audit; null for none
 * @param applyState     whether the route's messages feed the state machine
 * @param projection     how the blotter record is reshaped for this route's
 *                       messages; null for the machine's default
 */
public record Route(String name,
                    List<String> msgTypes,
                    List<String> execTypes,
                    List<String> execTransTypes,
                    List<String> topics,
                    List<Integer> selectedTags,
                    Map<String, Map<Integer, List<String>>> keyGuard,
                    boolean applyState,
                    Projection projection) {

    /** The catch-all message type of a lenient-mode route. */
    public static final String ANY_TYPE = "*";

    public Route {
        msgTypes = msgTypes == null ? List.of() : msgTypes;
        execTypes = execTypes == null ? List.of() : execTypes;
        execTransTypes = execTransTypes == null ? List.of() : execTransTypes;
        topics = topics == null ? List.of() : topics;
        selectedTags = selectedTags == null ? List.of() : List.copyOf(selectedTags);
    }

    /** A route on tag 35 alone that sends the message whole and keeps the machine's default record. */
    public Route(String name, List<String> msgTypes, List<String> topics,
                 Map<String, Map<Integer, List<String>>> keyGuard, boolean applyState) {
        this(name, msgTypes, List.of(), List.of(), topics, List.of(), keyGuard, applyState, null);
    }

    /** On tag 35 alone: what a route answers when the message is not at hand. */
    public boolean matches(String msgType) {
        return msgTypes.contains(msgType) || msgTypes.contains(ANY_TYPE);
    }

    /** On tags 35, 150 and 20, as {@code fix42-publisher} matches. */
    public boolean matches(FixFrame message) {
        if (catchAll()) {
            return true;
        }
        return matchesAny(message, FixTags.MSG_TYPE, msgTypes)
                && (execTypes.isEmpty() || matchesAny(message, FixTags.EXEC_TYPE, execTypes))
                && (execTransTypes.isEmpty() || matchesAny(message, FixTags.EXEC_TRANS_TYPE, execTransTypes));
    }

    public boolean catchAll() {
        return msgTypes.contains(ANY_TYPE);
    }

    /** Whether the route's topics receive only {@link #selectedTags()}. */
    public boolean selects() {
        return !selectedTags.isEmpty();
    }

    /** Whether {@code value} of {@code tag} is a sentinel that skips {@code topic}. */
    public boolean guarded(String topic, int tag, String value) {
        if (keyGuard == null) {
            return false;
        }
        Map<Integer, List<String>> guards = keyGuard.get(topic);
        return guards != null && guards.getOrDefault(tag, List.of()).contains(value);
    }

    /** An absent tag reads as {@code ""}, as {@code FixFrame.value} renders it. */
    private static boolean matchesAny(FixFrame message, int tag, List<String> options) {
        int index = message.indexOf(tag);
        for (String option : options) {
            if (index < 0 ? option.isEmpty() : message.valueAtEquals(index, option)) {
                return true;
            }
        }
        return false;
    }
}
