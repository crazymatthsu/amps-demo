package com.demo.amps.utils;

import com.crankuptheamps.client.Client;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Publishes the contents of a file to an AMPS topic. */
public final class FileToAmpsTool implements Tool {

    @Override
    public String name() {
        return "file-to-amps";
    }

    @Override
    public String summary() {
        return "Publish a file to a topic, one message per line";
    }

    @Override
    public String usage() {
        return """
               Options:
                 --file PATH        file to publish                        (required)
                 --topic NAME       destination topic                      (required,
                                    optional for --format jsonl if the records carry one)
                 --type TYPE        message type: json | fix | nvfix       (default: json)
                 --format FORMAT    lines | jsonl                          (default: lines)
                 --whole-file       publish the entire file as ONE message
                 --delta            use delta_publish instead of publish
                 --dry-run          parse and count, publish nothing

               The default 'lines' format is one payload per line, which is what the
               dump tools here produce -- so a SOW dump can be replayed straight back.
               Blank lines are skipped.
               """;
    }

    @Override
    public void run(Args args) throws Exception {
        args.reject("file", "topic", "type", "format", "whole-file", "delta", "dry-run");

        Path file = Path.of(args.require("file", "the file to publish"));
        String defaultTopic = args.get("topic", null);
        String messageType = args.get("type", "json");
        String format = Payloads.requireKnownFormat(args.get("format", Payloads.LINES));
        boolean wholeFile = args.has("whole-file");
        boolean delta = args.has("delta");
        boolean dryRun = args.has("dry-run");

        if (!Files.isRegularFile(file)) {
            throw new Args.UsageException("no such file: " + file.toAbsolutePath());
        }
        if (wholeFile && defaultTopic == null) {
            throw new Args.UsageException("--topic is required with --whole-file");
        }

        Console.title("fileToAMPS");
        Console.kv("file", file.toAbsolutePath());
        Console.kv("size", Console.bytes(Files.size(file)));
        Console.kv("topic", defaultTopic == null ? "(from each jsonl record)" : defaultTopic);
        Console.kv("message type", messageType);
        Console.kv("format", wholeFile ? "whole-file" : format);
        Console.kv("mode", delta ? "delta_publish" : "publish");
        Console.kv("server", DemoConfig.uri(messageType));
        if (dryRun) {
            Console.kv("dry run", "nothing will be published");
        }

        long published = 0;
        long skippedBlank = 0;
        long bytes = 0;

        try (Client client = AmpsConnections.connect("utils-file-to-amps", messageType)) {
            if (wholeFile) {
                String payload = Files.readString(file, StandardCharsets.UTF_8);
                bytes = payload.length();
                if (!dryRun) {
                    publish(client, defaultTopic, payload, delta);
                }
                published = 1;
            } else {
                // Streamed rather than slurped: these files are dumps, and a SOW
                // dump of a busy topic is not something to hold in memory.
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    long lineNumber = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        if (line.isBlank()) {
                            skippedBlank++;
                            continue;
                        }
                        String payload;
                        String topic;
                        try {
                            payload = Payloads.toPayload(line, format);
                            String recordTopic = Payloads.topicOf(line, format);
                            topic = recordTopic != null ? recordTopic : defaultTopic;
                        } catch (RuntimeException e) {
                            throw new Args.UsageException(
                                    "line " + lineNumber + " is not valid " + format + ": "
                                            + e.getMessage());
                        }
                        if (topic == null) {
                            throw new Args.UsageException("line " + lineNumber
                                    + " has no topic and --topic was not given");
                        }
                        bytes += payload.length();
                        if (!dryRun) {
                            publish(client, topic, payload, delta);
                        }
                        published++;
                    }
                }
            }

            if (!dryRun) {
                // Without this the process can exit with messages still buffered.
                client.publishFlush(DemoConfig.timeoutMillis());
            }
        }

        Console.step("Result");
        Console.kv(dryRun ? "messages that would publish" : "messages published", published);
        Console.kv("payload bytes", Console.bytes(bytes));
        if (skippedBlank > 0) {
            Console.kv("blank lines skipped", skippedBlank);
        }
        if (published == 0) {
            Console.note("Nothing was published. An empty file, or every line blank.");
        }
    }

    private static void publish(Client client, String topic, String payload, boolean delta)
            throws Exception {
        if (delta) {
            client.deltaPublish(topic, payload);
        } else {
            client.publish(topic, payload);
        }
    }
}
