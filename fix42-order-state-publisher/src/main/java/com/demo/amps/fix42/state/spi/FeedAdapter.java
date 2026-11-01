package com.demo.amps.fix42.state.spi;

/**
 * The source of the FIX feed: the mock venue in the demo, a QuickFIX/J
 * session in life (docs/02 §2). One thread, session order, and a message is
 * acknowledged to the session only after {@link MessageSink#onMessage}
 * returns -- which is what makes the publish store, not the socket, the point
 * of no return (docs/02 §7).
 */
public interface FeedAdapter {

    /** Delivers messages to {@code sink} until the feed ends. */
    void run(MessageSink sink) throws Exception;
}
