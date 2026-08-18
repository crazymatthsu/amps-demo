package com.demo.amps.utils;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.MessageStreams;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Dumps a SOW topic to a file, whole or filtered.
 *
 * <p>Backs both {@code ampsToFileSOW.sh} and {@code ampsToFileSOWByKey.sh} --
 * they are the same query with and without a selector, and keeping one
 * implementation means the two cannot drift apart.
 */
public final class SowToFileTool implements Tool {

    @Override
    public String name() {
        return "sow-to-file";
    }

    @Override
    public String summary() {
        return "Dump a SOW topic to a file, optionally filtered";
    }

    @Override
    public String usage() {
        return """
               Options:
                 --topic NAME       SOW topic to dump                      (required)
                 --out PATH         file to write                          (required)
                 --filter EXPR      content filter, e.g. "/quantity > 3000"
                 --sow-keys K1,K2   server-assigned SOW keys to fetch
                 --type TYPE        message type: json | fix | nvfix       (default: json)
                 --format FORMAT    lines | jsonl                          (default: lines)
                 --top-n N          return at most N records
                 --order-by EXPR    server-side ordering, e.g. "/price DESC"
                 --batch-size N     records per batch on the wire          (default: 500)
                 --idle-ms N        give up after this long with no data   (default: 10000)

               The topic must be declared in <SOW> -- an undeclared topic keeps no
               state, so there is nothing to dump and this reports zero records.
               """;
    }

    @Override
    public void run(Args args) throws Exception {
        args.reject("topic", "out", "filter", "sow-keys", "type", "format",
                "top-n", "order-by", "batch-size", "idle-ms");

        String topic = args.require("topic", "the SOW topic to dump");
        Path out = Path.of(args.require("out", "the file to write"));
        String filter = args.get("filter", null);
        String sowKeys = args.get("sow-keys", null);
        String messageType = args.get("type", "json");
        String format = Payloads.requireKnownFormat(args.get("format", Payloads.LINES));
        int idleMs = args.getInt("idle-ms", 10_000);

        Console.title("ampsToFileSOW");
        Console.kv("topic", topic);
        Console.kv("filter", filter == null ? "(none -- whole topic)" : filter);
        if (sowKeys != null) {
            Console.kv("sow keys", sowKeys);
        }
        Console.kv("message type", messageType);
        Console.kv("format", format);
        Console.kv("out", out.toAbsolutePath());
        Console.kv("server", DemoConfig.uri(messageType));

        Command command = new Command("sow")
                .setTopic(topic)
                .setBatchSize(args.getInt("batch-size", 500))
                .setTimeout(DemoConfig.timeoutMillis());
        if (filter != null) {
            command.setFilter(filter);
        }
        if (sowKeys != null) {
            command.setSowKeys(sowKeys);
        }
        if (args.has("top-n")) {
            command.setTopN(args.getInt("top-n", 0));
        }
        if (args.has("order-by")) {
            command.setOrderBy(args.get("order-by", null));
        }

        long records;
        long embeddedNewlines;
        try (Client client = AmpsConnections.connect("utils-sow-to-file", messageType);
             Payloads.Writer writer = new Payloads.Writer(out, format);
             MessageStream stream = client.execute(command)) {

            MessageStreams.forEach(stream, idleMs, message -> {
                // group_begin / group_end bracket the result; only SOW messages
                // carry a record.
                if (message.getCommand() == Message.Command.SOW) {
                    try {
                        writer.write(message);
                    } catch (IOException e) {
                        throw new IllegalStateException("could not write " + out + ": "
                                + e.getMessage(), e);
                    }
                }
                return true;
            });
            records = writer.written();
            embeddedNewlines = writer.withEmbeddedNewline();
        }

        Console.step("Result");
        Console.kv("records written", records);
        Console.kv("file", out.toAbsolutePath());
        if (records == 0) {
            Console.note("Nothing matched. Either the topic holds no records, the filter "
                    + "excluded everything, or the topic is not declared in <SOW> and so "
                    + "keeps no state at all.");
        }
        if (embeddedNewlines > 0) {
            Console.kv("payloads containing a newline", embeddedNewlines);
            Console.note("Those payloads span more than one line in this file, so it will "
                    + "not read back as the same number of messages. Re-run with "
                    + "--format jsonl, which escapes them and round-trips exactly.");
        }
    }
}
