package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.InstrumentSnapshot;
import com.demo.amps.market.v1.Order;

/** Populates the SOW topics the other demos query. */
public final class SowLoadDemo implements Demo {

    @Override
    public String name() {
        return "sow-load";
    }

    @Override
    public String summary() {
        return "Populate the orders and instruments SOW topics";
    }

    @Override
    public String explanation() {
        return "Loading a SOW is just publishing. There is no separate write path, no "
                + "upsert API and no table to create: AMPS keeps the last message per key "
                + "for any topic declared in <SOW>, and every publish updates that record "
                + "in place. Run this first -- the query, delta and recovery demos all read "
                + "what it writes.";
    }

    @Override
    public void run(Args args) throws Exception {
        int orders = args.getInt("orders", 200);
        int instruments = args.getInt("instruments", Fixtures.SYMBOLS.size());
        boolean withFills = args.getBoolean("fills", true);

        try (Client client = AmpsConnections.connect("demo-sow-load")) {

            Console.step("Publishing " + orders + " orders to '" + Topics.ORDERS + "'");
            long orderBytes = 0;
            for (int i = 1; i <= orders; i++) {
                Order order = Fixtures.order(i);
                String json = JsonCodec.toJson(order);
                orderBytes += JsonCodec.byteSize(json);
                client.publish(Topics.ORDERS, json);
            }
            client.publishFlush(DemoConfig.timeoutMillis());
            Console.kv("orders published", orders);
            Console.kv("bytes published", Console.bytes(orderBytes));

            if (withFills) {
                Console.step("Filling every third order");
                Console.note("These are full publishes of an existing key: AMPS replaces the "
                        + "stored record. The SOW record count does not change -- only the "
                        + "contents. That is the difference between a topic with a key and a "
                        + "log that grows forever.");
                int filled = 0;
                for (int i = 1; i <= orders; i += 3) {
                    Order order = Fixtures.order(i);
                    Order afterFill = Fixtures.fill(order, order.getQuantity() / 2);
                    client.publish(Topics.ORDERS, JsonCodec.toJson(afterFill));
                    filled++;
                }
                client.publishFlush(DemoConfig.timeoutMillis());
                Console.kv("orders updated", filled);
            }

            Console.step("Publishing " + instruments + " instrument snapshots to '"
                    + Topics.INSTRUMENTS + "'");
            long instrumentBytes = 0;
            for (int i = 0; i < instruments; i++) {
                String symbol = Fixtures.SYMBOLS.get(i % Fixtures.SYMBOLS.size());
                InstrumentSnapshot snapshot = Fixtures.instrument(symbol);
                String json = JsonCodec.toJson(snapshot);
                instrumentBytes += JsonCodec.byteSize(json);
                client.publish(Topics.INSTRUMENTS, json);
            }
            client.publishFlush(DemoConfig.timeoutMillis());
            Console.kv("instruments published", instruments);
            Console.kv("bytes published", Console.bytes(instrumentBytes));
            Console.kv("average record size",
                    Console.bytes(instruments == 0 ? 0 : instrumentBytes / instruments));

            Console.step("Next");
            Console.bullet("sow-query          snapshot the state you just built");
            Console.bullet("sow-query-by-key   fetch single records");
            Console.bullet("delta-publish      update those big records cheaply");
        }
    }
}
