package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DataDirs;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.MessageStreams;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.Order;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Restart survival: what comes back from the SOW, and what comes back from the
 * transaction log.
 */
public final class RecoveryDemo implements Demo {

    private static final int MARKER_BASE = 70_000;
    private static final String MARKER_DESK = "recovery-marker";
    private static final String MARKER_FILTER = "/desk = '" + MARKER_DESK + "'";

    @Override
    public String name() {
        return "recovery";
    }

    @Override
    public String summary() {
        return "Prove that SOW state and journal history survive an instance restart";
    }

    @Override
    public String explanation() {
        return "AMPS recovers from two independent things, and it is worth being precise "
                + "about which does what. The SOW gives back CURRENT STATE immediately -- one "
                + "record per key, no replay, no warm-up, however long the instance was "
                + "down. The transaction log gives back HISTORY, so a subscriber can resume "
                + "from its bookmark and receive everything it missed. A restart that keeps "
                + "both is a restart nobody downstream has to care about. Run this in two "
                + "phases with a server restart in between.";
    }

    @Override
    public void run(Args args) throws Exception {
        String phase = args.get("phase", args.positional(0, "snapshot"));
        switch (phase) {
            case "snapshot" -> snapshot(args);
            case "verify" -> verify(args);
            default -> {
                Console.note("Unknown phase '" + phase + "'. Use --phase snapshot or "
                        + "--phase verify.");
                throw new IllegalArgumentException("unknown phase: " + phase);
            }
        }
    }

    // ---------------------------------------------------------------- phase 1

    private void snapshot(Args args) throws Exception {
        int markers = args.getInt("count", 25);
        String topic = args.get("topic", Topics.ORDERS);

        try (Client client = AmpsConnections.connect("demo-recovery-snapshot")) {

            Console.step("Publishing " + markers + " marker orders");
            // Marked with a scalar field rather than a tag: exact string equality on
            // a top-level member is the least surprising filter expression there is,
            // and these records need to be findable again after the restart.
            for (int i = 0; i < markers; i++) {
                Order order = Fixtures.order(MARKER_BASE + i).toBuilder()
                        .setDesk(MARKER_DESK)
                        .build();
                client.publish(topic, JsonCodec.toJson(order));
            }
            client.publishFlush(DemoConfig.timeoutMillis());

            int sowRecords = countSow(client, topic, null);
            int markerRecords = countSow(client, topic, MARKER_FILTER);
            String lastBookmark = lastBookmark(client, topic);

            Console.step("State before the restart");
            Console.kv("SOW records in " + topic, sowRecords);
            Console.kv("marker records", markerRecords);
            Console.kv("last bookmark", lastBookmark == null ? "(none)" : lastBookmark);
            if (DataDirs.visible()) {
                Console.kv("SOW files on disk", Console.bytes(DataDirs.sizeOf(DataDirs.sowDir())));
                Console.kv("journal on disk", Console.bytes(DataDirs.sizeOf(DataDirs.journalDir())));
            }

            Properties state = new Properties();
            state.setProperty("topic", topic);
            state.setProperty("sowRecords", Integer.toString(sowRecords));
            state.setProperty("markerRecords", Integer.toString(markerRecords));
            state.setProperty("lastBookmark", lastBookmark == null ? "" : lastBookmark);
            writeState(state);

            Console.step("Now restart the instance");
            Console.info("");
            Console.info("      ./server/scripts/amps.sh restart");
            Console.info("      ./gradlew :clients:run --args=\"recovery --phase verify\"");
            Console.info("");
            Console.note("For a harsher test, kill the container instead of stopping it "
                    + "cleanly -- `podman kill amps-demo` followed by a start. The SOW is "
                    + "reconciled against the transaction log on the way back up, which is "
                    + "what the journal is there for.");
        }
    }

    // ---------------------------------------------------------------- phase 2

