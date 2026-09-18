package com.demo.amps.qfj2.engine;

import org.springframework.context.ApplicationEvent;
import quickfix.SessionID;

/** Logon and logout, as Spring application events. */
public final class FixSessionEvent extends ApplicationEvent {

    public enum Kind {
        LOGON, LOGOUT
    }

    private final SessionID sessionId;
    private final Kind kind;

    public FixSessionEvent(Object source, SessionID sessionId, Kind kind) {
        super(source);
        this.sessionId = sessionId;
        this.kind = kind;
    }

    public SessionID sessionId() {
        return sessionId;
    }

    public Kind kind() {
        return kind;
    }
}
