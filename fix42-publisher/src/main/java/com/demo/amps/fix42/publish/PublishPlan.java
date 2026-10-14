package com.demo.amps.fix42.publish;

import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The publishes one message produces, as a reusable, mutable plan.
 *
 * <p>{@link PublishPlanner#plan(com.demo.amps.fix42.fix.FixFrame, PublishPlan)}
 * fills it without allocating: delta payloads are written into the plan's own
 * writers, a whole-message payload points straight at the frame's buffer, and
 * each {@link Publish} is a pre-allocated entry reset per message. A plan
 * belongs to one thread. Its entries are valid until the next {@code plan}
 * call on it -- or, for a FULL entry, until the frame it was planned from is
 * rewrapped.
 */
public final class PublishPlan {

    /** One planned publish: the topic, the command, and the payload bytes to send. */
    public static final class Publish {

        private String topic;
        private byte[] topicBytes;
        private PublishMode mode;
        private String routeName;
        private byte[] payload;
        private int payloadOffset;
        private int payloadLength;

        private Publish() {
        }

        /** The resolved topic name, with no placeholder left in it. */
        public String topic() {
            return topic;
        }

        /** {@link #topic()} encoded once, for the client's byte-array overloads. */
        public byte[] topicBytes() {
            return topicBytes;
        }

        /** Which AMPS command to issue. */
        public PublishMode mode() {
            return mode;
        }

        /** The rule that produced this, for logs. */
        public String routeName() {
            return routeName;
        }

        /** The array holding the payload; the bytes are {@code [payloadOffset(), payloadOffset() + payloadLength())}. */
        public byte[] payload() {
            return payload;
        }

        public int payloadOffset() {
            return payloadOffset;
        }

        public int payloadLength() {
            return payloadLength;
        }

        /** The payload as text; allocates, for logs and tests. */
        public String payloadString() {
            return new String(payload, payloadOffset, payloadLength, StandardCharsets.ISO_8859_1);
        }

        /** The payload with separators rendered as {@code |}. */
        public String printable() {
            return payloadString().replace((char) FixMessage.SOH, '|');
        }

        @Override
        public String toString() {
            return mode + " -> " + topic + "  " + printable();
        }

        void set(String topic, byte[] topicBytes, PublishMode mode, String routeName,
                 byte[] payload, int payloadOffset, int payloadLength) {
            this.topic = topic;
            this.topicBytes = topicBytes;
            this.mode = mode;
            this.routeName = routeName;
            this.payload = payload;
            this.payloadOffset = payloadOffset;
            this.payloadLength = payloadLength;
        }
    }

    private final FixWriter selected = new FixWriter(512);
    private final FixWriter projected = new FixWriter(512);
    private Publish[] entries;
    private int count;

    /** A plan with room for {@code capacity} publishes before it has to grow; see {@link PublishPlanner#newPlan()}. */
    public PublishPlan(int capacity) {
        entries = new Publish[Math.max(1, capacity)];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new Publish();
        }
    }

    /** The number of publishes planned for the last message. */
    public int size() {
        return count;
    }

    public Publish get(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("publish " + index + " of " + count);
        }
        return entries[index];
    }

    void clear() {
        count = 0;
        selected.clear();
        projected.clear();
    }

    /** The writer holding the route's selected tags. */
    FixWriter selectedPayload() {
        return selected;
    }

    /** The writer holding the projected payload. */
    FixWriter projectedPayload() {
        return projected;
    }

    /** The next entry to fill; grows only when a rulebook produces more publishes than the plan was sized for. */
    Publish add() {
        if (count == entries.length) {
            int previous = entries.length;
            entries = Arrays.copyOf(entries, previous * 2);
            for (int i = previous; i < entries.length; i++) {
                entries[i] = new Publish();
            }
        }
        return entries[count++];
    }
}
