package com.demo.amps.fix42.mock;

import com.demo.amps.fix42.fix.FixMessage;

/**
 * One generated FIX message, with the little context a log line needs.
 *
 * <p>{@code scope} is carried for readability in logs and tests only. The
 * publisher deliberately does not read it: routing decides parent versus child
 * from tag 9000 on the message itself, so it behaves the same on a real feed
 * where nobody hands it an annotation.
 *
 * @param chainId     the order chain this belongs to, e.g. {@code PARENT-AAPL}
 * @param scope       parent or child, for display
 * @param description what this message is, e.g. {@code "partial fill"}
 * @param message     the message
 */
public record FixEvent(String chainId, OrderScope scope, String description, FixMessage message) {

    /** MsgType of the underlying message. */
    public String msgType() {
        return message.msgType();
    }
}
