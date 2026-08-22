package com.demo.amps.cli;

/**
 * Entry point for {@code ./gradlew :amps-cli:run --args="..."}.
 *
 * <p>Reads a SOW of FIX messages from AMPS and prints them as raw FIX or as
 * NVFIX expanded against the FIX 4.2 spec. Connection defaults match
 * {@code DemoConfig} / {@code AMPS_*} so the rest of this repo's layout just
 * works; {@code --url} overrides the URI when you point at another instance.
 */
public final class AmpsCli {

    private AmpsCli() {
    }

    public static void main(String[] argv) {
        int code = run(argv);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** Testable entry: returns an exit code and does not call {@code System.exit}. */
    static int run(String[] argv) {
        CliArgs args = CliArgs.parse(argv);
        if (args.has("help") || args.has("h")) {
            usage(System.out);
            return 0;
        }
        CliOptions options;
        try {
            options = CliOptions.from(args);
            options.validate();
        } catch (RuntimeException e) {
            System.err.println("amps-cli: " + e.getMessage());
            usage(System.err);
            return 2;
        }
        try {
            SowFixClient.run(options, System.out, System.err);
            return 0;
        } catch (Exception e) {
            System.err.println("amps-cli: " + e.getMessage());
            return 1;
        }
    }

    static void usage(java.io.PrintStream out) {
        out.println("amps-cli — dump a FIX SOW topic from AMPS");
        out.println();
        out.println("Usage:");
        out.println("  ./gradlew :amps-cli:run --args=\"--mode snapshot --topic fix.native.orders\"");
        out.println("  ./gradlew :amps-cli:run --args=\"--mode query --filter \\\"/39 = '2'\\\" --output nvfix\"");
        out.println("  ./gradlew :amps-cli:run --args=\"--mode snapshot-subscribe --timeout-ms 15000\"");
        out.println();
        out.println("Flags:");
        out.println("  --url URI              AMPS client URI (default tcp://$AMPS_HOST:$AMPS_PORT/amps/fix)");
        out.println("  --message-type TYPE    URI path when --url is omitted (fix|nvfix), default fix");
        out.println("  --topic NAME           SOW topic, default fix.native.orders");
        out.println("  --filter EXPR          AMPS content filter; required for --mode query");
        out.println("  --mode MODE            snapshot | snapshot-subscribe | query  (default snapshot)");
        out.println("  --output FMT           raw | nvfix  (default raw)");
        out.println("  --max N                stop after N data messages (0 = unlimited)");
        out.println("  --timeout-ms MS        command / idle timeout (default 10000 or AMPS_TIMEOUT_MS)");
        out.println("  --client-name NAME     AMPS client name (default amps-cli)");
        out.println("  --help                 this text");
    }
}
