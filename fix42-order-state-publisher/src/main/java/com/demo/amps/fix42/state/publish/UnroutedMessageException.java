package com.demo.amps.fix42.state.publish;

/** Strict mode's answer to a message no route claims, a topic key it lacks, or bytes that do not parse. */
public final class UnroutedMessageException extends RuntimeException {

    public UnroutedMessageException(String message) {
        super(message);
    }

    public UnroutedMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
