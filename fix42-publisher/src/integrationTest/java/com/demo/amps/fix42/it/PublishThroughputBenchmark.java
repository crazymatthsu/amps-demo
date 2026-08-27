package com.demo.amps.fix42.it;

import com.crankuptheamps.client.Client;
import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.mock.FixEvent;
import com.demo.amps.fix42.mock.Instrument;
import com.demo.amps.fix42.mock.OrderChain;
import com.demo.amps.fix42.publish.PublishInstruction;
import com.demo.amps.fix42.publish.PublishPlanner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Where publishing time actually goes. Off by default:
 * {@code -Dfix42.benchmark=true}.
 */
@EnabledIfSystemProperty(named = "fix42.benchmark", matches = "true")
class PublishThroughputBenchmark {

    private static final int CHAINS = 1_500;

    @Test
    void measure() throws Exception {
        if (AmpsTestServer.unavailableReason().isPresent()) {
            return;
        }
        List<FixMessage> messages = new ArrayList<>();
        for (int i = 0; i < CHAINS; i++) {
            OrderChain chain = OrderChain.forTest("BENCH-" + i, Instrument.AAPL, 10_000, 150.00)
                    .newOrder().ack().partialFill(3_000, 150.00)
                    .amend(12_000, 150.10).amendAck().fill(150.20);
            for (FixEvent event : chain.events()) {
                messages.add(event.message());
            }
        }

        try (AmpsTestServer server = AmpsTestServer.start()) {
            Fix42Properties props = Fix42Configurations.shipped(server.uri());
            PublishPlanner planner = new PublishPlanner(props);

            // Plan once so planning cost is excluded from the send timings.
            List<PublishInstruction> plan = new ArrayList<>();
            long planStart = System.nanoTime();
            for (FixMessage m : messages) {
                plan.addAll(planner.plan(m));
            }
            long planMs = (System.nanoTime() - planStart) / 1_000_000;

            System.out.printf("%n  %d messages -> %d publishes%n", messages.size(), plan.size());
            System.out.printf("  planning (routing + tag selection): %d ms%n", planMs);

            run("A. flush after EVERY publish", server, props, plan, true, 0);
            run("B. flush once at the end", server, props, plan, false, 0);
            run("C. + setPublishBatching(8KB, 10ms)", server, props, plan, false, 8192);
            run("D. + setPublishBatching(64KB, 10ms)", server, props, plan, false, 65536);
            run("E. + setPublishBatching(256KB, 50ms)", server, props, plan, false, 262144);

            // The shipped publisher logs one INFO line per publish, and the
            // line renders the payload. Measured through the real class.
            viaPublisher("F. AmpsDeltaPublisher, logging at INFO (as shipped)", server, props,
                    messages, "INFO");
            viaPublisher("G. AmpsDeltaPublisher, logging at WARN", server, props,
                    messages, "WARN");
        }
    }

    /** The same load through the real publisher, at a given log level. */
    private void viaPublisher(String label, AmpsTestServer server, Fix42Properties props,
                              List<FixMessage> messages, String level) throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("com.demo.amps.fix42");
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.toLevel(level));

        Client client = new Client("bench-" + label.charAt(0));
        client.connect(props.amps().uri());
        client.logon(props.amps().timeoutMs());
        com.demo.amps.fix42.publish.AmpsDeltaPublisher publisher =
                new com.demo.amps.fix42.publish.AmpsDeltaPublisher(
                        client, new PublishPlanner(props), props);

        long start = System.nanoTime();
        for (FixMessage m : messages) {
            publisher.send(m);
        }
        publisher.flush();
        long ms = (System.nanoTime() - start) / 1_000_000;
        long sent = publisher.fullPublishCount() + publisher.deltaPublishCount();
        client.close();
        logger.setLevel(previous);

        System.out.printf("  %-46s %6d ms   %,9d msg/s%n",
                label, ms, ms == 0 ? 0 : (sent * 1000L / ms));
    }

    private void run(String label, AmpsTestServer server, Fix42Properties props,
                     List<PublishInstruction> plan, boolean flushEach, int batchBytes)
            throws Exception {
        Client client = new Client("bench-" + label.charAt(0));
        client.connect(props.amps().uri());
        client.logon(props.amps().timeoutMs());
        if (batchBytes > 0) {
            client.setPublishBatching(batchBytes, 10);
        }

        long start = System.nanoTime();
        for (PublishInstruction i : plan) {
            String payload = i.payload().render();
            if (i.mode() == com.demo.amps.fix42.config.PublishMode.FULL) {
                client.publish(i.topic(), payload);
            } else {
                client.deltaPublish(i.topic(), payload);
            }
            if (flushEach) {
                client.publishFlush(props.amps().timeoutMs());
            }
        }
        client.publishFlush(60_000);
        long ms = (System.nanoTime() - start) / 1_000_000;
        client.close();

        System.out.printf("  %-46s %6d ms   %,9d msg/s%n",
                label, ms, ms == 0 ? 0 : (plan.size() * 1000L / ms));
    }
}