    private void verify(Args args) throws Exception {
        Properties state = readState();
        if (state == null) {
            Console.note("No saved state at " + stateFile() + ". Run `recovery --phase "
                    + "snapshot` first, restart the server, then run this.");
            return;
        }

        String topic = state.getProperty("topic", Topics.ORDERS);
        int expectedSow = Integer.parseInt(state.getProperty("sowRecords", "0"));
        int expectedMarkers = Integer.parseInt(state.getProperty("markerRecords", "0"));
        String bookmark = state.getProperty("lastBookmark", "");

        try (Client client = AmpsConnections.connect("demo-recovery-verify")) {

            Console.step("SOW recovery: current state, available immediately");
            int sowRecords = countSow(client, topic, null);
            int markerRecords = countSow(client, topic, MARKER_FILTER);

            Console.kv("records before restart", expectedSow);
            Console.kv("records after restart", sowRecords);
            Console.kv("markers before / after", expectedMarkers + " / " + markerRecords);
            Console.kv("SOW intact", sowRecords >= expectedSow && markerRecords >= expectedMarkers);
            Console.note("No replay was involved. AMPS did not rebuild this from a log on "
                    + "startup -- the SOW is a persistent keyed store, so it is queryable as "
                    + "soon as the instance is listening. That property is what makes AMPS "
                    + "restarts cheap regardless of how much history exists.");

            Console.step("Transaction log recovery: history, replayable");
            int fromEpoch = replayCount(client, topic, Client.Bookmarks.EPOCH);
            Console.kv("messages replayable from epoch", fromEpoch);

            if (!bookmark.isEmpty()) {
                int sinceBookmark = replayCount(client, topic, bookmark);
                Console.kv("messages after the pre-restart bookmark", sinceBookmark);
                Console.note("A subscriber holding that bookmark resumes here and misses "
                        + "nothing, whether it was down for a second or a week -- as long as "
                        + "the journal still covers the bookmark. That window is a retention "
                        + "decision: see docs/transaction-log-sizing.md.");
            }

            Console.step("The two halves, side by side");
            Console.info("   %-22s %-14s %s", "", "SOW", "transaction log");
            Console.info("   %-22s %-14s %s", "answers", "what is true now", "what happened");
            Console.info("   %-22s %-14s %s", "size grows with", "key count", "message count");
            Console.info("   %-22s %-14s %s", "restart cost", "none", "replay if requested");
            Console.info("   %-22s %-14s %s", "records here", sowRecords, fromEpoch);

            if (DataDirs.visible()) {
                Console.step("On disk");
                Console.kv("sow/", Console.bytes(DataDirs.sizeOf(DataDirs.sowDir()))
                        + " in " + DataDirs.fileCount(DataDirs.sowDir()) + " files");
                Console.kv("journal/", Console.bytes(DataDirs.sizeOf(DataDirs.journalDir()))
                        + " in " + DataDirs.fileCount(DataDirs.journalDir()) + " files");
                Console.note("The SOW is bounded by how many distinct keys exist. The journal "
                        + "is bounded by how many messages were published and how long you "
                        + "keep them -- which is the number you actually have to manage.");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int countSow(Client client, String topic, String filter) throws Exception {
        Command command = new Command("sow")
                .setTopic(topic)
                .setBatchSize(500)
                .setTimeout(DemoConfig.timeoutMillis());
        if (filter != null) {
            command.setFilter(filter);
        }
        AtomicInteger count = new AtomicInteger();
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, 8000, message -> {
                if (message.getCommand() == Message.Command.SOW) {
                    count.incrementAndGet();
                }
                return true;
            });
        }
        return count.get();
    }

    private static String lastBookmark(Client client, String topic) throws Exception {
        Command command = new Command("subscribe")
                .setTopic(topic)
                .setBookmark(Client.Bookmarks.EPOCH)
                .setTimeout(DemoConfig.timeoutMillis());
        String[] last = {null};
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, 4000, message -> {
                if (!message.isBookmarkNull()) {
                    last[0] = message.getBookmark();
                }
                return true;
            });
        }
        return last[0];
    }

    private static int replayCount(Client client, String topic, String bookmark) throws Exception {
        Command command = new Command("subscribe")
                .setTopic(topic)
                .setBookmark(bookmark)
                .setTimeout(DemoConfig.timeoutMillis());
        AtomicInteger count = new AtomicInteger();
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, 5000, message -> {
                if (!message.isDataNull()) {
                    count.incrementAndGet();
                }
                return true;
            });
        }
        return count.get();
    }

    private static Path stateFile() {
        return DemoConfig.clientStateDir().resolve("recovery-state.properties");
    }

    private static void writeState(Properties state) throws IOException {
        Files.createDirectories(stateFile().getParent());
        try (OutputStream out = Files.newOutputStream(stateFile())) {
            state.store(out, "captured before the restart by the recovery demo");
        }
        Console.kv("state saved to", stateFile());
    }

    private static Properties readState() throws IOException {
        if (!Files.isRegularFile(stateFile())) {
            return null;
        }
        Properties state = new Properties();
        try (InputStream in = Files.newInputStream(stateFile())) {
            state.load(in);
        }
        return state;
    }
}
