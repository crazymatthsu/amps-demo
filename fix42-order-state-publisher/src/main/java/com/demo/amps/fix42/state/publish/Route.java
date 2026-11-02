package com.demo.amps.fix42.state.publish;

import java.util.List;
import java.util.Map;

/**
 * One pass-through route, as the {@link AuditRouter} reads it: plain values,
 * so the router runs without a configuration binder. The shipped application
 * binds {@code OrderStateProperties.Route} from {@code application.yml} and
 * maps it here.
 *
 * <p>The topic names and the key guard are names the sink is handed, nothing
 * more: an AMPS sink publishes to them as topics, any other sink maps them to
 * whatever it writes (docs/07 §3).
 *
 * @param name       for logs and errors
 * @param msgTypes   tag 35 values, or {@code *} for the lenient catch-all
 * @param topics     destinations; the message goes whole, plus the key trailer
 * @param keyGuard   per topic, per key tag, the sentinel values that skip
 *                   that topic: {@code 37=NONE} would collapse execs onto
 *                   one record, {@code 17=0} execs_audit; null for none
 * @param applyState whether the route's messages feed the state machine
 */
public record Route(String name,
                    List<String> msgTypes,
                    List<String> topics,
                    Map<String, Map<Integer, List<String>>> keyGuard,
                    boolean applyState) {

    /** The catch-all message type of a lenient-mode route. */
    public static final String ANY_TYPE = "*";

    public Route {
        msgTypes = msgTypes == null ? List.of() : msgTypes;
        topics = topics == null ? List.of() : topics;
    }

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
