package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.MessageStreams;
import com.demo.amps.common.Topics;
import com.demo.amps.common.fix.FixWire;
import com.demo.amps.common.fix.OrderLifecycleSimulator;
import com.demo.amps.market.v1.OrderEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The native FIX and NVFIX message types, end to end: topics declared with
 * {@code MessageType fix}/{@code nvfix}, a simulator publishing raw
 * 35=D/G/F/8/9 payloads, a subscriber receiving them live, SOW queries keyed
 * and filtered on FIX fields, and a journalled event stream behind it all.
 *
 * <p>One class serves both encodings; the two registrations differ only in the
 * {@link Encoding} handed to the constructor, which is itself the point: the
 * pattern is identical, only the field naming changes.
 */
public final class NativeFixDemo implements Demo {

    /** Everything that differs between the two native encodings. */
    public enum Encoding {
        FIX("fix", Topics.FIX_NATIVE_EVENTS, Topics.FIX_NATIVE_ORDERS,
                "/37", "/39", "/11", "/41") {
            @Override
            String encode(OrderEvent event) {
                return FixWire.toFix(event);
            }

            @Override
            Map<String, String> decode(String payload) {
                Map<Integer, String> tags = FixWire.parseFix(payload);
                java.util.LinkedHashMap<String, String> byName = new java.util.LinkedHashMap<>();
                tags.forEach((tag, value) -> byName.put(FixWire.nameOf(tag), value));
                return byName;
            }
        },
        NVFIX("nvfix", Topics.NVFIX_NATIVE_EVENTS, Topics.NVFIX_NATIVE_ORDERS,
                "/OrderID", "/OrdStatus", "/ClOrdID", "/OrigClOrdID") {
            @Override
            String encode(OrderEvent event) {
                return FixWire.toNvfix(event);
            }

            @Override
            Map<String, String> decode(String payload) {
                return FixWire.parseNvfix(payload);
            }
        };

        final String messageType;
        final String eventsTopic;
        final String ordersTopic;
        final String orderIdRef;
        final String ordStatusRef;
        final String clOrdIdRef;
        final String origClOrdIdRef;

        Encoding(String messageType, String eventsTopic, String ordersTopic,
                 String orderIdRef, String ordStatusRef, String clOrdIdRef, String origClOrdIdRef) {
            this.messageType = messageType;
            this.eventsTopic = eventsTopic;
            this.ordersTopic = ordersTopic;
            this.orderIdRef = orderIdRef;
            this.ordStatusRef = ordStatusRef;
            this.clOrdIdRef = clOrdIdRef;
            this.origClOrdIdRef = origClOrdIdRef;
        }

        abstract String encode(OrderEvent event);

        abstract Map<String, String> decode(String payload);
    }

    private final Encoding encoding;

    public NativeFixDemo(Encoding encoding) {
        this.encoding = encoding;
    }

    @Override
    public String name() {
        return encoding.messageType + "-native";
    }

    @Override
    public String summary() {
        return "Raw " + encoding.messageType.toUpperCase(java.util.Locale.ROOT)
                + " on the wire: MessageType " + encoding.messageType
                + " topics, keys and filters on " + (encoding == Encoding.FIX
                ? "tag numbers" : "tag names");
    }

    @Override
    public String explanation() {
        return "Everything else in this repo normalizes to JSON; here the payload IS the "
                + "FIX wire -- SOH-separated " + (encoding == Encoding.FIX
                        ? "tag=value pairs (35=8, 11=A1, ...), and the server references "
                          + "fields by tag number: the orders SOW is keyed <Key>/37</Key> "
                          + "and a filter reads /39 = '2'."
                        : "name=value pairs (MsgType=8, ClOrdID=A1, ...), and the server "
                          + "references fields by name: <Key>/OrderID</Key>, filter "
                          + "/OrdStatus = '2'.")
                + " AMPS parses the format natively, so SOW, filters, journal and bookmark "
                + "replay all work without a translation layer. The simulator publishes "
                + "four scripted order lifecycles (35=D/G/F/8/9); every message lands in "
                + "the journalled events topic, and execution reports also land in the "
                + "orders SOW, whose last-record-per-OrderID is current state because FIX "
                + "4.2 reports are cumulative snapshots. Session-level tags (8/9/10/34) are "
                + "absent by design: AMPS is not a FIX session engine, and durability and "
                + "sequencing come from the transaction log instead.";
    }

