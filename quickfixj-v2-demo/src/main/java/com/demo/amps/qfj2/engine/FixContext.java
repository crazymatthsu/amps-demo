package com.demo.amps.qfj2.engine;

import java.time.Instant;
import quickfix.SessionID;

/**
 * What the rules and destinations know about a message besides the message:
 * which session it crossed, which way, and when the engine saw it.
 */
public record FixContext(SessionID sessionId, Direction direction, Instant receivedAt) {
}
