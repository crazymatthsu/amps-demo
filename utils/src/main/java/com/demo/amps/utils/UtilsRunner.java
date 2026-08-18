package com.demo.amps.utils;

import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point: {@code amps-utils <tool> [--options]}.
 *
 * <p>One dispatcher rather than five main classes, so there is a single
 * launcher to install and a single place that turns a bad command line into
 * usage text. The shell wrappers in {@code utils/bin} each pin one subcommand.
 */
public final class UtilsRunner {

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    static {
        register(new FileToAmpsTool());
        register(new TxLogToFileTool());
        register(new SowToFileTool());
        register(new TruncateTool());
    }

    private UtilsRunner() {
    }

    private static void register(Tool tool) {
        TOOLS.put(tool.name(), tool);
    }

    /** Visible for tests: the registry the shell wrappers have to line up with. */
    static Map<String, Tool> tools() {
        return Map.copyOf(TOOLS);
    }

    public static void main(String[] argv) {
        if (argv.length == 0 || isHelp(argv[0])) {
            printCatalogue();
            return;
        }

        String name = argv[0];
        Tool tool = TOOLS.get(name);
        if (tool == null) {
            System.err.println("unknown tool: " + name);
            printCatalogue();
            System.exit(2);
            return;
        }

        String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
        if (Arrays.stream(rest).anyMatch(UtilsRunner::isHelp)) {
            printToolHelp(tool);
            return;
        }

        try {
            tool.run(Args.parse(rest));
        } catch (Args.UsageException e) {
            System.err.println();
            System.err.println("error: " + e.getMessage());
            System.err.println();
            printToolHelp(tool);
            System.exit(2);
        } catch (ToolFailure e) {
            System.err.println();
            System.err.println("error: " + e.getMessage());
            System.exit(e.exitCode());
        } catch (Exception e) {
            System.err.println();
            if (isConnectionProblem(e)) {
                System.err.println("Could not reach AMPS at " + DemoConfig.uri());
                System.err.println("  " + e.getMessage());
                System.err.println();
                System.err.println("Start it with:  ./server/scripts/amps.sh start");
                System.err.println("Check it with:  ./server/scripts/amps.sh status");
            } else {
                System.err.println(name + " failed: " + e);
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static boolean isHelp(String token) {
        return "--help".equals(token) || "-h".equals(token) || "help".equals(token);
    }

    private static void printToolHelp(Tool tool) {
        Console.title(tool.name() + " -- " + tool.summary());
        System.out.println(tool.usage());
        printServer();
    }

    private static void printCatalogue() {
        Console.title("AMPS utils");
        Console.info("   usage: amps-utils <tool> [--option value]");
        Console.info("");
        for (Tool tool : TOOLS.values()) {
            Console.info("   %-16s %s", tool.name(), tool.summary());
        }
        Console.info("");
        Console.info("   Each tool also has a shell wrapper in utils/bin, which is the");
        Console.info("   intended way to call them:");
        Console.info("");
        Console.info("     utils/bin/fileToAMPS.sh          --topic orders --file orders.json");
        Console.info("     utils/bin/ampsToFileSOW.sh       --topic orders --out sow.json");
        Console.info("     utils/bin/ampsToFileSOWByKey.sh  --topic orders --filter \"/quantity > 3000\" --out big.json");
        Console.info("     utils/bin/ampsToFileTxLog.sh     --topic orders --out journal.jsonl");
        Console.info("     utils/bin/truncateAMPS.sh        --topic orders");
        Console.info("");
        Console.info("   '<tool> --help' for that tool's options.");
        printServer();
    }

    private static void printServer() {
        Console.info("");
        Console.info("   Server:  %s", DemoConfig.uri());
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