    @Override
    public void run(Args args) throws Exception {
        try (Client publisher = AmpsConnections.connect(
                        "demo-" + encoding.messageType + "-pub", encoding.messageType);
             Client subscriber = AmpsConnections.connect(
                        "demo-" + encoding.messageType + "-sub", encoding.messageType)) {

            // ---- subscriber first, so the live flow is visible ----------------
            Console.step("Subscriber: sow_and_subscribe on " + encoding.ordersTopic);
            AtomicInteger liveReports = new AtomicInteger();
            Command follow = new Command("sow_and_subscribe")
                    .setTopic(encoding.ordersTopic)
                    .setOptions(Message.Options.SendKeys)
                    .setTimeout(DemoConfig.timeoutMillis());
            subscriber.executeAsync(follow, message -> {
                if (message.getCommand() == Message.Command.Publish && !message.isDataNull()) {
                    liveReports.incrementAndGet();
                    Map<String, String> fields = encoding.decode(message.getData());
                    Console.info("   live <- %-8s 39=%s cum=%s leaves=%s avg=%s",
                            fields.getOrDefault("OrderID", "?"),
                            fields.getOrDefault("OrdStatus", "?"),
                            fields.getOrDefault("CumQty", "?"),
                            fields.getOrDefault("LeavesQty", "?"),
                            fields.getOrDefault("AvgPx", "?"));
                }
            });
            Thread.sleep(400);

            // ---- publisher: the simulator's four lifecycles -------------------
            List<OrderEvent> script = OrderLifecycleSimulator.fullScript();
            Console.step("Publisher: " + script.size() + " raw "
                    + encoding.messageType.toUpperCase(java.util.Locale.ROOT) + " messages");
            Console.note("Every message goes to the journalled events topic. Execution "
                    + "reports additionally go to the orders SOW -- requests carry no "
                    + "OrderID, and a SOW publish without its key field fails, which is a "
                    + "feature: the topic's key declares what belongs in it.");
            Console.info("");

            long bytes = 0;
            for (OrderEvent event : script) {
                String payload = encoding.encode(event);
                bytes += payload.getBytes(StandardCharsets.UTF_8).length;
                publisher.publish(encoding.eventsTopic, payload);
                if ("8".equals(event.getMsgType())) {
                    publisher.publish(encoding.ordersTopic, payload);
                }
                if (event.getSeq() <= 3) {
                    Console.info("   out -> %s", FixWire.printable(payload));
                }
            }
            publisher.publishFlush(DemoConfig.timeoutMillis());
            Console.info("   ... (%d messages, %s total)", script.size(), Console.bytes(bytes));
            Thread.sleep(700);

            // ---- SOW: latest state per order, straight off the wire -----------
            Console.step("SOW query: latest report per OrderID (key " + encoding.orderIdRef + ")");
            AtomicInteger orders = new AtomicInteger();
            query(publisher, new Command("sow")
                    .setTopic(encoding.ordersTopic)
                    .setTimeout(DemoConfig.timeoutMillis()), message -> {
                orders.incrementAndGet();
                Map<String, String> fields = encoding.decode(message.getData());
                Console.info("   %-8s %-6s 39=%-2s 38=%-5s 14=%-4s 151=%-4s 6=%s",
                        fields.getOrDefault("OrderID", "?"),
                        fields.getOrDefault("Symbol", "?"),
                        fields.getOrDefault("OrdStatus", "?"),
                        fields.getOrDefault("OrderQty", "?"),
                        fields.getOrDefault("CumQty", "?"),
                        fields.getOrDefault("LeavesQty", "?"),
                        fields.getOrDefault("AvgPx", "?"));
                return true;
            });
            Console.kv("orders in the SOW", orders.get());
            Console.note("One record per order, and it is the venue's own latest snapshot "
                    + "-- OrdStatus, CumQty, LeavesQty, AvgPx -- with no state machine and "
                    + "no JSON anywhere.");

            // ---- content filters on FIX fields --------------------------------
            Console.step("Content filters evaluate against " + (encoding == Encoding.FIX
                    ? "tag numbers" : "tag names"));
            Console.kv("filter", encoding.ordStatusRef + " = '2'");
            AtomicInteger filled = new AtomicInteger();
            query(publisher, new Command("sow")
                    .setTopic(encoding.ordersTopic)
                    .setFilter(encoding.ordStatusRef + " = '2'")
                    .setTimeout(DemoConfig.timeoutMillis()), message -> {
                filled.incrementAndGet();
                return true;
            });
            Console.kv("filled orders", filled.get());

            String historyFilter = encoding.clOrdIdRef + " = 'A1' OR "
                    + encoding.origClOrdIdRef + " = 'A1'";
            Console.kv("history filter", historyFilter);
            AtomicInteger history = new AtomicInteger();
            query(publisher, new Command("sow")
                    .setTopic(encoding.eventsTopic)
                    .setFilter(historyFilter)
                    .setBatchSize(100)
                    .setTimeout(DemoConfig.timeoutMillis()), message -> {
                history.incrementAndGet();
                return true;
            });
            Console.kv("events touching chain A1", history.get());
            Console.note("The events topic is journalled, so beyond these SOW queries a "
                    + "bookmark subscription can replay the whole stream -- the same "
                    + "recovery story as every JSON topic in this repo, unchanged by the "
                    + "message type.");

            Console.kv("live reports seen by subscriber", liveReports.get());

            Console.step("What native buys, and what it costs");
            Console.bullet("no translation layer: the gateway forwards wire payloads as-is");
            Console.bullet("smaller than JSON for the same fields (no quotes, no braces, "
                    + "short names" + (encoding == Encoding.FIX ? " -- numeric tags" : "") + ")");
            Console.bullet("costs: records are not human-readable, none of the protobuf "
                    + "schema tooling applies, and delta publishing needs flat "
                    + "tag-per-field records -- there is no nested merge here");
            Console.note("The comparison and the verify-on-your-build notes are in "
                    + "docs/src/native-fix-and-nvfix.md.");
        }
    }

    private static void query(Client client, Command command,
                              Function<Message, Boolean> onRecord) throws Exception {
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, 5000, message -> {
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    return onRecord.apply(message);
                }
                return true;
            });
        }
    }
}
