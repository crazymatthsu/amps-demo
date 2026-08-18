package com.demo.amps.utils;

/** A tool refusing or failing for a reason the operator needs, with an exit code to match. */
public final class ToolFailure extends RuntimeException {

    private final int exitCode;

    public ToolFailure(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
