package com.demo.amps.fix42.state.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.demo.amps.fix42.state.config.OrderStateProperties;
import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.hooks.DeskStatusHook;
import com.demo.amps.fix42.state.it.OrderStateConfigurations;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.Outcome;
import com.demo.amps.fix42.state.machine.RecordWriter;
import com.demo.amps.fix42.state.mock.EdgeCaseFlow;
import com.demo.amps.fix42.state.mock.FixEvent;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * docs/05 phase 5: what the publish path allocates per message, measured,
 * stated, and capped.
 *
 * <p>{@code fix42-publisher}'s path allocates nothing once warm (its own
 * {@code PublishPathAllocationTest}). This one cannot, by design:
 * <ul>
 *   <li>the cache keeps the venue's text as {@code String}s (ids, quantities,
 *       times), so a report that changes a field allocates its new value;</li>
 *   <li>a new order is a new cache entry and new index entries;</li>
 *   <li>the pending set and the chain list (12015, 12021) are built as strings
 *       for the record.</li>
 * </ul>
 * So the goal is a number, not zero: bytes per message over the scripted flow
 * and every edge case, which is where the hooks' views, the pending-set and
 * chain-list encoding, and the rare paths all run. It is printed on every run,
 * and the build fails if it grows past the ceiling, which sits about 25% above
 * what was measured.
 *
 * <p>Each round runs the whole flow through a fresh machine. The flow's
 * orders are created afresh each time, so creating cache entries counts, as
 * it does on a real day. Building the machine does not count: the counter is
 * read around the sends only.
 */
class PublishPathAllocationTest {

    private static final int WARM_UP_ROUNDS = 40;
    private static final int MEASURED_ROUNDS = 40;

    /**
     * About 25% above what was measured on 2026-11-01 (10,148 and 8,801
     * bytes; README "What it costs").
     */
    static final long PUBLISHER_CEILING = 12_700;
    static final long MACHINE_CEILING = 11_000;

    @Test
    @DisplayName("bytes per message through onMessage (byte path, desk-status hook, logging at WARN): bounded")
    void publisherPath() throws Exception {
        com.sun.management.ThreadMXBean memory = hotspotThreadBean();
        OrderStateProperties properties = OrderStateConfigurations.shipped("tcp://127.0.0.1:9007/amps/fix", "demo");
        byte[][] wire = flowAsBytes();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("com.demo.amps.fix42.state");
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
            for (int round = 0; round < WARM_UP_ROUNDS; round++) {
                sendAll(publisher(properties), wire, memory);
            }
            long allocated = 0;
            for (int round = 0; round < MEASURED_ROUNDS; round++) {
                allocated += sendAll(publisher(properties), wire, memory);
            }
            report("publisher", allocated, MEASURED_ROUNDS * wire.length, PUBLISHER_CEILING);
        } finally {
            logger.setLevel(previous);
        }
    }

    @Test
    @DisplayName("bytes per message through the machine alone (plan, write the record, commit): bounded")
    void machinePath() {
        com.sun.management.ThreadMXBean memory = hotspotThreadBean();
        OrderStateProperties properties = OrderStateConfigurations.shipped("tcp://127.0.0.1:9007/amps/fix", "demo");
        byte[][] wire = flowAsBytes();
        FixFrame frame = new FixFrame();
        FixWriter out = new FixWriter(1024);
        RecordWriter writer = new RecordWriter(properties.machineOptions());
        for (int round = 0; round < WARM_UP_ROUNDS; round++) {
            applyAll(new OrderStateMachine(properties.machineOptions()), writer, wire, frame, out);
        }
        long allocated = 0;
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            OrderStateMachine machine = new OrderStateMachine(properties.machineOptions());
            long before = memory.getCurrentThreadAllocatedBytes();
            applyAll(machine, writer, wire, frame, out);
            allocated += memory.getCurrentThreadAllocatedBytes() - before;
        }
        report("machine", allocated, MEASURED_ROUNDS * wire.length, MACHINE_CEILING);
    }

    private static void report(String path, long allocated, long messages, long ceiling) {
        long perMessage = allocated / messages;
        System.out.printf("  %s path: %,d bytes over %,d messages = %,d bytes per message (ceiling %,d)%n",
                path, allocated, messages, perMessage, ceiling);
        assertThat(perMessage).as("bytes allocated per message on the %s path", path).isLessThanOrEqualTo(ceiling);
    }

    private static OrderStatePublisher publisher(OrderStateProperties properties) {
        return new OrderStatePublisher(new OrderStateMachine(properties.machineOptions()),
                new RecordWriter(properties.machineOptions()), new AuditRouter(properties.routingOptions()),
                new DiscardingSink(), new RepairQueue(), List.of(new DeskStatusHook()), properties.publisherOptions());
    }

    /** The whole flow through {@code publisher}; returns the bytes the sends allocated. */
    private static long sendAll(OrderStatePublisher publisher, byte[][] wire, com.sun.management.ThreadMXBean memory)
            throws Exception {
        long before = memory.getCurrentThreadAllocatedBytes();
        for (byte[] message : wire) {
            publisher.onMessage(message, 0, message.length);
        }
        return memory.getCurrentThreadAllocatedBytes() - before;
    }

    private static void applyAll(OrderStateMachine machine, RecordWriter writer, byte[][] wire, FixFrame frame,
                                 FixWriter out) {
        for (byte[] message : wire) {
            frame.wrap(message, 0, message.length);
            Outcome outcome = machine.plan(frame);
            out.clear();
            writer.write(outcome, frame, out);
            machine.commit(outcome);
        }
    }

    /** The scripted flow, then every edge case: the bytes a feed would hand over. */
    static byte[][] flowAsBytes() {
        List<FixEvent> events = EdgeCaseFlow.scriptedThenEdgeCases();
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
        assumeTrue(memory.isThreadAllocatedMemorySupported() && memory.isThreadAllocatedMemoryEnabled(),
                "per-thread allocation counting is not enabled on this JVM");
        return memory;
    }

    /**
     * A sink that keeps nothing but hands back rising sequence numbers, as a
     * publish store does, so the publisher's sequence-to-key history is
     * exercised as in production.
     */
    private static final class DiscardingSink implements PublishSink {
        private long sequence;

        @Override
        public long publish(String topic, byte[] payload, int offset, int length) {
            return ++sequence;
        }

        @Override
        public long deltaPublish(String topic, byte[] payload, int offset, int length) {
            return ++sequence;
        }

        @Override
        public void flush(long timeoutMs) {
        }

        @Override
        public long unpersisted() {
            return 0;
        }

        @Override
        public long lastPersisted() {
            return sequence;
        }
    }
}
