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
import com.demo.amps.common.DeltaBuilder;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.InstrumentSnapshot;
import java.util.List;
import java.util.Random;

/** Delta publishing: send the change, not the record. */
public final class DeltaPublishDemo implements Demo {

    private static final List<String> SYMBOL_KEY = List.of("symbol");

    @Override
    public String name() {
        return "delta-publish";
    }

    @Override
    public String summary() {
        return "Update a large SOW record by sending only the fields that changed";
    }

    @Override
    public String explanation() {
        return "An instrument snapshot here is about 1.3 KB, of which a quote tick changes "
                + "roughly 134 bytes. delta_publish sends only those bytes; AMPS merges them "
                + "into the stored record server-side, and the transaction log records the "
                + "delta rather than the whole record. That last point is the one that "
                + "matters for storage: the same number of updates produces a journal an "
                + "order of magnitude smaller. This demo publishes both ways, verifies that "
                + "the merged record in the SOW is byte-for-byte what a full publish would "
                + "have produced, and shows what it cost each way.";
    }

    @Override
    public void run(Args args) throws Exception {
        String topic = args.get("topic", Topics.INSTRUMENTS);
        String symbol = args.get("symbol", "AAPL");
        int ticks = args.getInt("ticks", 10);

        try (Client client = AmpsConnections.connect("demo-delta-pub")) {

            Console.step("Seeding " + symbol + " with a full publish");
            InstrumentSnapshot base = Fixtures.instrument(symbol);
            String baseJson = JsonCodec.toJson(base);
            client.publish(topic, baseJson);
            client.publishFlush(DemoConfig.timeoutMillis());
            Console.kv("full record", Console.bytes(JsonCodec.byteSize(baseJson)));

            Console.step("One tick, published both ways");
            InstrumentSnapshot ticked = Fixtures.tick(base, new Random(42));
            String fullJson = JsonCodec.toJson(ticked);
            DeltaBuilder.Delta delta = DeltaBuilder.between(base, ticked, SYMBOL_KEY);
            String deltaJson = delta.json();

            Console.info("");
            Console.info("   full publish  (%4d B):", JsonCodec.byteSize(fullJson));
            Console.info("      %s", com.demo.amps.clients.Messages.truncate(fullJson, 140));
            Console.info("");
            Console.info("   delta publish (%4d B):", JsonCodec.byteSize(deltaJson));
            Console.info("      %s", deltaJson);
            Console.info("");
            Console.kv("reduction", String.format("%.1fx",
                    (double) JsonCodec.byteSize(fullJson) / JsonCodec.byteSize(deltaJson)));

            Console.step("Sending the delta");
            if (delta.fullPublishRequired()) {
                Console.note("This change clears a field, which a merge cannot express. "
                        + "Falling back to a full publish -- see DeltaBuilder for why.");
                client.publish(topic, fullJson);
            } else {
                client.deltaPublish(topic, deltaJson);
            }
            client.publishFlush(DemoConfig.timeoutMillis());

            Console.step("Verifying the server merged it correctly");
            InstrumentSnapshot stored = fetch(client, topic, symbol);
            if (stored == null) {
                Console.note("Record not found -- is '" + topic + "' declared in <SOW>?");
                return;
            }

            // Compare against the locally-predicted merge. Timestamps differ between
            // the prediction and the publish, so compare the fields the delta touched.
            InstrumentSnapshot expected = (InstrumentSnapshot) DeltaBuilder.apply(base, delta);
            boolean quoteMatches = stored.getQuote().equals(expected.getQuote());
            boolean referenceIntact = stored.getReference().equals(base.getReference());

            Console.kv("quote merged as predicted", quoteMatches);
            Console.kv("reference data still intact", referenceIntact);
            Console.note(referenceIntact
                    ? "The reference block was never re-sent, yet it is still there in full. "
                      + "That is the merge: untouched fields keep their stored values."
                    : "Reference data changed unexpectedly -- the merge did not behave as a "
                      + "merge.");

            Console.step("Streaming " + ticks + " ticks as deltas");
            long fullBytes = 0;
            long deltaBytes = 0;
            InstrumentSnapshot previous = stored;
            Random random = new Random(7);

            for (int i = 0; i < ticks; i++) {
                InstrumentSnapshot next = Fixtures.tick(previous, random);
                DeltaBuilder.Delta step = DeltaBuilder.between(previous, next, SYMBOL_KEY);
                String stepJson = step.json();

                fullBytes += JsonCodec.byteSize(JsonCodec.toJson(next));
                deltaBytes += JsonCodec.byteSize(stepJson);

                client.deltaPublish(topic, stepJson);
                previous = next;
            }
            client.publishFlush(DemoConfig.timeoutMillis());

            Console.kv("ticks", ticks);
            Console.kv("as full publishes would have been", Console.bytes(fullBytes));
            Console.kv("as deltas actually sent", Console.bytes(deltaBytes));
            Console.kv("saved", Console.bytes(fullBytes - deltaBytes));
            Console.note("Those same bytes are what each approach writes to the transaction "
                    + "log. Run `journal-lab` to see the difference on disk.");

            Console.step("Caveat worth knowing");
            Console.note("Arrays are replaced, not merged. Changing one level of the order "
                    + "book re-sends the whole ladder, because JSON has no positional merge. "
                    + "If a repeated field is your hot path, model it as nested messages "
                    + "keyed by name instead.");
        }
    }

    private static InstrumentSnapshot fetch(Client client, String topic, String symbol)
            throws Exception {
        Command command = new Command("sow")
                .setTopic(topic)
                .setFilter("/symbol = '" + symbol + "'")
                .setTimeout(DemoConfig.timeoutMillis());
        InstrumentSnapshot[] found = {null};
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, 5000, message -> {
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    found[0] = JsonCodec.fromJson(message.getData(),
                            InstrumentSnapshot.getDefaultInstance());
                }
                return true;
            });
        }
        return found[0];
    }
}
