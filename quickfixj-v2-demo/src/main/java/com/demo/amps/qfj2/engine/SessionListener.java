package com.demo.amps.qfj2.engine;

import quickfix.Message;
import quickfix.SessionID;

/**
 * Session-level events, for whoever wants them: the mock feed (starts
 * sending on logon), and tests (which admin messages crossed the wire).
 */
public interface SessionListener {

    default void onLogon(SessionID sessionId) {
    }

    default void onLogout(SessionID sessionId) {
    }

    /** An admin message (logon, heartbeat, resend request ...) in either direction. */
    default void onAdmin(Message message, SessionID sessionId, Direction direction) {
    }
}
