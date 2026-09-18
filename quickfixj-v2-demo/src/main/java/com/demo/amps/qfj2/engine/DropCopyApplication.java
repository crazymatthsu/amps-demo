package com.demo.amps.qfj2.engine;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;

/**
 * The QuickFIX/J {@link Application}: the point where a FIX message leaves
 * the engine and enters the Spring Integration flow.
 *
 * <p>{@code fromApp} wraps the message in a Spring message carrying the
 * session headers and sends it on the inbound channel. On a
 * {@code DirectChannel} that runs the rules and destinations right here, on
 * the session thread, and an exception from any of them comes back out of
 * {@code fromApp} -- which is the behaviour we want: QuickFIX/J then does not
 * increment the inbound sequence number, the counterparty's next message is
 * seen as a gap, a resend request goes out, and the message is delivered
 * again. A destination that cannot deliver therefore never loses a message;
 * it delays it.
 *
 * <p>With {@code disconnectOnFailure} (the default) the engine also drops the
 * session on a failed delivery. Otherwise a counterparty that keeps sending
 * while, say, AMPS is down draws one resend request per message -- every one
 * of them a gap -- and the engine's own sequence number climbs with each,
 * beyond what the (unreachable) checkpoint holds. Disconnecting turns that
 * into one reconnect per {@code ReconnectInterval}, with one resend request
 * for the whole gap once delivery works again.
 *
 * <p>Admin messages are not routed; they go to the {@link SessionListener}s.
 */
public final class DropCopyApplication implements Application {

    private static final Logger log = LoggerFactory.getLogger(DropCopyApplication.class);

    private final MessageChannel inbound;
    private final boolean includeOutbound;
    private final boolean disconnectOnFailure;
    private final List<SessionListener> listeners;
    private final AtomicLong inboundCount = new AtomicLong();
    private final AtomicLong outboundCount = new AtomicLong();

    /**
     * @param inbound             where application messages go
     * @param includeOutbound     also route what this engine sends ({@code toApp})
     * @param disconnectOnFailure drop the session when an inbound delivery fails
     * @param listeners           told about logon, logout and admin traffic
     */
    public DropCopyApplication(MessageChannel inbound, boolean includeOutbound, boolean disconnectOnFailure,
                               List<SessionListener> listeners) {
        this.inbound = inbound;
        this.includeOutbound = includeOutbound;
        this.disconnectOnFailure = disconnectOnFailure;
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public void onCreate(SessionID sessionId) {
        log.info("session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        log.info("logged on: {}", sessionId);
        listeners.forEach(listener -> listener.onLogon(sessionId));
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.info("logged out: {}", sessionId);
        listeners.forEach(listener -> listener.onLogout(sessionId));
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        listeners.forEach(listener -> listener.onAdmin(message, sessionId, Direction.OUTBOUND));
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
        listeners.forEach(listener -> listener.onAdmin(message, sessionId, Direction.INBOUND));
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        if (includeOutbound) {
            route(message, sessionId, Direction.OUTBOUND);
            outboundCount.incrementAndGet();
        }
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) {
        route(message, sessionId, Direction.INBOUND);
        inboundCount.incrementAndGet();
    }

    private void route(Message message, SessionID sessionId, Direction direction) {
        org.springframework.messaging.Message<Message> envelope = MessageBuilder.withPayload(message)
                .setHeader(FixHeaders.SESSION_ID, sessionId)
                .setHeader(FixHeaders.MSG_TYPE, FixMessages.msgType(message))
                .setHeader(FixHeaders.DIRECTION, direction)
                .setHeader(FixHeaders.RECEIVED_AT, Instant.now())
                .build();
        try {
            if (!inbound.send(envelope)) {
                throw new IllegalStateException("the inbound channel refused a " + direction + " 35="
                        + FixMessages.msgType(message) + " from " + sessionId);
            }
        } catch (RuntimeException e) {
            if (disconnectOnFailure && direction == Direction.INBOUND) {
                disconnect(sessionId, message, e);
            }
            throw e;
        }
    }

    private void disconnect(SessionID sessionId, Message message, RuntimeException cause) {
        Session session = Session.lookupSession(sessionId);
        if (session == null) {
            return;
        }
        log.error("delivery of 35={} on {} failed ({}); disconnecting so the counterparty holds its "
                + "messages until this engine reconnects and requests the gap",
                FixMessages.msgType(message), sessionId, cause.getMessage());
        try {
            session.disconnect("drop-copy delivery failed: " + cause.getMessage(), true);
        } catch (IOException e) {
            log.warn("disconnecting {} failed: {}", sessionId, e.toString());
        }
    }

    /** Application messages received and routed. */
    public long inboundCount() {
        return inboundCount.get();
    }

    /** Application messages sent and routed (only when include-outbound is on). */
    public long outboundCount() {
        return outboundCount.get();
    }
}
