package com.demo.amps.fix42.publish;

import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixMessage;

/**
 * One resolved "send this payload to that topic" decision.
 *
 * <p>Planning is separated from sending so the routing rules can be unit
 * tested without a server: {@link PublishPlanner} turns a message into
 * instructions, and {@link AmpsDeltaPublisher} is the only thing that needs a
 * connection.
 *
 * @param topic     the resolved topic name, with no placeholder left in it
 * @param mode      which AMPS command to issue
 * @param payload   the message as it will go on the wire -- already reduced to
 *                  the route's selected tags when {@code mode} is
 *                  {@link PublishMode#DELTA}
 * @param routeName the rule that produced this, for logs
 */
public record PublishInstruction(String topic, PublishMode mode, FixMessage payload,
                                 String routeName) {

    @Override
    public String toString() {
        return mode + " -> " + topic + "  " + payload.printable();
    }
}
