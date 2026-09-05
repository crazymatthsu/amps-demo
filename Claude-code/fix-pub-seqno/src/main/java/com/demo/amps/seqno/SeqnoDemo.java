package com.demo.amps.seqno;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.exception.NameInUseException;
import com.demo.amps.common.Console;
import com.demo.amps.seqno.fix.FixMessage;
import com.demo.amps.seqno.fix.FixTags;
import com.demo.amps.seqno.fix.OrderFeed;
import com.demo.amps.seqno.outbox.Outbox;
import com.demo.amps.seqno.publish.JournalLastSequenceLocator;
import com.demo.amps.seqno.publish.JournalScanResult;
import com.demo.amps.seqno.publish.PublisherRecovery;
import com.demo.amps.seqno.publish.RecoveryReport;
import com.demo.amps.seqno.publish.SequencedPublisher;
import com.demo.amps.seqno.subscribe.BookmarkSubscriber;
import com.demo.amps.seqno.subscribe.SequenceTracker;
import com.demo.amps.seqno.subscribe.SubscriberReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demo entry point: a FIX publisher that survives losing its AMPS
 * connection without losing or duplicating a message, and a subscriber that
 * proves it end to end.
 *
 * <pre>
 *   AMPS_FLOW=fix-pub-seqno ./server/scripts/amps.sh start
 *   ./gradlew :Claude-code:fix-pub-seqno:run --args="all"
 * </pre>
 *
 * <p>Phases (also runnable one at a time, so the crash and the recovery can be
 * separated by a real server restart):
 *
 * <ul>
 *   <li>{@code publish}   -- send N orders; read the last 8888 back both ways.</li>
 *   <li>{@code crash}     -- record M more in the outbox, send only K, drop the
 *       connection without flushing. The classic persist-then-die gap.</li>
 *   <li>{@code recover}   -- ask AMPS what it has, republish only the gap, verify.</li>
 *   <li>{@code subscribe} -- resume a bookmark subscription; report every 8888.</li>
 *   <li>{@code naive}     -- a second sender resends below L; the scan and the
 *       subscriber both catch the duplicates.</li>
 *   <li>{@code all}       -- the five above in order, against one instance.</li>
 *   <li>{@code reset}     -- delete this demo's client-side state.</li>
 * </ul>
 *
 * <p>The design and its failure analysis are in {@code docs/} next to this
 * source tree.
 */
public final class SeqnoDemo {

    private static final Logger log = LoggerFactory.getLogger(SeqnoDemo.class);

    private SeqnoDemo() {
    }

    public static void main(String[] args) throws Exception {
        DemoArgs parsed = DemoArgs.parse(args);
        String phase = parsed.positional(0, "all");
        SeqnoConfig config = SeqnoConfig.fromEnvironment();

        Console.title("fix-pub-seqno -- FIX publisher sequence recovery (tag 8888)");
        Console.kv("AMPS URI", config.uri());
        Console.kv("topic", config.topic());
        Console.kv("sender (tag 49)", config.sender());
        Console.kv("state dir", config.stateDir());

        switch (phase) {
            case "publish" -> publish(config, parsed.getInt("count", 8));
            case "crash" -> crash(config, parsed.getInt("count", 6), parsed.getInt("sent", 2));
            case "recover" -> recover(config);
            case "subscribe" -> subscribe(config, parsed.has("reset"));
            case "naive" -> naive(config.withSender(parsed.get("sender", "PUB-NAIVE")),
                    parsed.getInt("count", 5), parsed.getInt("resend", 3));
            case "reset" -> reset(config);
            case "all" -> all(config);
            case "list", "help" -> help();
            default -> {
                Console.note("Unknown phase '" + phase + "'.");
                help();
                throw new IllegalArgumentException("unknown phase: " + phase);
            }
        }
    }

    // ------------------------------------------------------------------ phases

    private static void publish(SeqnoConfig config, int count) throws Exception {
        Console.step("publish -- send " + count + " orders");
        Outbox outbox = Outbox.open(config.outboxFile());
        Client client = connectPublisher(config);
        try {
            SequencedPublisher publisher = new SequencedPublisher(client, outbox, config);
            // Recovery on every connect, first run included.
            RecoveryReport recovery = new PublisherRecovery(client, config, publisher).recover();
            reportRecovery(recovery);

            OrderFeed feed = new OrderFeed(config.sender(), outbox.lastSequence() + 1, Instant.now());
            long last = publisher.publishAll(feed.next(count));
            publisher.flush();
            Console.kv("published up to 8888", last);
            Console.kv("outbox now holds", outbox.lastSequence());

            showLast(config, client);
        } finally {
            client.close();
        }
    }

