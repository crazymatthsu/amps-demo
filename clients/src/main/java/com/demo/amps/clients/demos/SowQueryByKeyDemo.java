package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.clients.Messages;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.MessageStreams;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fetching and following individual records by key. */
public final class SowQueryByKeyDemo implements Demo {

    @Override
    public String name() {
        return "sow-query-by-key";
    }

    @Override
    public String summary() {
        return "Look up single records by business key and by SOW key, then follow one live";
    }

    @Override
    public String explanation() {
        return "Two different keys are involved and confusing them is the classic AMPS "
                + "beginner mistake. The BUSINESS key is the JSON field named in the config "
                + "(<Key>/orderId</Key>); you query it with an ordinary filter and AMPS uses "
                + "the key index rather than scanning. The SOW KEY is an opaque identifier "
                + "the server assigns to each record and reports on every message; you can "
                + "hand a set of them back with sow_keys for the cheapest possible lookup, "
                + "but you cannot compute one client-side. This demo does both, then leaves "
                + "a query-and-subscribe running on a single key so updates to that one "
                + "record arrive live.";
    }

    @Override
    public void run(Args args) throws Exception {
        String topic = args.get("topic", Topics.ORDERS);
        String orderId = args.get("orderId", Fixtures.orderId(7));

        try (Client client = AmpsConnections.connect("demo-sow-key");
             Client publisher = AmpsConnections.connect("demo-sow-key-pub")) {

            // ---- 1. by business key -------------------------------------------
            Console.step("Lookup by business key: /orderId = '" + orderId + "'");
            List<String> sowKeys = new ArrayList<>();
            Command byField = new Command("sow")
                    .setTopic(topic)
                    .setFilter("/orderId = '" + orderId + "'")
                    .setTimeout(DemoConfig.timeoutMillis());

            try (MessageStream stream = client.execute(byField)) {
                MessageStreams.forEach(stream, 5000, message -> {
                    if (message.getCommand() != Message.Command.SOW) {
                        return true;
                    }
                    Order order = JsonCodec.fromJson(message.getData(), Order.getDefaultInstance());
                    sowKeys.add(message.getSowKey());
                    Console.kv("orderId", order.getOrderId());
                    Console.kv("symbol / side", order.getSymbol() + " " + order.getSide());
                    Console.kv("quantity / filled",
                            order.getQuantity() + " / " + order.getFilledQuantity());
                    Console.kv("price", order.getPrice());
                    Console.kv("server-assigned sowkey", message.getSowKey());
                    return true;
                });
            }

            if (sowKeys.isEmpty()) {
                Console.note("No such record. Run `sow-load` first.");
                return;
            }

            // ---- 2. by SOW key ------------------------------------------------
            Console.step("Lookup by SOW key: sow_keys = " + sowKeys.get(0));
            Console.note("This is the identifier AMPS just reported. It is derived from the "
                    + "key fields by the server, is stable for the life of the record, and "
                    + "is the cheapest way to re-fetch records you have already seen -- a "
                    + "GUI refreshing rows it is displaying, for instance.");

            Command byKey = new Command("sow")
                    .setTopic(topic)
                    .setSowKeys(String.join(",", sowKeys))
                    .setTimeout(DemoConfig.timeoutMillis());
            int found;
            try (MessageStream stream = client.execute(byKey)) {
                int[] records = {0};
                MessageStreams.forEach(stream, 5000, message -> {
                    if (message.getCommand() == Message.Command.SOW) {
                        records[0]++;
                        Console.info("   %s", Messages.withSize(message));
                    }
                    return true;
                });
                found = records[0];
            }
            Console.kv("records returned", found);

            // ---- 3. query and subscribe by key --------------------------------
            Console.step("Query and subscribe to that one record");
            Console.note("sow_and_subscribe returns the current record and then keeps the "
                    + "subscription open, atomically. Everything the publisher below does to "
                    + "this order arrives without a second query.");

            CountDownLatch updates = new CountDownLatch(2);
            Command follow = new Command("sow_and_subscribe")
                    .setTopic(topic)
                    .setFilter("/orderId = '" + orderId + "'")
                    .setOptions(Message.Options.SendKeys + Message.Options.Timestamp)
                    .setTimeout(DemoConfig.timeoutMillis());

            client.executeAsync(follow, message -> {
                if (Messages.isGroupMarker(message)) {
                    return;
                }
                String phase = message.getCommand() == Message.Command.SOW ? "snapshot" : "live";
                if (message.isDataNull()) {
                    return;
                }
                Order order = JsonCodec.fromJson(message.getData(), Order.getDefaultInstance());
                Console.info("   %-9s revision=%-3d filled=%-6d status=%s",
                        phase, order.getRevision(), order.getFilledQuantity(), order.getStatus());
                if (!"snapshot".equals(phase)) {
                    updates.countDown();
                }
            });

            Thread.sleep(500);

            Console.info("");
            Console.info("   ...publisher fills the order twice...");
            Order current = Fixtures.order(indexOf(orderId));
            Order firstFill = Fixtures.fill(current, current.getQuantity() / 4);
            publisher.publish(topic, JsonCodec.toJson(firstFill));
            publisher.publishFlush(DemoConfig.timeoutMillis());
            Thread.sleep(300);

            Order secondFill = Fixtures.fill(firstFill, firstFill.getQuantity());
            publisher.publish(topic, JsonCodec.toJson(secondFill));
            publisher.publishFlush(DemoConfig.timeoutMillis());

            updates.await(10, TimeUnit.SECONDS);

            Console.step("Takeaway");
            Console.note("One command gave a consistent starting point plus every subsequent "
                    + "change to that key. Doing this on Kafka means reading a compacted "
                    + "topic to its end to build the current value, then continuing to "
                    + "consume -- and getting the handover right is the caller's problem.");
        }
    }

    /** Recovers the fixture index from a generated order id such as ORD-00007. */
    private static int indexOf(String orderId) {
        int dash = orderId.lastIndexOf('-');
        return dash < 0 ? 1 : Integer.parseInt(orderId.substring(dash + 1));
    }
}
