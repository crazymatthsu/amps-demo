package com.demo.amps.ha;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Where the pair is and what to publish, from {@code -Dha.*} system
 * properties, then {@code HA_*} environment variables, then defaults.
 *
 * @param uris          both instances' client URIs, in the order the HA client
 *                      tries them. The defaults are the compose stack's host ports.
 * @param topic         the replicated SOW topic
 * @param count         how many messages the publisher sends
 * @param interval      pause between publishes, so a human has time to pull a plug
 * @param publisherName the publisher's client name -- the identity AMPS
 *                      de-duplicates a replay under, so it must be stable
 * @param consumerName  the consumer's client name
 * @param bookmark      where the consumer starts: {@code now}, {@code epoch} or {@code recent}
 * @param stateDir      where file-backed publish and bookmark stores live, or
 *                      {@code null} for in-memory stores
 * @param run           a tag for this publisher run, part of every record's SOW key
 */
public record HaSettings(List<String> uris, String topic, long count, Duration interval,
                         String publisherName, String consumerName, String bookmark,
                         Path stateDir, String run) {

    public static final String DEFAULT_URIS =
            "tcp://127.0.0.1:9007/amps/json,tcp://127.0.0.1:9107/amps/json";

    /** From the JVM's system properties and environment. */
    public static HaSettings fromEnvironment() {
        return from(System::getProperty, System.getenv()::get);
    }

    /**
     * From explicit lookups, so a test can drive it without touching globals.
     *
     * @param property {@code ha.<key>} lookups, returning {@code null} when unset
     * @param env      {@code HA_<KEY>} lookups, likewise
     */
    public static HaSettings from(Function<String, String> property, Function<String, String> env) {
        Function<String, String> lookup = key -> {
            String fromProperty = property.apply("ha." + key);
            if (fromProperty != null && !fromProperty.isBlank()) {
                return fromProperty.trim();
            }
            String fromEnv = env.apply(envName(key));
            return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.trim();
        };
        String uris = or(lookup.apply("uris"), DEFAULT_URIS);
        String stateDir = lookup.apply("stateDir");
        String bookmark = or(lookup.apply("bookmark"), "now").toLowerCase(Locale.ROOT);
        if (!List.of("now", "epoch", "recent").contains(bookmark)) {
            throw new IllegalArgumentException("ha.bookmark must be now, epoch or recent, not '" + bookmark + "'");
        }
        return new HaSettings(
                Arrays.stream(uris.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                or(lookup.apply("topic"), "orders"),
                Long.parseLong(or(lookup.apply("count"), "3000")),
                Duration.ofMillis(Long.parseLong(or(lookup.apply("intervalMs"), "20"))),
                or(lookup.apply("publisher"), "ha-demo-publisher"),
                or(lookup.apply("consumer"), "ha-demo-consumer"),
                bookmark,
                stateDir == null ? null : Path.of(stateDir),
                or(lookup.apply("run"), LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))));
    }

    /** The AMPS bookmark string for {@link #bookmark()}. */
    public String bookmarkValue() {
        return switch (bookmark) {
            case "epoch" -> com.crankuptheamps.client.Client.Bookmarks.EPOCH;
            case "recent" -> com.crankuptheamps.client.Client.Bookmarks.MOST_RECENT;
            default -> com.crankuptheamps.client.Client.Bookmarks.NOW;
        };
    }

    /** {@code intervalMs} is {@code HA_INTERVAL_MS}: camel case becomes upper snake case. */
    static String envName(String key) {
        return "HA_" + key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT)
                .replace('.', '_').replace('-', '_');
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
