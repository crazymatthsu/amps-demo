package com.demo.amps.clients;

import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.DisconnectedException;
import com.crankuptheamps.client.exception.TimedOutException;
import java.util.function.Predicate;

/**
 * Consuming a {@link MessageStream} defensively.
 *
 * <p>A SOW query stream ends by itself when the server finishes the query. A
 * subscription stream does not -- it is live until you stop it -- so every demo
 * that subscribes needs an exit condition. {@code MessageStream.timeout} provides
 * one, but different client builds signal the expiry differently: some return
 * false from {@code hasNext()}, some return null from {@code next()}, and some
 * surface a {@link TimedOutException} wrapped in a runtime exception (the
 * {@link java.util.Iterator} contract has nowhere to put a checked one). This
 * helper accepts all three so the demos read the same either way.
 */
public final class MessageStreams {

    private MessageStreams() {
    }

    /**
     * Feeds messages to {@code handler} until it returns false, the stream ends,
     * or the stream goes idle for {@code idleTimeoutMillis}.
     *
     * @return the number of messages handled
     */
    public static int forEach(MessageStream stream, int idleTimeoutMillis, Predicate<Message> handler) {
        stream.timeout(idleTimeoutMillis);
        int handled = 0;
        try {
            while (stream.hasNext()) {
                Message message = stream.next();
                if (message == null) {
                    break; // idle timeout
                }
                handled++;
                if (!handler.test(message)) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            if (!isExpectedEnd(e)) {
                throw e;
            }
        }
        return handled;
    }

    private static boolean isExpectedEnd(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimedOutException || current instanceof DisconnectedException) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
        }
        return false;
    }
}
