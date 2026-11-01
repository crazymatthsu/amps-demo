package com.demo.amps.fix42.state.machine;

/** What an in-flight request asks for; the values of tag 15035 besides {@code NONE}. */
public enum PendingAction {
    NEW,
    REPLACE,
    CANCEL
}
