package com.demo.amps.ha.api;

import com.crankuptheamps.client.Message;

/**
 * What a {@link HaBookmarkSubscriber} subscription does with each message.
 *
 * <p>Returning normally means "processed": only then does the subscriber
 * discard the message's bookmark, which is what lets the subscription's
 * resume position move past it -- in the local store, and in the recovery
 * point topic when there is one. Throwing means "not processed": the bookmark
 * is kept, the exception is logged and counted, and the subscription carries
 * on with the next message. The message is not redelivered live; a subscriber
 * restarted from {@link BookmarkStart#MOST_RECENT} replays from the oldest
 * undiscarded message, so it is seen again then. That is at-least-once, and
 * a processor should be prepared to see a message twice.
 *
 * <p>The {@link Message} is the client's and is reused after the call
 * returns: copy what is needed, keep no reference.
 */
@FunctionalInterface
public interface MessageProcessor {

    void process(Message message) throws Exception;
}
