package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.MessageStreams;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.Order;
import java.util.concurrent.atomic.AtomicInteger;

/** Replaying history out of the transaction log with bookmark subscriptions. */
public final class BookmarkReplayDemo implements Demo {

    @Override
    public String name() {
        return "bookmark-replay";
    }

    @Override
    public String summary() {
        return "Replay a transaction-logged topic from the beginning, from a bookmark, "
                + "or from where this client left off";
    }

    @Override
    public String explanation() {
        return "A topic listed in <TransactionLog> keeps every message in an ordered "
                + "journal, and any subscription can start from a position in it. That is "
                + "the AMPS analogue of a Kafka offset -- with one important difference: the "
                + "position is per subscriber and stored by the client, not a broker-side "
                + "consumer-group offset, so nothing needs to coordinate. This demo replays "
                + "from the epoch, from a specific bookmark, and then demonstrates resuming: "
                + "a client with a bookmark store picks up exactly where it stopped, even "
                + "across a process restart.";
    }

    @Override
    public void run(Args args) throws Exception {
        String topic = args.get("topic", Topics.ORDERS);
        int publishCount = args.getInt("count", 5);

        // ---- publish a few known messages so there is something to replay ------
        try (Client publisher = AmpsConnections.connect("demo-replay-pub")) {
            Console.step("Publishing " + publishCount + " orders to the journalled topic");
            for (int i = 0; i < publishCount; i++) {
                Order order = Fixtures.order(50_000 + i);
                publisher.publish(topic, JsonCodec.toJson(order));
            }
            publisher.publishFlush(DemoConfig.timeoutMillis());
            Console.kv("published", publishCount);
        }

        // ---- 1. replay everything from the start of the journal ----------------
        Console.step("Replay from the epoch (bookmark \"" + Client.Bookmarks.EPOCH + "\")");
        Console.note("A subscription with a bookmark is served from the transaction log "
                + "first, then transitions to live without a gap. The client here has "
                + "never seen any of this data.");

        String firstBookmark;
        int replayed;
        try (Client client = AmpsConnections.connect("demo-replay-epoch")) {
            Command command = new Command("subscribe")
                    .setTopic(topic)
                    .setBookmark(Client.Bookmarks.EPOCH)
                    .setTimeout(DemoConfig.timeoutMillis());

            AtomicInteger count = new AtomicInteger();
            String[] bookmarkAt10 = {null};
            try (MessageStream stream = client.execute(command)) {
                MessageStreams.forEach(stream, 4000, message -> {
                    if (message.isDataNull()) {
                        return true;
                    }
                    int seen = count.incrementAndGet();
                    if (seen == 10) {
                        bookmarkAt10[0] = message.getBookmark();
                    }
                    if (seen <= 3) {
                        Console.info("   %3d. bookmark=%s", seen, message.getBookmark());
                    }
                    return true;
                });
            }
            replayed = count.get();
            firstBookmark = bookmarkAt10[0];
            Console.kv("messages replayed", replayed);
            Console.note(replayed > publishCount
                    ? "More than we just published: the journal still holds everything from "
                      + "earlier demo runs. That history is what survives a restart."
                    : "Only the messages from this run -- the journal was empty or reset.");
        }

        // ---- 2. replay from a specific bookmark -------------------------------
        if (firstBookmark != null) {
            Console.step("Replay from a specific bookmark");
            Console.kv("bookmark", firstBookmark);
            Console.note("Bookmarks are opaque, ordered identifiers issued by the server. "
                    + "Keep one and you can ask for everything after it, at any time, from "
                    + "any client.");

            try (Client client = AmpsConnections.connect("demo-replay-bookmark")) {
                Command command = new Command("subscribe")
                        .setTopic(topic)
                        .setBookmark(firstBookmark)
                        .setTimeout(DemoConfig.timeoutMillis());
                AtomicInteger count = new AtomicInteger();
                try (MessageStream stream = client.execute(command)) {
                    MessageStreams.forEach(stream, 4000, message -> {
                        if (!message.isDataNull()) {
                            count.incrementAndGet();
                        }
                        return true;
                    });
                }
                Console.kv("messages from that point", count.get());
                Console.kv("messages skipped", Math.max(0, replayed - count.get()));
            }
        }

        // ---- 3. resume where this named client left off ------------------------
        Console.step("Resuming with a durable bookmark store");
        Console.note("This client keeps its position in a file under "
                + DemoConfig.clientStateDir() + ". The first run replays from the epoch; "
                + "every later run asks for MOST_RECENT and receives only what it has not "
                + "already acknowledged -- including messages published while it was down. "
                + "Run this demo twice to see the second number drop.");

        String clientName = "demo-replay-resumable";
        try (HAClient client = AmpsConnections.connectWithBookmarkStore(clientName)) {
            Command command = new Command("subscribe")
                    .setTopic(topic)
                    .setSubId(clientName + "-sub")
                    .setBookmark(Client.Bookmarks.MOST_RECENT)
                    .setTimeout(DemoConfig.timeoutMillis());

            AtomicInteger count = new AtomicInteger();
            try (MessageStream stream = client.execute(command)) {
                MessageStreams.forEach(stream, 4000, message -> {
                    if (message.isDataNull()) {
                        return true;
                    }
                    count.incrementAndGet();
                    try {
                        // Discarding is what advances the stored position. Do it after
                        // the message is safely processed, not before -- this is the
                        // at-least-once boundary.
                        client.getBookmarkStore().discard(message);
                    } catch (Exception e) {
                        Console.kv("discard failed", e.getMessage());
                    }
                    return true;
                });
            }
            Console.kv("messages delivered to this run", count.get());
            Console.note("Run `bookmark-replay` again: this number should be roughly the "
                    + "count published in the meantime, not the whole journal.");
        }

        Console.step("Where this differs from Kafka");
        Console.note("Kafka stores the consumer-group offset on the broker and rebalances "
                + "partitions between members. AMPS puts the bookmark in the subscriber's "
                + "own store, so there is no group membership to negotiate and two clients "
                + "reading the same topic never interfere. The trade-off is symmetric: "
                + "AMPS gives no automatic work-sharing across a consumer group unless you "
                + "use a queue topic, which is a separate feature.");
    }
}
