package com.demo.amps.qfj2.engine;

import java.time.Instant;
import org.springframework.messaging.MessageHeaders;
import quickfix.SessionID;

/**
 * The Spring Integration message headers a FIX message travels with.
 *
 * <p>The payload is the {@code quickfix.Message} itself; these carry the
 * session context alongside it so any handler in the flow can rebuild a
 * {@link FixContext} without the engine having to pass one explicitly.
 */
public final class FixHeaders {

    public static final String SESSION_ID = "fix_sessionId";
    public static final String MSG_TYPE = "fix_msgType";
    public static final String DIRECTION = "fix_direction";
    public static final String RECEIVED_AT = "fix_receivedAt";

    private FixHeaders() {
    }

    public static FixContext context(MessageHeaders headers) {
        SessionID sessionId = headers.get(SESSION_ID, SessionID.class);
        Direction direction = headers.get(DIRECTION, Direction.class);
        Instant receivedAt = headers.get(RECEIVED_AT, Instant.class);
        if (sessionId == null || direction == null || receivedAt == null) {
            throw new IllegalStateException("message lacks the FIX session headers: " + headers);
        }
        return new FixContext(sessionId, direction, receivedAt);
    }
}
