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
import com.demo.amps.common.DataDirs;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.Topics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Removing data from a topic at runtime with {@code sow_delete}.
 *
 * <p>Deliberately conservative by default: it only deletes the records it just
 * created, identified by a marker on the {@code desk} field. Pass {@code --all}
 * to purge the whole topic.
 */
public final class TruncateDemo implements Demo {

    private static final int MARKER_BASE = 80_000;
    private static final String MARKER_DESK = "truncate-demo";
    private static final String MARKER_FILTER = "/desk = '" + MARKER_DESK + "'";

    /**
     * A filter that matches every record, which is how a whole SOW topic is
     * purged. If your AMPS version rejects the constant form, an always-true
     * predicate over a field the records always carry works just as well
     * (for example {@code /orderId != ''}).
     */
    private static final String MATCH_EVERYTHING = "1=1";

    @Override
    public String name() {
        return "truncate";
    }

    @Override
    public String summary() {
        return "Delete SOW records at runtime with sow_delete, by filter and by SOW key";
    }

    @Override
    public String explanation() {
        return "Yes, a client can truncate a topic's data, and sow_delete is the command "
                + "that does it -- by content filter, by SOW key, or by matching data. It is "
                + "a durable server-side operation, not a client-side view: the records are "
                + "gone for every subscriber, and each one generates an OOF with reason "
                + "'deleted' so downstream views drop the rows. What it does NOT do is "
                + "shrink the transaction log. A delete is itself a write, journalled like "
                + "any other change, so purging a SOW makes the journal larger, not smaller. "
                + "The two stores are bounded by completely different mechanisms.";
    }

