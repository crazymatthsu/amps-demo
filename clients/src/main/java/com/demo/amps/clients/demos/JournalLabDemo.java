package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DataDirs;
import com.demo.amps.common.DeltaBuilder;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.InstrumentSnapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Measures what full publishes and delta publishes actually cost in the
 * transaction log, on disk, for the same logical updates.
 */
public final class JournalLabDemo implements Demo {

    private static final List<String> SYMBOL_KEY = List.of("symbol");

    @Override
    public String name() {
        return "journal-lab";
    }

    @Override
    public String summary() {
        return "Measure transaction-log growth: whole records vs deltas, for identical updates";
    }

    @Override
    public String explanation() {
        return "The headline question for SOW topics carrying large, repetitive records: "
                + "how do I stop the transaction log from being dominated by data that "
                + "never changes? This runs the experiment. The same instruments receive "
                + "the same number of quote updates twice -- once as full snapshots, once "
                + "as deltas -- and both the bytes sent and the bytes that landed in "
                + "./server/data/journal are reported. Journal files are preallocated to "
                + "MinJournalSize, so on-disk growth moves in steps rather than smoothly; "
                + "the bytes-published figure is the exact one, and the file count is what "
                + "shows the journal rolling over.";
    }

    @Override
    public void run(Args args) throws Exception {
        int symbols = args.getInt("symbols", 10);
        int updates = args.getInt("updates", 500);

        if (!DataDirs.visible()) {
            Console.note("Cannot see the AMPS data directory at " + DemoConfig.dataDir()
                    + ", so on-disk numbers will be unavailable. Run this demo from the "
                    + "repository root, or set AMPS_DATA_DIR to the bind-mounted path. The "
                    + "bytes-published comparison below is still meaningful.");
        }

        Console.step("Experiment");
        Console.kv("symbols", symbols);
        Console.kv("updates per symbol", updates);
        Console.kv("total updates per phase", (long) symbols * updates);
        Console.kv("journal directory", DataDirs.journalDir());

        Map<String, Result> results = new LinkedHashMap<>();

        try (Client client = AmpsConnections.connect("demo-journal-lab")) {
            results.put("full publish", runPhase(client, Topics.BENCH_FULL, symbols, updates, false));
            results.put("delta publish", runPhase(client, Topics.BENCH_DELTA, symbols, updates, true));
        }

        Console.step("Results");
        Console.info("   %-16s %14s %14s %10s", "phase", "bytes sent", "journal grew", "files");
        for (Map.Entry<String, Result> entry : results.entrySet()) {
            Result result = entry.getValue();
            Console.info("   %-16s %14s %14s %10d",
                    entry.getKey(),
                    Console.bytes(result.bytesPublished()),
                    DataDirs.visible() ? Console.bytes(result.journalGrowth()) : "n/a",
                    result.journalFilesAfter());
        }

        Result full = results.get("full publish");
        Result delta = results.get("delta publish");
        if (full != null && delta != null && delta.bytesPublished() > 0) {
            Console.info("");
            Console.kv("payload reduction", String.format(Locale.ROOT, "%.1fx",
                    (double) full.bytesPublished() / delta.bytesPublished()));
            // Only a ratio of two real measurements means anything. Journal
            // growth is quantised to whole preallocated files, so whenever a
            // phase happens to fit inside space that already existed its growth
            // reads as zero -- and dividing by that produces "0.0x", which looks
            // like deltas cost more when it only means the ruler is too coarse.
            if (DataDirs.visible()) {
                if (full.journalGrowth() > 0 && delta.journalGrowth() > 0) {
                    Console.kv("on-disk reduction", String.format(Locale.ROOT, "%.1fx",
                            (double) full.journalGrowth() / delta.journalGrowth()));
                } else {
                    Console.kv("on-disk reduction", "not measurable in this run");
                    Console.note("One phase grew the journal by zero bytes: it fit inside "
                            + "already-preallocated space. Growth here moves in whole "
                            + "MinJournalSize files, so it is too coarse to compare two "
                            + "phases of this size -- 'bytes sent' above is the exact "
                            + "figure, and it is what the journal has to record. Raise "
                            + "--updates until both phases cross a file boundary if you "
                            + "want the on-disk ratio too.");
                }
            }
        }

        Console.step("Reading the numbers");
        Console.note("Bytes sent is exact and is what the journal has to record. On-disk "
                + "growth lags it in steps because AMPS preallocates journal files at "
                + "MinJournalSize (10MB in the demo config -- AMPS's minimum); if the delta "
                + "phase fits inside "
                + "an already-allocated file, its on-disk growth can read as zero. That is "
                + "the point rather than a measurement error -- the delta workload did not "
                + "need another file.");

        if (DataDirs.visible()) {
            Console.step("Journal files now");
            List<String> listing = DataDirs.listing(DataDirs.journalDir());
            listing.stream().limit(12).forEach(line -> Console.info("   " + line));
            if (listing.size() > 12) {
                Console.info("   ... and %d more", listing.size() - 12);
            }
        }

        Console.step("The four levers, in order of effect");
        Console.bullet("Do not journal topics you never need to replay. quote-cache is "
                + "absent from <TransactionLog> and costs nothing.");
        Console.bullet("Publish deltas. Same updates, an order of magnitude less journal, "
                + "as measured above.");
        Console.bullet("Age out old journal files. The SOW already holds current state, so "
                + "the journal only needs to span your worst-case consumer outage.");
        Console.bullet("Bound the SOW itself with <Expiration> so the key space does not "
                + "grow forever. See the `expiration` demo.");
        Console.note("Details and the configuration for each in docs/transaction-log-sizing.md.");
    }

