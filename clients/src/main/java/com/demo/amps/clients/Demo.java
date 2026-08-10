package com.demo.amps.clients;

/** One runnable feature demonstration. */
public interface Demo {

    /** Command-line name, e.g. {@code sow-query}. */
    String name();

    /** One line for the {@code list} output. */
    String summary();

    /** What the demo is trying to show; printed before it runs. */
    String explanation();

    void run(Args args) throws Exception;
}
