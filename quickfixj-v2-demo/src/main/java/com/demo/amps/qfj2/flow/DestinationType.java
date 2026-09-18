package com.demo.amps.qfj2.flow;

/** Where a routed message can go. */
public enum DestinationType {
    /** A full publish of the raw FIX message to an AMPS topic. */
    AMPS,
    /** {@code Session.sendToTarget} on another session this engine has. */
    FIX
}
