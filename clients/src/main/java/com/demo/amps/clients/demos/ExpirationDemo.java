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
import com.demo.amps.market.v1.InstrumentSnapshot;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounding SOW growth with per-record time-to-live. */
public final class ExpirationDemo implements Demo {

    @Override
    public String name() {
        return "expiration";
    }

    @Override
    public String summary() {
        return "Give SOW records a TTL so state stays proportional to the working set";
    }

    @Override
    public String explanation() {
        return "A SOW holds one record per key forever unless something removes it, so a "
                + "key space that grows without bound -- session ids, order ids, "
                + "instruments that stop trading -- eventually becomes the storage problem. "
                + "Expiration fixes that declaratively: the topic carries a default TTL and "
                + "individual publishes can set their own. When a record expires AMPS "
                + "removes it and tells subscribers via an OOF with reason 'expired', so "
                + "downstream views stay correct instead of holding rows that no longer "
                + "exist. This is the cheapest of the retention levers and the one most "
                + "often left unused.";
    }

    @Override
    public void run(Args args) throws Exception {
        String topic = args.get("topic", Topics.QUOTE_CACHE);
        int ttlSeconds = args.getInt("ttl", 5);
        int symbols = args.getInt("symbols", 4);
        int waitSeconds = args.getInt("wait", ttlSeconds + 4);

        try (Client publisher = AmpsConnections.connect("demo-expiry-pub");
             Client subscriber = AmpsConnections.connect("demo-expiry-sub")) {

            Console.step("Watching " + topic + " for expiry notifications");
            AtomicInteger expiries = new AtomicInteger();
            Command watch = new Command("sow_and_subscribe")
                    .setTopic(topic)
                    .setOptions(Message.Options.OOF + Message.Options.SendKeys)
                    .setTimeout(DemoConfig.timeoutMillis());
            subscriber.executeAsync(watch, message -> {
                if (message.getCommand() == Message.Command.OOF) {
                    expiries.incrementAndGet();
                    Console.info("   OOF <- %s (reason: %s)",
                            message.isSowKeyNull() ? "?" : message.getSowKey(),
                            Messages.reasonName(message.getReason()));
                }
            });
            Thread.sleep(400);

            Console.step("Publishing " + symbols + " records with a " + ttlSeconds + "s TTL");
            Console.note("setExpiration on the command overrides the topic default. The "
                    + "topic must have expiration enabled in the config for either to take "
                    + "effect -- quote-cache declares <Expiration>60s</Expiration>.");

            for (int i = 0; i < symbols; i++) {
                String symbol = Fixtures.SYMBOLS.get(i);
                InstrumentSnapshot snapshot = Fixtures.instrument(symbol);
                // The three-argument publish carries a per-message expiration in
                // seconds, overriding the topic default.
                publisher.publish(topic, JsonCodec.toJson(snapshot), ttlSeconds);
                Console.bullet("published %s, expires in %ds", symbol, ttlSeconds);
            }
            publisher.publishFlush(DemoConfig.timeoutMillis());

            Console.step("Immediately after publishing");
            int before = count(publisher, topic);
            Console.kv("records in " + topic, before);

            Console.step("Waiting " + waitSeconds + "s for the TTL to elapse");
            for (int elapsed = 1; elapsed <= waitSeconds; elapsed++) {
                Thread.sleep(1000);
                if (elapsed % 2 == 0 || elapsed == waitSeconds) {
                    Console.info("   t+%-3ds  records: %d   expiry notifications: %d",
                            elapsed, count(publisher, topic), expiries.get());
                }
            }

            int after = count(publisher, topic);
            Console.step("Result");
            Console.kv("records before", before);
            Console.kv("records after", after);
            Console.kv("expiry notifications", expiries.get());
            Console.note(after < before
                    ? "The SOW shrank on its own. Nothing ran a cleanup job and no client "
                      + "issued a delete -- the records simply stopped existing, and every "
                      + "subscriber was told."
                    : "Nothing expired. Check that the topic declares <Expiration> and that "
                      + "the TTL has actually elapsed (try --ttl 3 --wait 10).");

            Console.step("How this interacts with restart persistence");
            Console.note("quote-cache is also declared <Durability>transient</Durability> and "
                    + "kept out of the transaction log. Between the TTL, the transient SOW "
                    + "and the absent journal entries, this topic costs nothing across a "
                    + "restart -- which is right for data that is cheap to rebuild. Compare "
                    + "with `orders`, where all three are the opposite.");
        }
    }

    private static int count(Client client, String topic) throws Exception {
        AtomicInteger records = new AtomicInteger();
        try (MessageStream stream = client.execute(new Command("sow")
                .setTopic(topic)
                .setTimeout(DemoConfig.timeoutMillis()))) {
            MessageStreams.forEach(stream, 4000, message -> {
                if (message.getCommand() == Message.Command.SOW) {
                    records.incrementAndGet();
                }
                return true;
            });
        }
        return records.get();
    }
}
