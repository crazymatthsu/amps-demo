package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.clients.Messages;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DeltaBuilder;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.InstrumentSnapshot;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** The receiving half: get the snapshot once, then only what changes. */
public final class DeltaSubscribeDemo implements Demo {

    private static final List<String> SYMBOL_KEY = List.of("symbol");

    @Override
    public String name() {
        return "delta-subscribe";
    }

    @Override
    public String summary() {
        return "Receive a full snapshot once, then only changed fields on every update";
    }

    @Override
    public String explanation() {
        return "sow_and_delta_subscribe is the mirror of delta_publish and it applies even "
                + "when publishers send whole records: AMPS knows the previous state, so it "
                + "can compute what changed and send only that. A screen showing 5,000 rows "
                + "of a wide record gets its full picture once and then a few bytes per "
                + "update. Note this is a server-side saving on the *outbound* side, "
                + "independent of how the data was published.";
    }

    @Override
    public void run(Args args) throws Exception {
        String topic = args.get("topic", Topics.INSTRUMENTS);
        String symbol = args.get("symbol", "MSFT");
        int ticks = args.getInt("ticks", 6);

        try (Client subscriber = AmpsConnections.connect("demo-delta-sub");
             Client publisher = AmpsConnections.connect("demo-delta-sub-pub")) {

            Console.step("Seeding " + symbol);
            InstrumentSnapshot base = Fixtures.instrument(symbol);
            publisher.publish(topic, JsonCodec.toJson(base));
            publisher.publishFlush(DemoConfig.timeoutMillis());

            Console.step("sow_and_delta_subscribe on /symbol = '" + symbol + "'");
            AtomicLong snapshotBytes = new AtomicLong();
            AtomicLong deltaBytes = new AtomicLong();
            CountDownLatch done = new CountDownLatch(ticks);

            Command command = new Command("sow_and_delta_subscribe")
                    .setTopic(topic)
                    .setFilter("/symbol = '" + symbol + "'")
                    .setOptions(Message.Options.SendKeys)
                    .setTimeout(DemoConfig.timeoutMillis());

            subscriber.executeAsync(command, message -> {
                if (Messages.isGroupMarker(message) || message.isDataNull()) {
                    return;
                }
                int size = JsonCodec.byteSize(message.getData());
                if (message.getCommand() == Message.Command.SOW) {
                    snapshotBytes.addAndGet(size);
                    Console.info("   snapshot <- %4d B  (the whole record, once)", size);
                } else {
                    deltaBytes.addAndGet(size);
                    Console.info("   delta    <- %4d B  %s", size,
                            Messages.truncate(message.getData(), 96));
                    done.countDown();
                }
            });

            Thread.sleep(700);

            Console.step("Publishing " + ticks + " ticks");
            InstrumentSnapshot previous = base;
            Random random = new Random(11);
            long publishedFullBytes = 0;
            for (int i = 0; i < ticks; i++) {
                InstrumentSnapshot next = Fixtures.tick(previous, random);
                DeltaBuilder.Delta delta = DeltaBuilder.between(previous, next, SYMBOL_KEY);
                publishedFullBytes += JsonCodec.byteSize(JsonCodec.toJson(next));
                publisher.deltaPublish(topic, delta.json());
                previous = next;
                Thread.sleep(120);
            }
            publisher.publishFlush(DemoConfig.timeoutMillis());
            done.await(15, TimeUnit.SECONDS);

            Console.step("Bytes delivered to this subscriber");
            Console.kv("initial snapshot", Console.bytes(snapshotBytes.get()));
            Console.kv("all " + ticks + " updates combined", Console.bytes(deltaBytes.get()));
            Console.kv("if each update were a full record", Console.bytes(publishedFullBytes));
            if (deltaBytes.get() > 0) {
                Console.kv("reduction on the update stream",
                        String.format("%.1fx", (double) publishedFullBytes / deltaBytes.get()));
            }
            Console.note("Multiply by the number of subscribers: this is bandwidth saved per "
                    + "consumer, on top of the storage saved in the transaction log.");
        }
    }
}
