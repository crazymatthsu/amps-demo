package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.engine.FixContext;
import quickfix.Message;

/**
 * Somewhere an enriched message is delivered.
 *
 * <p>{@link #deliver} returns only once the message is where it is going --
 * an AMPS destination flushes, a FIX destination has handed the message to
 * the session -- and throws otherwise. On the direct channel that exception
 * reaches QuickFIX/J, which leaves the inbound sequence number alone so the
 * counterparty resends. Any Spring bean implementing this is added after the
 * configured destinations.
 */
public interface Destination {

    String name();

    /** Whether this destination takes messages of the given type (tag 35). */
    boolean accepts(String msgType);

    void deliver(Message message, FixContext context) throws Exception;
}
