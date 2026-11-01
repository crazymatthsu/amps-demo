package com.demo.amps.fix42.state.spi;

/** Where a {@link FeedAdapter} delivers each message: the publisher. */
public interface MessageSink {

    /**
     * Applies one message and returns only after every publish for it is in
     * the publish store and the cache is committed. Throws when it is not:
     * the adapter must then not acknowledge the message to its session.
     */
    void onMessage(byte[] payload, int offset, int length) throws Exception;
}
