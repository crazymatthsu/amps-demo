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
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.MessageStreams;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.Order;
import java.util.ArrayList;
import java.util.List;

/** Point-in-time SOW queries: the snapshot half of AMPS. */
public final class SowQueryDemo implements Demo {

    @Override
    public String name() {
        return "sow-query";
    }

    @Override
    public String summary() {
        return "Snapshot a SOW topic with a server-side content filter, ordering and top-N";
    }

    @Override
    public String explanation() {
        return "A `sow` command is a query against current state, answered from the SOW and "
                + "then finished -- the stream ends on its own. The filter is a content "
                + "expression evaluated by the server against the JSON payload, so only "
                + "matching records cross the network. This is the feature with no Kafka "
                + "equivalent: there is no consumer group to drain, no changelog to replay "
                + "and no state store to build. The filter reads the same field names the "
                + "protobuf schema generates.";
    }

    @Override
    public void run(Args args) throws Exception {
        String topic = args.get("topic", Topics.ORDERS);
        String filter = args.get("filter", "/quantity > 2000 AND /side = 'SIDE_BUY'");
        String orderBy = args.get("orderBy", "/price DESC");
        int topN = args.getInt("topN", 5);

        try (Client client = AmpsConnections.connect("demo-sow-query")) {

            Console.step("Unfiltered count");
            Console.kv("records in " + topic, count(client, topic, null));

            Console.step("Filtered query");
            Console.kv("filter", filter);
            Console.note("Evaluated by the server against the JSON payload. Note that "
                    + "/quantity is compared numerically because the schema declares it "
                    + "int32; had it been int64, protobuf JSON would have written it as a "
                    + "quoted string and this comparison would silently do the wrong thing.");
            Console.kv("matching records", count(client, topic, filter));

            Console.step("Filtered, ordered, top " + topN);
            Command command = new Command("sow")
                    .setTopic(topic)
                    .setFilter(filter)
                    .setOrderBy(orderBy)
                    .setTopN(topN)
                    .setBatchSize(100)
                    .setTimeout(DemoConfig.timeoutMillis());

            List<Order> results = new ArrayList<>();
            try (MessageStream stream = client.execute(command)) {
                MessageStreams.forEach(stream, 5000, message -> {
                    if (Messages.isGroupMarker(message)) {
                        Console.info("   %s", Messages.commandName(message.getCommand()));
                        return true;
                    }
                    if (message.getCommand() == Message.Command.SOW) {
                        results.add(JsonCodec.fromJson(message.getData(), Order.getDefaultInstance()));
                    }
                    return true;
                });
            }

            Console.info("");
            Console.info("   %-12s %-8s %-6s %10s %10s  %s",
                    "orderId", "symbol", "side", "quantity", "price", "sowkey-derived-from");
            for (Order order : results) {
                Console.info("   %-12s %-8s %-6s %10d %10.2f  /orderId",
                        order.getOrderId(), order.getSymbol(),
                        order.getSide().name().replace("SIDE_", ""),
                        order.getQuantity(), order.getPrice());
            }

            Console.step("What the group markers mean");
            Console.note("AMPS brackets a query result with group_begin and group_end. They "
                    + "are how a client knows the snapshot is complete -- which is exactly "
                    + "what sow-and-subscribe relies on to hand over from snapshot to live "
                    + "stream without a gap or a duplicate.");

            Console.step("Projection: ask for fewer fields");
            Console.note("The server can project a subset of the record, so a wide record "
                    + "costs only what the client actually reads. This uses the same field "
                    + "paths as the filter.");
            Command projected = new Command("sow")
                    .setTopic(topic)
                    .setFilter(filter)
                    .setTopN(3)
                    .setOptions(Message.Options.Projection("/orderId, /symbol, /price")
                            + Message.Options.Grouping("/symbol"))
                    .setTimeout(DemoConfig.timeoutMillis());
            try (MessageStream stream = client.execute(projected)) {
                MessageStreams.forEach(stream, 5000, message -> {
                    if (!Messages.isGroupMarker(message) && !message.isDataNull()) {
                        Console.info("   %s", Messages.withSize(message));
                    }
                    return true;
                });
            } catch (Exception e) {
                Console.kv("projection", "not available on this build: " + e.getMessage());
            }
        }
    }

    /** Runs a query purely to count what matches. */
    private static int count(Client client, String topic, String filter) throws Exception {
        Command command = new Command("sow")
                .setTopic(topic)
                .setBatchSize(500)
                .setTimeout(DemoConfig.timeoutMillis());
        if (filter != null) {
            command.setFilter(filter);
        }
        int[] records = {0};
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, 5000, message -> {
                if (message.getCommand() == Message.Command.SOW) {
                    records[0]++;
                }
                return true;
            });
        }
        return records[0];
    }
}
