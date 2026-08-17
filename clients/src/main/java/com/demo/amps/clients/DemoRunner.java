package com.demo.amps.clients;

import com.demo.amps.clients.demos.BookmarkReplayDemo;
import com.demo.amps.clients.demos.DeltaPublishDemo;
import com.demo.amps.clients.demos.DeltaSubscribeDemo;
import com.demo.amps.clients.demos.DynamicTopicsDemo;
import com.demo.amps.clients.demos.ExpirationDemo;
import com.demo.amps.clients.demos.FixLifecycleDemo;
import com.demo.amps.clients.demos.JournalLabDemo;
import com.demo.amps.clients.demos.PubSubDemo;
import com.demo.amps.clients.demos.RecoveryDemo;
import com.demo.amps.clients.demos.SowAndSubscribeDemo;
import com.demo.amps.clients.demos.SowLoadDemo;
import com.demo.amps.clients.demos.SowQueryByKeyDemo;
import com.demo.amps.clients.demos.SowQueryDemo;
import com.demo.amps.clients.demos.TruncateDemo;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point: {@code amps-demo <demo> [--options]}.
 *
 * <p>Run {@code list} for the catalogue, or {@code tour} for the guided sequence
 * that tells the whole story in order.
 */
public final class DemoRunner {

    /** Ordered so `list` reads as a curriculum rather than an inventory. */
    private static final Map<String, Demo> DEMOS = new LinkedHashMap<>();

    /** The subset `tour` runs, in the order that builds the argument. */
    private static final List<String> TOUR = List.of(
            "pubsub",
            "dynamic-topics",
            "sow-load",
            "sow-query",
            "sow-query-by-key",
            "sow-and-subscribe",
            "delta-publish",
            "delta-subscribe",
            "bookmark-replay",
            "expiration");

    static {
        register(new PubSubDemo());
        register(new DynamicTopicsDemo());
        register(new SowLoadDemo());
        register(new SowQueryDemo());
        register(new SowQueryByKeyDemo());
        register(new SowAndSubscribeDemo());
        register(new DeltaPublishDemo());
        register(new DeltaSubscribeDemo());
        register(new BookmarkReplayDemo());
        register(new RecoveryDemo());
        register(new ExpirationDemo());
        register(new TruncateDemo());
        register(new FixLifecycleDemo());
        register(new JournalLabDemo());
    }

    private DemoRunner() {
    }

    private static void register(Demo demo) {
        DEMOS.put(demo.name(), demo);
    }

    public static void main(String[] argv) {
        if (argv.length == 0 || "list".equals(argv[0]) || "--help".equals(argv[0])
                || "-h".equals(argv[0])) {
            printCatalogue();
            return;
        }

        String name = argv[0];
        String[] rest = new String[argv.length - 1];
        System.arraycopy(argv, 1, rest, 0, rest.length);
        Args args = Args.parse(rest);

        try {
            if ("tour".equals(name)) {
                runTour(args);
            } else {
                Demo demo = DEMOS.get(name);
                if (demo == null) {
                    System.err.println("unknown demo: " + name);
                    printCatalogue();
                    System.exit(2);
                    return;
                }
                runOne(demo, args);
            }
        } catch (Exception e) {
            System.err.println();
            if (isConnectionProblem(e)) {
                // The stack trace tells the reader nothing they can act on here.
                System.err.println("Could not reach AMPS at " + DemoConfig.uri());
                System.err.println("  " + e.getMessage());
                System.err.println();
                System.err.println("Start it with:  ./server/scripts/amps.sh start");
                System.err.println("Check it with:  ./server/scripts/amps.sh status");
            } else {
                System.err.println("demo failed: " + e);
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static void runOne(Demo demo, Args args) throws Exception {
        Console.title(demo.name() + " -- " + demo.summary());
        Console.note(demo.explanation());
        Console.kv("connecting to", DemoConfig.uri());
        demo.run(args);
        Console.info("");
    }

    private static void runTour(Args args) throws Exception {
        Console.title("AMPS feature tour");
        Console.note("Runs the core demos in the order that builds the story: a plain "
                + "pub/sub topic, then what declaring it as a SOW topic adds, then what "
                + "adding it to the transaction log adds. The recovery and journal-lab "
                + "demos are not included because they need a server restart and a longer "
                + "run respectively -- see the README for those.");

        for (String name : TOUR) {
            Demo demo = DEMOS.get(name);
            if (demo == null) {
                continue;
            }
            runOne(demo, args);
        }

        Console.title("Tour complete");
        Console.bullet("recovery      restart survival (needs a server restart between phases)");
        Console.bullet("journal-lab   transaction-log sizing, full vs delta");
        Console.note("Then read docs/amps-vs-kafka.md and docs/transaction-log-sizing.md.");
    }

    private static void printCatalogue() {
        Console.title("AMPS demos");
        Console.info("   usage: amps-demo <demo> [--option value]");
        Console.info("");
        Console.info("   %-20s %s", "tour", "run the core demos in order");
        Console.info("");
        for (Demo demo : DEMOS.values()) {
            Console.info("   %-20s %s", demo.name(), demo.summary());
        }
        Console.info("");
        Console.info("   Server:  %s", DemoConfig.uri());
        Console.info("   Admin:   %s", DemoConfig.adminUrl());
        Console.info("");
        Console.info("   Override with -Damps.host / -Damps.port, or AMPS_HOST / AMPS_PORT.");
        Console.info("");
    }

    private static boolean isConnectionProblem(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String name = current.getClass().getSimpleName();
            if (name.contains("Connection") || name.contains("Disconnected")
                    || current instanceof java.net.ConnectException) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
        }
        return false;
    }
}
