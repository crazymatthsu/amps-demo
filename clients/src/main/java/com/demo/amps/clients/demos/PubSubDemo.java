package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.clients.Messages;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.MessageStreams;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.Order;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Plain publish/subscribe on an undeclared topic. */
public final class PubSubDemo implements Demo {

    @Override
    public String name() {
        return "pubsub";
    }

    @Override
    public String summary() {
        return "Publish and subscribe on a topic that exists in no configuration file";
    }

    @Override
    public String explanation() {
        return "The simplest thing AMPS does, and the baseline for everything else. "
                + "The topic is not declared anywhere: it exists because someone published "
                + "to it. There is no state, no key, no journal -- a subscriber that is not "
                + "connected when a message is published never sees it. Everything that "
                + "follows in these demos is what you gain by declaring the topic.";
    }

    @Override
    public void run(Args args) throws Exception {
        String topic = args.get("topic", Topics.event("trade.us"));
        int count = args.getInt("count", 5);

        try (Client subscriber = AmpsConnections.connect("demo-pubsub-sub");
             Client publisher = AmpsConnections.connect("demo-pubsub-pub")) {

            Console.step("Subscribing to " + topic);
            CountDownLatch received = new CountDownLatch(count);
            AtomicInteger seen = new AtomicInteger();

            // Async handler: AMPS calls this on the client's background thread as
            // messages arrive. The alternative -- iterating a MessageStream -- is
            // used elsewhere in these demos; both are first-class.
            subscriber.subscribe(message -> {
                Console.info("   in  <- %s", Messages.withSize(message));
                seen.incrementAndGet();
                received.countDown();
            }, topic, com.demo.amps.common.DemoConfig.timeoutMillis());

            Console.step("Publishing " + count + " orders");
            for (int i = 1; i <= count; i++) {
                Order order = Fixtures.order(i);
                String json = JsonCodec.toJson(order);
                publisher.publish(topic, json);
                Console.info("   out ->  %4d B  %s", JsonCodec.byteSize(json),
                        Messages.truncate(json, 100));
            }
            publisher.publishFlush(com.demo.amps.common.DemoConfig.timeoutMillis());

            received.await(10, TimeUnit.SECONDS);

            Console.step("Result");
            Console.kv("published", count);
            Console.kv("received", seen.get());

            Console.step("Now the part that matters");
            Console.note("A second subscriber, connecting after the fact, sees nothing at "
                    + "all -- there is no history on an undeclared topic:");

            try (Client latecomer = AmpsConnections.connect("demo-pubsub-late");
                 MessageStream stream = latecomer.subscribe(topic)) {
                int late = MessageStreams.forEach(stream, 1500, message -> true);
                Console.kv("messages replayed to latecomer", late);
                Console.note(late == 0
                        ? "Zero, as expected. Compare with the bookmark-replay demo, where a "
                          + "transaction-logged topic hands a latecomer the entire history."
                        : "Unexpected: this topic appears to be transaction-logged.");
            }
        }
    }
}
