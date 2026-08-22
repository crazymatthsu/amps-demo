package com.demo.amps.cli;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.cli.fix.NvfixFormatter;
import com.demo.amps.common.MessageStreams;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AMPS SOW operations used by the CLI: snapshot, query, and snapshot-then-
 * subscribe against a FIX (or NVFIX) topic.
 */
public final class SowFixClient {

    private SowFixClient() {
    }

    public static Client connect(CliOptions options) throws AMPSException {
        Client client = new Client(options.clientName());
        try {
            client.connect(options.uri());
            client.logon(options.timeoutMillis());
        } catch (AMPSException e) {
            client.close();
            throw e;
        }
        return client;
    }

    public static int run(CliOptions options, PrintStream out, PrintStream err) throws Exception {
        try (Client client = connect(options)) {
            return switch (options.mode()) {
                case SNAPSHOT, QUERY -> sow(client, options, out, err);
                case SNAPSHOT_SUBSCRIBE -> sowAndSubscribe(client, options, out, err);
            };
        }
    }

    private static int sow(Client client, CliOptions options, PrintStream out, PrintStream err)
            throws Exception {
        Command command = new Command("sow")
                .setTopic(options.topic())
                .setBatchSize(100)
                .setTimeout(options.timeoutMillis());
        if (options.filter() != null && !options.filter().isBlank()) {
            command.setFilter(options.filter());
        }
        int printed = consume(client, command, options, out);
        err.println("amps-cli: printed " + printed + " message(s) from " + options.topic());
        return printed;
    }

    private static int sowAndSubscribe(
            Client client, CliOptions options, PrintStream out, PrintStream err) throws Exception {
        Command command = new Command("sow_and_subscribe")
                .setTopic(options.topic())
                .setOptions(Message.Options.SendKeys)
                .setBatchSize(100)
                .setTimeout(options.timeoutMillis());
        if (options.filter() != null && !options.filter().isBlank()) {
            command.setFilter(options.filter());
        }
        int printed = consume(client, command, options, out);
        err.println("amps-cli: printed " + printed + " message(s) from " + options.topic()
                + " (snapshot + subscribe)");
        return printed;
    }

    private static int consume(Client client, Command command, CliOptions options, PrintStream out)
            throws Exception {
        AtomicInteger printed = new AtomicInteger();
        int idleTimeout = (int) Math.min(Integer.MAX_VALUE, options.timeoutMillis());
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, idleTimeout, message -> {
                if (!isDataMessage(message)) {
                    return true;
                }
                String payload = message.getData();
                if (payload == null || payload.isEmpty()) {
                    return true;
                }
                out.print(format(payload, options.output()));
                if (!payload.endsWith("\n")) {
                    out.println();
                }
                int n = printed.incrementAndGet();
                return options.maxMessages() <= 0 || n < options.maxMessages();
            });
        }
        return printed.get();
    }

    public static String format(String payload, CliOptions.OutputFormat output) {
        if (output == CliOptions.OutputFormat.NVFIX) {
            return NvfixFormatter.toNvfix(payload);
        }
        return payload;
    }

    private static boolean isDataMessage(Message message) {
        if (message.isDataNull()) {
            return false;
        }
        int command = message.getCommand();
        return command == Message.Command.SOW
                || command == Message.Command.Publish
                || command == Message.Command.SOWDelete;
    }
}