    private Result runPhase(Client client, String topic, int symbols, int updates, boolean useDeltas)
            throws Exception {

        Console.step((useDeltas ? "Delta" : "Full") + " publishes to " + topic);

        Path journal = DataDirs.journalDir();
        long journalBefore = DataDirs.sizeOf(journal);

        List<InstrumentSnapshot> current = new ArrayList<>(symbols);
        long bytes = 0;

        // Seed: both phases start from a full record, because a delta has nothing
        // to merge into until the record exists.
        for (int i = 0; i < symbols; i++) {
            String symbol = "LAB" + i;
            InstrumentSnapshot snapshot = Fixtures.instrument(symbol);
            String json = JsonCodec.toJson(snapshot);
            bytes += JsonCodec.byteSize(json);
            client.publish(topic, json);
            current.add(snapshot);
        }
        client.publishFlush(DemoConfig.timeoutMillis());

        Random random = new Random(99);
        long started = System.nanoTime();

        for (int round = 0; round < updates; round++) {
            for (int i = 0; i < symbols; i++) {
                InstrumentSnapshot previous = current.get(i);
                InstrumentSnapshot next = Fixtures.tick(previous, random);

                if (useDeltas) {
                    String json = DeltaBuilder.between(previous, next, SYMBOL_KEY).json();
                    bytes += JsonCodec.byteSize(json);
                    client.deltaPublish(topic, json);
                } else {
                    String json = JsonCodec.toJson(next);
                    bytes += JsonCodec.byteSize(json);
                    client.publish(topic, json);
                }
                current.set(i, next);
            }
        }
        client.publishFlush(60_000);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        // Give the server a moment to finish writing before measuring.
        waitForQuietJournal(journal);

        long journalAfter = DataDirs.sizeOf(journal);
        Console.kv("bytes published", Console.bytes(bytes));
        Console.kv("elapsed", elapsedMillis + " ms");
        if (DataDirs.visible()) {
            Console.kv("journal before / after",
                    Console.bytes(journalBefore) + " -> " + Console.bytes(journalAfter));
        }

        return new Result(bytes, Math.max(0, journalAfter - journalBefore),
                (int) DataDirs.fileCount(journal));
    }

    /** Polls until the journal directory stops changing size, or we give up. */
    private static void waitForQuietJournal(Path journal) throws InterruptedException {
        long previous = -1;
        for (int attempt = 0; attempt < 10; attempt++) {
            Thread.sleep(300);
            long size = DataDirs.sizeOf(journal);
            if (size == previous) {
                return;
            }
            previous = size;
        }
    }

    private record Result(long bytesPublished, long journalGrowth, int journalFilesAfter) {
    }
}
