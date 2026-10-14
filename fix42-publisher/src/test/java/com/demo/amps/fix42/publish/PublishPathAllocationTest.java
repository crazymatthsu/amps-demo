package com.demo.amps.fix42.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.crankuptheamps.client.Client;
import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.fix.FixFrame;
import com.demo.amps.fix42.it.Fix42Configurations;
import com.demo.amps.fix42.mock.FixEvent;
import com.demo.amps.fix42.mock.MockFixFlow;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the property the hot path exists for: once warm, planning and sending
 * a message allocates nothing. Measured with the JVM's own per-thread
 * allocation counter over the whole mock flow, so a stray boxing, lambda or
 * String anywhere on the path fails the build rather than showing up later as
 * a GC pause.
 *
 * <p>The bound is one byte per message, not zero: the JVM itself occasionally
 * allocates a few hundred bytes on the running thread (a deoptimization, a
 * thread-local map resize), and a per-message object is at least 16 bytes, so
 * the bound tolerates the former and still fails on the latter.
 */
class PublishPathAllocationTest {

    private static final int WARM_UP_ROUNDS = 2_000;
    private static final int MEASURED_ROUNDS = 1_000;

    @Test
    @DisplayName("planning through a FixFrame allocates nothing once warm")
    void planningAllocatesNothing() {
        com.sun.management.ThreadMXBean memory = hotspotThreadBean();
        PublishPlanner planner = new PublishPlanner(shippedProperties());
        byte[][] wire = shippedFlowAsBytes();
        FixFrame frame = new FixFrame();
        PublishPlan plan = planner.newPlan();

        int publishes = 0;
        for (int round = 0; round < WARM_UP_ROUNDS; round++) {
            publishes = planAll(planner, wire, frame, plan);
        }
        assertThat(publishes).as("the rulebook fans most messages out to several topics")
                .isGreaterThan(wire.length);

        long before = memory.getCurrentThreadAllocatedBytes();
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            planAll(planner, wire, frame, plan);
        }
        long allocated = memory.getCurrentThreadAllocatedBytes() - before;

        assertNothingPerMessage(allocated, "planning", MEASURED_ROUNDS * wire.length);
    }

    @Test
    @DisplayName("sending bytes through the publisher allocates nothing once warm, with the INFO line off")
    void sendingAllocatesNothing() throws Exception {
        com.sun.management.ThreadMXBean memory = hotspotThreadBean();
        Fix42Properties properties = shippedProperties();
        byte[][] wire = shippedFlowAsBytes();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("com.demo.amps.fix42");
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        Client client = new NoopClient();
        try {
            AmpsDeltaPublisher publisher =
                    new AmpsDeltaPublisher(client, new PublishPlanner(properties), properties);

            for (int round = 0; round < WARM_UP_ROUNDS; round++) {
                sendAll(publisher, wire);
            }
            long sentBefore = publisher.fullPublishCount() + publisher.deltaPublishCount();

            long before = memory.getCurrentThreadAllocatedBytes();
            for (int round = 0; round < MEASURED_ROUNDS; round++) {
                sendAll(publisher, wire);
            }
            long allocated = memory.getCurrentThreadAllocatedBytes() - before;

            assertThat(publisher.fullPublishCount() + publisher.deltaPublishCount() - sentBefore)
                    .as("every measured round published")
                    .isGreaterThan((long) MEASURED_ROUNDS * wire.length);
            assertNothingPerMessage(allocated, "sending", MEASURED_ROUNDS * wire.length);
        } finally {
            logger.setLevel(previous);
            client.close();
        }
    }

    private static void assertNothingPerMessage(long allocated, String doing, int messages) {
        assertThat(allocated)
                .as("bytes allocated %s %d messages (%.3f per message)",
                        doing, messages, (double) allocated / messages)
                .isLessThan(messages);
    }

    private static int planAll(PublishPlanner planner, byte[][] wire, FixFrame frame,
                               PublishPlan plan) {
        int publishes = 0;
        for (byte[] message : wire) {
            planner.plan(frame.wrap(message, 0, message.length), plan);
            publishes += plan.size();
        }
        return publishes;
    }

    private static void sendAll(AmpsDeltaPublisher publisher, byte[][] wire) throws Exception {
        for (byte[] message : wire) {
            publisher.send(message, 0, message.length);
        }
    }

    private static Fix42Properties shippedProperties() {
        return Fix42Configurations.shipped("tcp://127.0.0.1:9007/amps/fix");
    }

    /** Every message of the mock flow, as the bytes a feed would hand over. */
    private static byte[][] shippedFlowAsBytes() {
        List<FixEvent> events = MockFixFlow.events();
        byte[][] wire = new byte[events.size()][];
        for (int i = 0; i < wire.length; i++) {
            wire[i] = events.get(i).message().render().getBytes(StandardCharsets.ISO_8859_1);
        }
        return wire;
    }

    private static com.sun.management.ThreadMXBean hotspotThreadBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean,
                "per-thread allocation counting needs HotSpot's thread MXBean");
        com.sun.management.ThreadMXBean memory = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(memory.isThreadAllocatedMemorySupported()
                        && memory.isThreadAllocatedMemoryEnabled(),
                "per-thread allocation counting is not enabled on this JVM");
        return memory;
    }

    /** Accepts publishes and does nothing with them: the test measures the publisher, not a socket. */
    private static final class NoopClient extends Client {

        NoopClient() {
            super("allocation-test");
        }

        @Override
        public long publish(byte[] topic, int topicOffset, int topicLength,
                            byte[] data, int dataOffset, int dataLength) {
            return 0;
        }

        @Override
        public long deltaPublish(byte[] topic, int topicOffset, int topicLength,
                                 byte[] data, int dataOffset, int dataLength) {
            return 0;
        }
    }
}
