package com.demo.amps.utils;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.MessageStreams;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dumps a topic's transaction-log history to a file via a bookmark subscription.
 *
 * <p>This is a replay, not a file copy: AMPS serves the journal through an
 * ordinary subscription that starts at a bookmark, so what lands in the output
 * is the message stream, in order, exactly as a resuming subscriber would see
 * it. Two consequences worth knowing:
 *
 * <ul>
 *   <li>Only topics listed in {@code <TransactionLog>} have any history. On any
 *       other topic this returns nothing -- correctly.</li>
 *   <li>A bookmark subscription does not end; it catches up and then goes live.
 *       The tool therefore stops on an idle interval rather than on an
 *       end-of-stream marker, which is why {@code --idle-ms} exists.</li>
 * </ul>
 */
public final class TxLogToFileTool implements Tool {

    @Override
    public String name() {
        return "txlog-to-file";
    }

    @Override
    public String summary() {
        return "Dump a journalled topic's history to a file (bookmark replay)";
    }

    @Override
    public String usage() {
        return """
               Options:
                 --topic NAME       journalled topic to dump               (required)
                 --out PATH         file to write                          (required)
                 --bookmark BM      start point: 'epoch' | 'recent' | a bookmark
                                                                           (default: epoch)
                 --type TYPE        message type: json | fix | nvfix       (default: json)
                 --format FORMAT    lines | jsonl                          (default: jsonl)
                 --filter EXPR      content filter applied during replay
                 --max N            stop after N messages
                 --idle-ms N        stop after this long with no message   (default: 5000)

               Defaults to jsonl because a transaction-log dump is usually wanted for
               its bookmarks, and only jsonl records them. Use --format lines if you
               want a file you can hand straight back to fileToAMPS.

               Only topics in <TransactionLog> have history. Others report zero.
               """;
    }

    @Override
    public void run(Args args) throws Exception {
        args.reject("topic", "out", "bookmark", "type", "format", "filter", "max", "idle-ms");

        String topic = args.require("topic", "the journalled topic to dump");
        Path out = Path.of(args.require("out", "the file to write"));
        String messageType = args.get("type", "json");
        String format = Payloads.requireKnownFormat(args.get("format", Payloads.JSONL));
        String filter = args.get("filter", null);
        int idleMs = args.getInt("idle-ms", 5_000);
        long max = args.getInt("max", Integer.MAX_VALUE);
        String bookmark = resolveBookmark(args.get("bookmark", "epoch"));

        Console.title("ampsToFileTxLog");
        Console.kv("topic", topic);
        Console.kv("from bookmark", bookmark);
        Console.kv("filter", filter == null ? "(none)" : filter);
        Console.kv("message type", messageType);
        Console.kv("format", format);
        Console.kv("out", out.toAbsolutePath());
        Console.kv("server", DemoConfig.uri(messageType));

        Command command = new Command("subscribe")
                .setTopic(topic)
                .setBookmark(bookmark)
                .setTimeout(DemoConfig.timeoutMillis());
        if (filter != null) {
            command.setFilter(filter);
        }

        AtomicLong lastBookmark = new AtomicLong();
        String[] finalBookmark = {null};
        long records;
        long embeddedNewlines;

        try (Client client = AmpsConnections.connect("utils-txlog-to-file", messageType);
             Payloads.Writer writer = new Payloads.Writer(out, format);
             MessageStream stream = client.execute(command)) {

            MessageStreams.forEach(stream, idleMs, message -> {
                // Subscription acks and markers carry no payload.
                if (message.isDataNull()) {
                    return true;
                }
                try {
                    writer.write(message);
                    finalBookmark[0] = message.getBookmark();
                } catch (IOException e) {
                    throw new IllegalStateException("could not write " + out + ": "
                            + e.getMessage(), e);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                lastBookmark.incrementAndGet();
                return writer.written() < max;
            });
            records = writer.written();
            embeddedNewlines = writer.withEmbeddedNewline();
        }

        Console.step("Result");
        Console.kv("messages written", records);
        Console.kv("file", out.toAbsolutePath());
        if (finalBookmark[0] != null) {
            Console.kv("last bookmark", finalBookmark[0]);
            Console.note("Pass that back as --bookmark to continue from here next time.");
        }
        if (records == 0) {
            Console.note("No history. Either nothing was ever published to this topic, or "
                    + "-- much more likely -- the topic is not listed in <TransactionLog>, "
                    + "so AMPS keeps no journal for it. Check "
                    + "server/config/flows/<flow>/amps-config.xml.");
        }
        if (embeddedNewlines > 0) {
            Console.kv("payloads containing a newline", embeddedNewlines);
            Console.note("Those span more than one line in this file. Use --format jsonl "
                    + "for an exact round-trip.");
        }
    }

    /** Accepts the two names people actually type, or a raw bookmark. */
    private static String resolveBookmark(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "epoch", "start", "beginning" -> Client.Bookmarks.EPOCH;
            case "recent", "most_recent", "resume" -> Client.Bookmarks.MOST_RECENT;
            case "now" -> Client.Bookmarks.NOW;
            default -> value;
        };
    }
}