    private static void crash(SeqnoConfig config, int record, int send) throws Exception {
        if (send >= record) {
            throw new IllegalArgumentException("crash needs sent (" + send + ") < count (" + record + ")");
        }
        Console.step("crash -- record " + record + ", send only " + send + ", then drop the link");
        Console.note("This is the failure the whole design is for: a publisher that committed "
                + "messages to its own store and died before AMPS received them. The outbox ends "
                + "ahead of AMPS, with no flush and no clean disconnect.");
        Outbox outbox = Outbox.open(config.outboxFile());
        Client client = connectPublisher(config);
        boolean dropped = false;
        try {
            SequencedPublisher publisher = new SequencedPublisher(client, outbox, config);
            new PublisherRecovery(client, config, publisher).recover();

            long base = outbox.lastSequence();
            OrderFeed feed = new OrderFeed(config.sender(), base + 1, Instant.now());

            // Persist all of them locally first...
            List<Long> sequences = new java.util.ArrayList<>();
            for (FixMessage order : feed.next(record)) {
                sequences.add(publisher.enqueue(order));
            }
            Console.kv("recorded in outbox", "8888 " + (base + 1) + ".." + (base + record));

            // ...but only send the first `send`, and flush just those so AMPS
            // deterministically holds exactly base+send.
            for (int i = 0; i < send; i++) {
                publisher.transmit(sequences.get(i));
            }
            publisher.flush();
            Console.kv("sent to AMPS", "8888 " + (base + 1) + ".." + (base + send));
            Console.kv("NOT sent (the gap)", "8888 " + (base + send + 1) + ".." + (base + record));

            // Simulate the crash: disconnect without draining anything else.
            client.disconnect();
            dropped = true;
            Console.note("Connection dropped. AMPS holds up to " + (base + send) + "; the outbox "
                    + "holds up to " + outbox.lastSequence() + ". Run 'recover' next.");
        } finally {
            if (!dropped) {
                client.close();
            }
        }
    }

    private static void recover(SeqnoConfig config) throws Exception {
        Console.step("recover -- find L, republish the gap, verify");
        Outbox outbox = Outbox.open(config.outboxFile());
        Client client = connectPublisher(config);
        try {
            SequencedPublisher publisher = new SequencedPublisher(client, outbox, config);
            RecoveryReport report = new PublisherRecovery(client, config, publisher).recover();
            reportRecovery(report);
            if (!report.verified() && report.decision().action()
                    == com.demo.amps.seqno.publish.RecoveryDecision.Action.HALT) {
                Console.note("Recovery halted deliberately. See the reason above; publishing must "
                        + "not resume until a person has reconciled the outbox with the journal.");
            }
        } finally {
            client.close();
        }
    }

    private static void subscribe(SeqnoConfig config, boolean reset) throws Exception {
        Console.step("subscribe -- resume from the bookmark store, check every 8888");
        if (reset) {
            Files.deleteIfExists(config.bookmarkFile());
            Files.deleteIfExists(config.highWaterMarkFile());
            Console.note("Reset: cleared this subscriber's bookmark store and high-water marks, "
                    + "so it replays from the start of the journal.");
        }
        SequenceTracker tracker = SequenceTracker.load(config.highWaterMarkFile());
        try (BookmarkSubscriber subscriber = new BookmarkSubscriber(config)) {
            SubscriberReport report = subscriber.consume(tracker);
            Console.kv("processed", report.processed());
            Console.kv("duplicates skipped", report.duplicatesSkipped());
            Console.kv("gaps detected", report.gaps().size());
            report.gaps().forEach(gap -> Console.bullet(gap));
            Console.kv("high-water marks", report.marks());
            Console.note(report.cleanRun()
                    ? "No gaps: every sender's sequence arrived unbroken. Run 'subscribe' again "
                      + "and it resumes after here -- the second run processes only what is new."
                    : "Gaps above are real: the publisher invariant failed, or the journal aged "
                      + "out under this subscriber's bookmark.");
        }
    }

