package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.engine.FixMessages;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;

/**
 * Forwards the message on another FIX session this engine has.
 *
 * <p>The message is cloned and its header stripped of what the target
 * session sets for itself; {@code Session.sendToTarget} then assigns the
 * next sequence number and stamps the comp ids. A message is never echoed
 * back onto the session it arrived on. If the target is not logged on the
 * send fails, and the failure propagates so the message is redelivered
 * rather than dropped.
 */
public final class FixSessionDestination implements Destination {

    private static final Logger log = LoggerFactory.getLogger(FixSessionDestination.class);

    private final String name;
    private final SessionID target;
    private final Set<String> msgTypes;

    public FixSessionDestination(String name, SessionID target, Set<String> msgTypes) {
        this.name = name;
        this.target = target;
        this.msgTypes = Set.copyOf(msgTypes);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean accepts(String msgType) {
        return msgTypes.isEmpty() || msgTypes.contains(msgType);
    }

    @Override
    public void deliver(Message message, FixContext context) throws Exception {
        if (target.equals(context.sessionId())) {
            log.debug("[{}] not echoing a 35={} back onto {}", name, FixMessages.msgType(message), target);
            return;
        }
        Message copy = FixMessages.forSession(message);
        if (!Session.sendToTarget(copy, target)) {
            throw new IllegalStateException("session " + target + " is not logged on; 35="
                    + FixMessages.msgType(message) + " not forwarded");
        }
        if (log.isInfoEnabled()) {
            log.info("forward {} [{}] {}", target, name, FixMessages.printable(copy));
        }
    }

    public SessionID target() {
        return target;
    }

    @Override
    public String toString() {
        return "fix:" + name + " -> " + target + (msgTypes.isEmpty() ? "" : " for 35 in " + msgTypes);
    }
}
