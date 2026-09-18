package com.demo.amps.qfj2.engine;

/** Which way a message crossed the FIX session. */
public enum Direction {
    /** Received from the counterparty ({@code fromApp}). */
    INBOUND,
    /** Sent to the counterparty ({@code toApp}). */
    OUTBOUND
}
