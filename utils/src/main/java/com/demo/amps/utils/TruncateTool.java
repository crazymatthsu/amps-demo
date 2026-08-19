package com.demo.amps.utils;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.MessageStreams;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Clears SOW records out of one or more topics.
 *
 * <p><b>On the transaction log.</b> This tool cannot truncate it, and neither
 * can any other client: AMPS exposes no command that removes journal entries.
 * That is deliberate rather than an omission -- the journal is an ordered,
 * append-only record, and bookmark replay and publisher deduplication both
 * depend on it staying that way, so cutting messages out of the middle would
 * break the guarantees it exists to provide. A {@code sow_delete} is itself a
 * write, so clearing a SOW makes the journal <em>larger</em>.
 *
 * <p>The two legitimate ways to bound the journal are listed by
 * {@code --journal}, which is what that flag is for -- it explains and exits
 * rather than pretending to do something.
 *
 * <p>Destructive, so: dry run unless {@code --yes}.
 */
public final class TruncateTool implements Tool {

    /** The conventional match-everything filter for a whole-topic purge. */
    private static final String MATCH_EVERYTHING = "1=1";

    @Override
    public String name() {
        return "truncate";
    }

    @Override
    public String summary() {
        return "Delete SOW records from topics (dry run unless --yes)";
    }

    @Override
    public String usage() {
        return """
               Options:
                 --topic A,B,C      topics to clear                        (required)
                 --filter EXPR      what to delete           (default: 1=1, everything)
                 --type TYPE        message type: json | fix | nvfix       (default: json)
                 --yes              actually delete; without it this is a dry run
                 --journal          explain how to bound the transaction log, and exit

               Dry run by default: it counts what matches and reports it, changing
               nothing. Add --yes to perform the deletes.

               This clears the SOW only. The transaction log cannot be truncated by
               any client -- run with --journal for the reason and the two things
               that do work.
               """;
    }

    @Override
    public void run(Args args) throws Exception {
        args.reject("topic", "filter", "type", "yes", "journal");

        if (args.has("journal")) {
            explainJournal();
            throw new ToolFailure(3, "the transaction log cannot be truncated from a client");
        }

        List<String> topics = args.list("topic");
        if (topics.isEmpty()) {
            throw new Args.UsageException(
                    "--topic is required (comma-separated list of topics to clear)");
        }
        String filter = args.get("filter", MATCH_EVERYTHING);
        String messageType = args.get("type", "json");
        boolean confirmed = args.has("yes");

        Console.title("truncateAMPS");
        Console.kv("topics", String.join(", ", topics));
        Console.kv("filter", filter
                + (MATCH_EVERYTHING.equals(filter) ? "   (everything)" : ""));
        Console.kv("message type", messageType);
        Console.kv("mode", confirmed ? "DELETING" : "dry run (add --yes to delete)");
        Console.kv("server", DemoConfig.uri(messageType));

        long totalBefore = 0;
        long totalDeleted = 0;

        try (Client client = AmpsConnections.connect("utils-truncate", messageType)) {
            for (String topic : topics) {
                long matching = count(client, topic, filter);
                totalBefore += matching;

                if (!confirmed) {
                    Console.kv(topic, matching + " record(s) would be deleted");
                    continue;
                }

                Message ack = client.sowDelete(topic, filter, DemoConfig.timeoutMillis());
                long deleted = ack.getRecordsDeleted();
                totalDeleted += deleted;
                Console.kv(topic, deleted + " record(s) deleted, "
                        + count(client, topic, null) + " remaining in topic");
            }
        }

        Console.step("Result");
        if (confirmed) {
            Console.kv("records deleted", totalDeleted);
            Console.note("Every deleted record produced an OOF with reason 'deleted', so live "
                    + "subscribers have already dropped those rows.");
        } else {
            Console.kv("records that would be deleted", totalBefore);
            Console.note("Nothing was changed. Re-run with --yes to delete.");
        }

        Console.step("What this did not do");
        Console.note("It did not shrink anything on disk. A delete is a write, so the "
                + "transaction log grew; and a SOW file frees the space internally for "
                + "reuse rather than returning it to the filesystem. Run with --journal "
                + "for the ways to actually bound the journal.");
    }

    private static void explainJournal() {
        Console.title("truncateAMPS --journal");
        Console.note("The transaction log cannot be truncated by a client, and this tool "
                + "will not pretend otherwise. AMPS provides no command that removes "
                + "journal entries: the journal is ordered and append-only, and bookmark "
                + "replay and publisher deduplication both depend on that, so removing "
                + "messages from the middle would break the guarantees it exists to "
                + "provide. Note also that a sow_delete is itself a write -- clearing a "
                + "SOW makes the journal bigger, not smaller.");

        Console.step("What does work");
        Console.bullet("Age journal files out on a schedule, which is the supported way to "
                + "bound it in a running instance:");
        Console.info("");
        Console.info("      <Action>");
        Console.info("        <On><Module>amps-action-on-schedule</Module>");
        Console.info("            <Options><Every>1h</Every></Options></On>");
        Console.info("        <Do><Module>amps-action-do-remove-journal</Module>");
        Console.info("            <Options><Age>1d</Age></Options></Do>");
        Console.info("      </Action>");
        Console.info("");
        Console.note("See server/config/flows/bounded-retention/amps-config.xml for "
                + "that in place, and docs/src/transaction-log-sizing.md for how to size "
                + "the window.");
        Console.bullet("Stop the instance and delete its data directory, which is the only "
                + "route that returns the disk to the filesystem:");
        Console.info("");
        Console.info("      ./server/scripts/amps.sh reset");
        Console.info("");
        Console.note("That clears the SOW and the journal together, and starts the next run "
                + "from empty.");

        Console.step("What to avoid");
        Console.note("Never delete journal files with an external rm while AMPS is running. "
                + "It tracks which files back which bookmark ranges; removing them "
                + "underneath it risks failed replays and a SOW it cannot reconcile after "
                + "an unclean shutdown. amps-action-do-remove-journal is the safe "
                + "equivalent because it updates that bookkeeping.");
    }

    private static long count(Client client, String topic, String filter) throws Exception {
        Command command = new Command("sow")
                .setTopic(topic)
                .setBatchSize(500)
                .setTimeout(DemoConfig.timeoutMillis());
        if (filter != null) {
            command.setFilter(filter);
        }
        AtomicLong records = new AtomicLong();
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, 10_000, message -> {
                if (message.getCommand() == Message.Command.SOW) {
                    records.incrementAndGet();
                }
                return true;
            });
        }
        return records.get();
    }
}
