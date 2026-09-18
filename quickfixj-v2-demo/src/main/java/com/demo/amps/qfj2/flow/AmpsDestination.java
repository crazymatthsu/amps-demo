package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.amps.AmpsPublisher;
import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.engine.FixMessages;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;

/**
 * A full {@code publish} of the raw FIX message -- header, body, trailer,
 * SOH-separated, exactly as QuickFIX/J renders it -- to a fix-typed topic.
 *
 * <p>With {@code flushEach} the publish is followed by {@code publishFlush},
 * so returning from {@link #deliver} means AMPS has the message; that is
 * what turns the FIX acknowledgement into a delivery guarantee. Without it
 * the publish is buffered and a failure surfaces later, on some other
 * message's flush or on disconnect.
 *
 * <p>The publisher reopens its connection after a failure, so a delivery that
 * failed because AMPS restarted is retried -- by the counterparty's resend --
 * on a fresh connection.
 */
public final class AmpsDestination implements Destination {

    private static final Logger log = LoggerFactory.getLogger(AmpsDestination.class);

    private final String name;
    private final AmpsPublisher publisher;
    private final String topic;
    private final Set<String> msgTypes;
    private final boolean flushEach;

    public AmpsDestination(String name, AmpsPublisher publisher, String topic, Set<String> msgTypes,
                           boolean flushEach) {
        this.name = name;
        this.publisher = publisher;
        this.topic = topic;
        this.msgTypes = Set.copyOf(msgTypes);
        this.flushEach = flushEach;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean accepts(String msgType) {
        return msgTypes.isEmpty() || msgTypes.contains(msgType);
    }

    @Override
    public void deliver(Message message, FixContext context) throws Exception {
        publisher.publish(topic, message.toString(), flushEach);
        if (log.isInfoEnabled()) {
            log.info("publish {} [{}] {}", topic, name, FixMessages.printable(message));
        }
    }

    public String topic() {
        return topic;
    }

    @Override
    public String toString() {
        return "amps:" + name + " -> " + topic + (msgTypes.isEmpty() ? "" : " for 35 in " + msgTypes);
    }
}
