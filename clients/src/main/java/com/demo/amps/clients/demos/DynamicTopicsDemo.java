package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.clients.MessageStreams;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.Order;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Topics invented at publish time, and regex subscriptions that catch them. */
public final class DynamicTopicsDemo implements Demo {

    private static final List<String> SUFFIXES = List.of(
            "trade.us.equity", "trade.eu.equity", "trade.us.option",
            "quote.us.equity", "risk.intraday");

    @Override
    public String name() {
        return "dynamic-topics";
    }

    @Override
    public String summary() {
        return "Create topics by publishing to them; subscribe to a whole family by regex";
    }

    @Override
    public String explanation() {
        return "AMPS topics are not provisioned. A publisher names one and it exists; the "
                + "cost is a hash-table entry, so a system can carry hundreds of thousands "
                + "of them and use the topic name itself as a routing dimension. Subscribers "
                + "match families with a regular expression evaluated by the server, so a "
                + "new topic is picked up by existing subscriptions the moment it appears. "
                + "Contrast Kafka, where a topic is a provisioned resource with partitions "
                + "and on-disk directories and adding one is an administrative act.";
    }

    @Override
    public void run(Args args) throws Exception {
        int perTopic = args.getInt("count", 3);

        try (Client subscriber = AmpsConnections.connect("demo-dynamic-sub");
             Client publisher = AmpsConnections.connect("demo-dynamic-pub")) {

            String pattern = "^" + Topics.EVENTS_PREFIX.replace(".", "\\.") + ".*";

            Console.step("One regex subscription: " + pattern);
            Console.note("None of the topics it will match exist yet.");

            Map<String, AtomicInteger> perTopicCounts = new ConcurrentHashMap<>();
            CountDownLatch received = new CountDownLatch(SUFFIXES.size() * perTopic);

            subscriber.subscribe(message -> {
                perTopicCounts.computeIfAbsent(message.getTopic(), t -> new AtomicInteger())
                        .incrementAndGet();
                received.countDown();
            }, pattern, DemoConfig.timeoutMillis());

            Console.step("Publishing to topics that are being created as we go");
            int index = 1;
            for (String suffix : SUFFIXES) {
                String topic = Topics.event(suffix);
                for (int i = 0; i < perTopic; i++) {
                    Order order = Fixtures.order(index++);
                    publisher.publish(topic, JsonCodec.toJson(order));
                }
                Console.bullet("created and published to %s", topic);
            }
            publisher.publishFlush(DemoConfig.timeoutMillis());
            received.await(10, TimeUnit.SECONDS);

            Console.step("What the single subscription caught");
            perTopicCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> Console.kv(entry.getKey(), entry.getValue() + " messages"));
            Console.note("The subscription was created before any of these topics existed. "
                    + "No configuration was changed and the server was not restarted.");

            Console.step("Dynamic SOW topics");
            Console.note("A <SOW> topic can also carry a <Pattern>, which captures every topic "
                    + "matching that regex into one physical store. The demo config declares "
                    + "<Name>desk</Name> with <Pattern>^desk\\.[A-Za-z0-9_-]+$</Pattern>, so "
                    + "publishing to desk.equities starts keeping state for it with no config "
                    + "edit and no restart. The three desks share a single desk.json.sow -- "
                    + "one physical topic, three logical ones -- and each is still queried "
                    + "under its own name, as below. Note that the regex belongs in <Pattern>: "
                    + "put it in <Name> and AMPS takes it literally, creating one SOW that "
                    + "nothing routes into and every query below returns zero.");

            for (String desk : Fixtures.DESKS) {
                String topic = Topics.desk(desk);
                for (int i = 0; i < perTopic; i++) {
                    publisher.publish(topic, JsonCodec.toJson(Fixtures.order(index++)));
                }
                Console.bullet("published %d orders to %s", perTopic, topic);
            }
            publisher.publishFlush(DemoConfig.timeoutMillis());

            for (String desk : Fixtures.DESKS) {
                String topic = Topics.desk(desk);
                try (MessageStream stream = publisher.execute(
                        new Command("sow").setTopic(topic).setTimeout(DemoConfig.timeoutMillis()))) {
                    AtomicInteger records = new AtomicInteger();
                    MessageStreams.forEach(stream, 3000, message -> {
                        if (message.getCommand() == Message.Command.SOW) {
                            records.incrementAndGet();
                        }
                        return true;
                    });
                    Console.kv(topic + " SOW records", records.get());
                } catch (Exception e) {
                    Console.kv(topic + " SOW query", "failed: " + e.getMessage());
                }
            }
            Console.note("A non-zero count means the <Pattern> took effect: each desk is "
                    + "keeping queryable state, and no configuration changed to make that "
                    + "happen. Zero would mean the pattern never matched -- check that the "
                    + "regex is in <Pattern> and not in <Name>.");
        }
    }
}