    private static void naive(SeqnoConfig config, int count, int resend) throws Exception {
        Console.step("naive -- a publisher that resends below L without asking AMPS");
        Console.note("The counter-example. This sender publishes " + count + " orders correctly, "
                + "then blindly re-sends its last " + resend + " -- the mistake the recovery exists "
                + "to avoid. Without a publish store AMPS cannot reject them, so they land in the "
                + "journal twice; the scan and the subscriber both catch that.");
        Outbox outbox = Outbox.open(config.outboxFile());
        Client client = connectPublisher(config);
        try {
            SequencedPublisher publisher = new SequencedPublisher(client, outbox, config);
            new PublisherRecovery(client, config, publisher).recover();

            OrderFeed feed = new OrderFeed(config.sender(), outbox.lastSequence() + 1, Instant.now());
            long last = publisher.publishAll(feed.next(count));
            publisher.flush();
            Console.kv("published up to 8888", last);

            long from = Math.max(0, last - resend);
            Console.kv("naively re-sending 8888", (from + 1) + ".." + last);
            publisher.republish(from, last);   // below L on purpose
            publisher.flush();

            JournalScanResult scan = new JournalLastSequenceLocator(client, config).scanFromEpoch(config.sender());
            Console.kv("journal scan", scan);
            Console.kv("duplicates the scan caught", scan.duplicates());
            Console.note(scan.duplicates().isEmpty()
                    ? "No duplicates seen -- unexpected for this phase."
                    : "The scan reports the repeated 8888 values. A subscriber's per-sender check "
                      + "skips them, so no consumer double-processes; with a publish store (the "
                      + "production recommendation) AMPS would have rejected them at the source.");
        } finally {
            client.close();
        }
    }

    private static void all(SeqnoConfig config) throws Exception {
        Console.note("Running publish -> crash -> recover -> subscribe -> naive against this one "
                + "instance. Each phase connects its own client, as it would in production.");
        publish(config, 8);
        crash(config, 6, 2);
        recover(config);
        subscribe(config, false);
        naive(config.withSender("PUB-NAIVE"), 5, 3);
        Console.step("done");
        Console.note("Every order the publisher committed reached AMPS exactly once. The crash "
                + "left a gap of four; recovery asked AMPS for its last 8888 and republished only "
                + "those four; the subscriber saw an unbroken sequence.");
    }

    private static void reset(SeqnoConfig config) throws Exception {
        Console.step("reset -- delete this demo's client-side state");
        Path dir = config.stateDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception e) {
                        log.warn("could not delete {}: {}", path, e.toString());
                    }
                });
            }
        }
        Console.kv("deleted", dir);
        Console.note("The server's SOW and journal are untouched. For a fully clean run also reset "
                + "the instance (AMPS_FLOW=fix-pub-seqno ./server/scripts/amps.sh reset), or the "
                + "next recovery will HALT -- correctly -- on finding AMPS ahead of the empty outbox.");
    }

    // ------------------------------------------------------------------ helpers

    private static void showLast(SeqnoConfig config, Client client) throws Exception {
        long sow = new com.demo.amps.seqno.publish.SowLastSequenceLocator(client, config)
                .locate(config.sender());
        JournalScanResult scan = new JournalLastSequenceLocator(client, config)
                .scanFromEpoch(config.sender());
        Console.kv("last 8888 per the SOW", sow);
        Console.kv("last 8888 per the journal", scan.max().isPresent() ? scan.max().getAsLong() : "none");
        Console.kv("journal contiguous", scan.isContiguous());
    }

    private static void reportRecovery(RecoveryReport report) {
        Console.kv("recovery action", report.decision().action());
        Console.kv("last 8888 AMPS held", report.decision().resolvedL());
        Console.kv("messages republished", report.messagesResent());
        Console.kv("verified in sync", report.verified());
        report.decision().alarms().forEach(alarm -> Console.bullet("ALARM: " + alarm));
    }

    private static void help() {
        Console.step("phases");
        Console.bullet("publish   [--count N]                 send N new orders");
        Console.bullet("crash     [--count M] [--sent K]      record M, send K, drop the link");
        Console.bullet("recover                               find L, republish the gap, verify");
        Console.bullet("subscribe [--reset]                   resume and check tag 8888");
        Console.bullet("naive     [--sender S --count N --resend R]   resend below L (counter-example)");
        Console.bullet("all                                   the sequence above, one instance");
        Console.bullet("reset                                 delete client-side state");
    }

    /**
     * Connects the publisher under its stable client name, retrying
     * {@link NameInUseException}: after a crash the old session lingers until
     * the server's heartbeat timeout reaps it, and a transaction-logged
     * instance permits one connection per name.
     */
    private static Client connectPublisher(SeqnoConfig config) throws Exception {
        int attempts = 0;
        while (true) {
            Client client = new Client(config.publisherClientName());
            try {
                client.connect(config.uri());
                client.logon(config.timeoutMillis());
                try {
                    client.setHeartbeat(5);
                } catch (Exception ignore) {
                    // Heartbeats are a reaping optimisation, not required for the demo.
                }
                return client;
            } catch (NameInUseException e) {
                client.close();
                if (++attempts >= 5) {
                    throw e;
                }
                Console.note("client name '" + config.publisherClientName() + "' still in use "
                        + "(previous session not yet reaped); retrying in 2s");
                Thread.sleep(2000);
            } catch (Exception e) {
                client.close();
                throw e;
            }
        }
    }
}
