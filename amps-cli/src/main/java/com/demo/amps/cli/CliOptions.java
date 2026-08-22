package com.demo.amps.cli;

import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Topics;
import java.util.Locale;

/**
 * Resolved CLI settings. Flags win; otherwise the same {@link DemoConfig} /
 * {@code AMPS_*} knobs the rest of the repo uses.
 */
public record CliOptions(
        String uri,
        String topic,
        String filter,
        Mode mode,
        OutputFormat output,
        int maxMessages,
        long timeoutMillis,
        String messageType,
        String clientName) {

    public enum Mode {
        SNAPSHOT,
        SNAPSHOT_SUBSCRIBE,
        QUERY
    }

    public enum OutputFormat {
        RAW,
        NVFIX
    }

    public static CliOptions from(CliArgs args) {
        String messageType = args.get("message-type", "fix");
        String uri = args.has("url") ? args.get("url", "") : DemoConfig.uri(messageType);
        String filter = args.has("filter") ? args.get("filter", "") : "";
        return new CliOptions(
                uri,
                args.get("topic", Topics.FIX_NATIVE_ORDERS),
                filter,
                parseMode(args.get("mode", "snapshot")),
                parseOutput(args.get("output", "raw")),
                args.getInt("max", 0),
                args.has("timeout-ms")
                        ? args.getLong("timeout-ms", DemoConfig.timeoutMillis())
                        : DemoConfig.timeoutMillis(),
                messageType,
                args.get("client-name", "amps-cli"));
    }

    public void validate() {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("--url must not be blank");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("--topic must not be blank");
        }
        if (mode == Mode.QUERY && (filter == null || filter.isBlank())) {
            throw new IllegalArgumentException("--mode query requires --filter");
        }
        if (maxMessages < 0) {
            throw new IllegalArgumentException("--max must be >= 0");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("--timeout-ms must be > 0");
        }
    }

    private static Mode parseMode(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (key) {
            case "snapshot", "sow" -> Mode.SNAPSHOT;
            case "snapshot-subscribe", "sow-and-subscribe", "sow_and_subscribe" ->
                    Mode.SNAPSHOT_SUBSCRIBE;
            case "query" -> Mode.QUERY;
            default -> throw new IllegalArgumentException(
                    "unknown --mode '" + raw + "' (snapshot | snapshot-subscribe | query)");
        };
    }

    private static OutputFormat parseOutput(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "raw", "fix" -> OutputFormat.RAW;
            case "nvfix" -> OutputFormat.NVFIX;
            default -> throw new IllegalArgumentException(
                    "unknown --output '" + raw + "' (raw | nvfix)");
        };
    }
}
