package com.demo.amps.fix42.state.it;

import com.crankuptheamps.client.HAClient;
import com.demo.amps.fix42.state.amps.AmpsClientConfiguration;
import com.demo.amps.fix42.state.amps.AmpsFailedWriteHandler;
import com.demo.amps.fix42.state.amps.AmpsPublishSink;
import com.demo.amps.fix42.state.config.OrderStateProperties;
import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.Outcome;
import com.demo.amps.fix42.state.machine.RecordWriter;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.mock.Instrument;
import com.demo.amps.fix42.state.mock.OrderChain;
import com.demo.amps.fix42.state.publish.AuditRouter;
import com.demo.amps.fix42.state.publish.OrderStatePublisher;
import com.demo.amps.fix42.state.publish.RepairQueue;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * docs/05 phase 5: what the cache and the publish store cost, as numbers
 * rather than arguments. Off by default:
 * {@code ./gradlew :fix42-order-state-publisher:publishBenchmark}.
 *
 * <p>The load is {@code fix42-publisher}'s {@code PublishThroughputBenchmark}'s,
 * so its run on the same machine is the comparison (README "What it costs"):
 * 1,500 orders, each a D, the ack, a partial fill, an amend, the amend's ack,
 * and the final fill. That is 9,000 messages. Each message is one blotter
 * publish here, plus its audit copies.
 *
 * <p>Every variant gets its own order ids, so no variant meets another's
 * records, and is timed from the first message to a flush that returns once
 * the server has everything. One untimed run first warms the JIT. The numbers
 * belong to the machine they ran on (on the author's, an emulated linux/amd64
 * AMPS under podman on macOS), which is the point: run it where you publish.
 */
@EnabledIfSystemProperty(named = "fix42-order-state.benchmark", matches = "true")
class OrderStateThroughputBenchmark {

    private static final int CHAINS = 1_500;

    @Test
    void measure() throws Exception {
        if (AmpsTestServer.unavailableReason(AmpsFlow.FIX42_ORDER_STATE).isPresent()) {
            return;
        }
        try (AmpsTestServer server = AmpsTestServer.start(AmpsFlow.FIX42_ORDER_STATE)) {
            System.out.printf("%n  %d orders x 6 messages = %,d messages%n", CHAINS, CHAINS * 6);

            OrderStateProperties machineOnly = settings(server, "warm", "MEMORY");
            for (int round = 0; round < 5; round++) {
                viaMachine(machineOnly, load("W" + round + "-"));
            }
            viaPublisher(null, server, "MEMORY", "WARN", load("W-"));

            long machineNanos = 0;
            for (int round = 0; round < 10; round++) {
                byte[][] load = load("A" + round + "-");
                long start = System.nanoTime();
                viaMachine(machineOnly, load);
                machineNanos += System.nanoTime() - start;
            }
            print("A. the machine alone: plan, write, commit (no server)", machineNanos / 10, 0, CHAINS * 6L, -1);

            viaPublisher("B. publisher, publish-store NONE (at most once)", server, "NONE", "WARN", load("B-"));
            viaPublisher("C. publisher, publish-store MEMORY", server, "MEMORY", "WARN", load("C-"));
            viaPublisher("D. publisher, publish-store FILE", server, "FILE", "WARN", load("D-"));
            viaPublisher("E. publisher, MEMORY, logging at INFO (as the demo)", server, "MEMORY", "INFO",
                    load("E-"));

            // What the server costs for this flow: the publisher's own
            // publishes for a fresh load, recorded without a server, then sent
            // with nothing else in the loop.
            List<Publish> recorded = record(machineOnly, load("F-"));
            com.crankuptheamps.client.Client plain = new com.crankuptheamps.client.Client("bench-F-" + System.nanoTime());
            plain.connect(server.uri());
            plain.logon(120_000);
            replay("F. its publishes replayed through a plain Client", plain, recorded);
            recorded = record(machineOnly, load("G-"));
            HAClient ha = AmpsClientConfiguration.connect(settings(server, "bench-G-" + System.nanoTime(), "NONE").amps(),
                    new AmpsFailedWriteHandler(new RepairQueue()));
            replay("G. the same through an HAClient, no store", ha, recorded);
        }
    }

    /** One publish as the publisher issued it. */
    private record Publish(byte[] topic, byte[] payload, boolean delta) {
    }

    /** The publishes {@code wire} produces, captured by a sink that keeps copies. */
    private static List<Publish> record(OrderStateProperties settings, byte[][] wire) throws Exception {
        List<Publish> publishes = new java.util.ArrayList<>();
        com.demo.amps.fix42.state.publish.PublishSink capture = new com.demo.amps.fix42.state.publish.PublishSink() {
            @Override
            public long publish(String topic, byte[] payload, int offset, int length) {
                publishes.add(new Publish(topic.getBytes(StandardCharsets.ISO_8859_1),
                        java.util.Arrays.copyOfRange(payload, offset, offset + length), false));
                return 0;
            }

            @Override
            public long deltaPublish(String topic, byte[] payload, int offset, int length) {
                publishes.add(new Publish(topic.getBytes(StandardCharsets.ISO_8859_1),
                        java.util.Arrays.copyOfRange(payload, offset, offset + length), true));
                return 0;
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
                return Long.MAX_VALUE;
            }
        };
        OrderStatePublisher publisher = new OrderStatePublisher(new OrderStateMachine(settings.machineOptions()),
                new RecordWriter(settings.machineOptions()), new AuditRouter(settings.routingOptions()), capture,
                new RepairQueue(), List.of(), settings.publisherOptions());
        for (byte[] message : wire) {
            publisher.onMessage(message, 0, message.length);
        }
        return publishes;
    }