    @Override
    public void run(Args args) throws Exception {
        String topic = args.get("topic", Topics.ORDERS);
        int markers = args.getInt("count", 20);
        boolean purgeEverything = args.has("all");

        try (Client client = AmpsConnections.connect("demo-truncate");
             Client watcher = AmpsConnections.connect("demo-truncate-watch")) {

            // ---- watch for the OOF notifications the deletes will produce -----
            AtomicInteger oofDeleted = new AtomicInteger();
            CountDownLatch sawDeleteOof = new CountDownLatch(1);
            watcher.executeAsync(new Command("sow_and_subscribe")
                    .setTopic(topic)
                    .setFilter(MARKER_FILTER)
                    .setOptions(Message.Options.OOF + Message.Options.SendKeys)
                    .setTimeout(DemoConfig.timeoutMillis()), message -> {
                if (message.getCommand() == Message.Command.OOF
                        && message.getReason() == Message.Reason.Deleted) {
                    oofDeleted.incrementAndGet();
                    sawDeleteOof.countDown();
                }
            });
            Thread.sleep(400);

            // ---- seed ---------------------------------------------------------
            Console.step("Publishing " + markers + " marker records");
            for (int i = 0; i < markers; i++) {
                client.publish(topic, JsonCodec.toJson(
                        Fixtures.order(MARKER_BASE + i).toBuilder().setDesk(MARKER_DESK).build()));
            }
            client.publishFlush(DemoConfig.timeoutMillis());
            Console.kv("records in " + topic, count(client, topic, null));
            Console.kv("marker records", count(client, topic, MARKER_FILTER));

            long journalBefore = DataDirs.visible() ? DataDirs.sizeOf(DataDirs.journalDir()) : -1;
            long sowBefore = DataDirs.visible() ? DataDirs.sizeOf(DataDirs.sowDir()) : -1;

            // ---- 1. delete by filter -----------------------------------------
            Console.step("sow_delete by filter, keeping half");
            String halfFilter = MARKER_FILTER + " AND /quantity > 2500";
            Console.kv("filter", halfFilter);

            // The synchronous form returns the ack message, whose recordsDeleted
            // field is the server's own count -- not an estimate from the client.
            Message ack = client.sowDelete(topic, halfFilter, DemoConfig.timeoutMillis());
            Console.kv("records deleted (server count)", ack.getRecordsDeleted());
            Console.kv("markers remaining", count(client, topic, MARKER_FILTER));

            // ---- 2. delete by SOW key ----------------------------------------
            Console.step("sow_delete by SOW key");
            List<String> keys = sowKeys(client, topic, MARKER_FILTER, 3);
            if (keys.isEmpty()) {
                Console.note("Nothing left to delete by key.");
            } else {
                Console.kv("sow keys", String.join(",", keys));
                Console.note("These are the server-assigned identifiers, learned from a "
                        + "previous query. This is the form a GUI uses to delete the exact "
                        + "rows a user selected, with no filter to get wrong.");

                CountDownLatch done = new CountDownLatch(1);
                long[] deleted = {0};
                client.sowDeleteByKeys(message -> {
                    deleted[0] = message.getRecordsDeleted();
                    done.countDown();
                }, topic, String.join(",", keys), DemoConfig.timeoutMillis());
                done.await(10, TimeUnit.SECONDS);

                Console.kv("records deleted", deleted[0]);
                Console.kv("markers remaining", count(client, topic, MARKER_FILTER));
            }

            // ---- 3. purge the topic ------------------------------------------
            Console.step("Purging the whole topic");
            if (purgeEverything) {
                Console.note("--all was passed, so every record in '" + topic + "' goes, not "
                        + "just this demo's markers.");
                Message purge = client.sowDelete(topic, MATCH_EVERYTHING,
                        DemoConfig.timeoutMillis());
                Console.kv("records deleted", purge.getRecordsDeleted());
                Console.kv("records remaining", count(client, topic, null));
            } else {
                Console.note("Skipped -- this would delete every record in '" + topic + "', "
                        + "including whatever the other demos loaded. The command is a "
                        + "sow_delete with a filter that matches everything:");
                Console.info("");
                Console.info("      client.sowDelete(\"%s\", \"%s\", timeout);", topic,
                        MATCH_EVERYTHING);
                Console.info("");
                Console.note("Re-run with --all to actually do it.");
            }

            sawDeleteOof.await(5, TimeUnit.SECONDS);

            // ---- what it did and did not cost --------------------------------
            Console.step("What the deletes cost on disk");
            if (DataDirs.visible()) {
                long journalAfter = DataDirs.sizeOf(DataDirs.journalDir());
                long sowAfter = DataDirs.sizeOf(DataDirs.sowDir());
                Console.kv("journal before / after",
                        Console.bytes(journalBefore) + " -> " + Console.bytes(journalAfter));
                Console.kv("sow before / after",
                        Console.bytes(sowBefore) + " -> " + Console.bytes(sowAfter));
                Console.note("The journal did not shrink, and may have grown: a delete is a "
                        + "write. The SOW file does not shrink either -- the space is freed "
                        + "for reuse inside the file rather than returned to the filesystem. "
                        + "Deleting records reduces what a query returns and what memory the "
                        + "SOW needs; it is not a way to reclaim disk.");
            } else {
                Console.note("Data directory not visible, so on-disk figures are unavailable. "
                        + "Run from the repository root to see them.");
            }

            Console.kv("OOF 'deleted' notifications seen", oofDeleted.get());
            Console.note(oofDeleted.get() > 0
                    ? "Subscribers were told. A deleted record is not silently absent from "
                      + "the next query -- live views are corrected as it happens."
                    : "No OOF observed; the watcher may have missed the window.");

            Console.step("The other ways to remove data");
            Console.bullet("<Expiration> on the SOW topic -- a TTL, so records leave on their "
                    + "own. Cheaper than any delete because nothing has to run.");
            Console.bullet("Stop the instance and delete the SOW file, then restart. This is "
                    + "the only route that returns the file's disk to the filesystem.");
            Console.bullet("./server/scripts/amps.sh reset -- the demo's version of that, for "
                    + "SOW and journal together.");
            Console.note("None of these truncate the transaction log by topic. The journal is "
                    + "bounded by file retention, not by commands. See "
                    + "docs/src/transaction-log-sizing.md.");
        }
    }

    private static int count(Client client, String topic, String filter) throws Exception {
        Command command = new Command("sow")
                .setTopic(topic)
                .setBatchSize(500)
                .setTimeout(DemoConfig.timeoutMillis());
        if (filter != null) {
            command.setFilter(filter);
        }
        AtomicInteger records = new AtomicInteger();
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, 8000, message -> {
                if (message.getCommand() == Message.Command.SOW) {
                    records.incrementAndGet();
                }
                return true;
            });
        }
        return records.get();
    }

    /** Collects up to {@code limit} server-assigned SOW keys matching a filter. */
    private static List<String> sowKeys(Client client, String topic, String filter, int limit)
            throws Exception {
        List<String> keys = new ArrayList<>();
        Command command = new Command("sow")
                .setTopic(topic)
                .setFilter(filter)
                .setTopN(limit)
                .setTimeout(DemoConfig.timeoutMillis());
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, 5000, message -> {
                if (message.getCommand() == Message.Command.SOW && !message.isSowKeyNull()) {
                    keys.add(message.getSowKey());
                }
                return keys.size() < limit;
            });
        }
        return keys;
    }
}
