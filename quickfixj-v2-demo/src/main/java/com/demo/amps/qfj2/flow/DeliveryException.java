package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.engine.FixMessages;
import quickfix.Message;

/** A destination could not take the message; names which one. */
public final class DeliveryException extends RuntimeException {

    private final String destination;

    public DeliveryException(String destination, Message message, Throwable cause) {
        super("destination '" + destination + "' failed for 35=" + FixMessages.msgType(message) + ": " + cause,
                cause);
        this.destination = destination;
    }

    public String destination() {
        return destination;
    }
}