    private static void replay(String label, com.crankuptheamps.client.Client client, List<Publish> publishes)
            throws Exception {
        try {
            long start = System.nanoTime();
            for (Publish p : publishes) {
                if (p.delta()) {
                    client.deltaPublish(p.topic(), 0, p.topic().length, p.payload(), 0, p.payload().length);
                } else {
                    client.publish(p.topic(), 0, p.topic().length, p.payload(), 0, p.payload().length);
                }
            }
            long sent = System.nanoTime();
            client.publishFlush(120_000);
            print(label, sent - start, System.nanoTime() - sent, -1, publishes.size());
        } finally {
            client.close();
        }
    }

    /** The load as the bytes a feed hands over, under ids starting {@code prefix}. */
    private static byte[][] load(String prefix) {
        byte[][] wire = new byte[CHAINS * 6][];
        int n = 0;
        for (int i = 0; i < CHAINS; i++) {
            OrderChain chain = OrderChain.forTest(prefix + "BENCH-" + i, Instrument.AAPL, 10_000, 150.00)
                    .newOrder().ack().partialFill(3_000, 150.00)
                    .amend(12_000, 150.10).amendAck().fill(150.20);
            for (FixEvent event : chain.events()) {
                wire[n++] = event.message().render().getBytes(StandardCharsets.ISO_8859_1);
            }
        }
        return n == wire.length ? wire : java.util.Arrays.copyOf(wire, n);
    }

    private static void viaMachine(OrderStateProperties settings, byte[][] wire) {
        OrderStateMachine machine = new OrderStateMachine(settings.machineOptions());
        RecordWriter writer = new RecordWriter(settings.machineOptions());
        FixFrame frame = new FixFrame();
        FixWriter out = new FixWriter(1024);
        for (byte[] message : wire) {
            frame.wrap(message, 0, message.length);
            Outcome outcome = machine.plan(frame);
            out.clear();
            writer.write(outcome, frame, out);
            machine.commit(outcome);
        }
    }

    /** {@code wire} through a publisher on its own client, timed to a flush; {@code label} null runs it untimed. */
    private static void viaPublisher(String label, AmpsTestServer server, String store, String level,
                                     byte[][] wire) throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("com.demo.amps.fix42.state");
        ch.qos.logback.classic.Level previous = logger.getLevel();
        String name = "bench-" + (label == null ? "warm" : label.substring(0, 1)) + "-" + System.nanoTime();
        OrderStateProperties settings = settings(server, name, store);
        RepairQueue repairs = new RepairQueue();
        HAClient client = AmpsClientConfiguration.connect(settings.amps(), new AmpsFailedWriteHandler(repairs));
        logger.setLevel(ch.qos.logback.classic.Level.toLevel(level));
        try {
            OrderStatePublisher publisher = new OrderStatePublisher(
                    new OrderStateMachine(settings.machineOptions()), new RecordWriter(settings.machineOptions()),
                    new AuditRouter(settings.routingOptions()), new AmpsPublishSink(client), repairs, List.of(),
                    settings.publisherOptions());
            long start = System.nanoTime();
            for (byte[] message : wire) {
                publisher.onMessage(message, 0, message.length);
            }
            long sent = System.nanoTime();
            publisher.flush();
            long flushed = System.nanoTime();
            OrderStatePublisher.Counters c = publisher.counters();
            long publishes = c.fullPublishes() + c.deltaPublishes() + c.auditPublishes();
            if (label != null) {
                print(label, sent - start, flushed - sent, wire.length, publishes);
            }
        } finally {
            logger.setLevel(previous);
            client.close();
            if ("FILE".equals(store)) {
                deleteTree(Path.of(settings.amps().publishStoreDir()));
            }
        }
    }

    private static OrderStateProperties settings(AmpsTestServer server, String clientName, String store) {
        return OrderStateFixture.settings(server, clientName, "demo", "amps.publish-store=" + store,
                "amps.publish-store-dir=fix42-order-state-publisher/build/benchmark-store/" + clientName,
                "amps.timeout-ms=120000");
    }

    /**
     * One line: the send loop's time, the final flush's, and the rates over
     * both. {@code messages} or {@code publishes} below zero is left out.
     */
    private static void print(String label, long sendNanos, long flushNanos, long messages, long publishes) {
        double sendMs = sendNanos / 1e6;
        double flushMs = flushNanos / 1e6;
        double totalSeconds = Math.max(1e-9, (sendNanos + flushNanos) / 1e9);
        System.out.printf("  %-52s send %8.1f ms  flush %7.1f ms  %9s  %s%n", label, sendMs, flushMs,
                messages < 0 ? "" : String.format("%,.0f msg/s", messages / totalSeconds),
                publishes < 0 ? "" : String.format("%,.0f publishes/s", publishes / totalSeconds));
    }

    private static void deleteTree(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
