package com.demo.amps.ha;

import com.demo.amps.ha.api.BookmarkStart;
import com.demo.amps.ha.api.RecoveryPointTopic;
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
 * @param uris               both instances' client URIs, in the order the HA client
 *                           tries them. The defaults are the compose stack's host ports.
 * @param topic              the replicated SOW topic
 * @param count              how many messages the publisher sends
 * @param interval           pause between publishes, so a human has time to pull a plug
 * @param publisherName      the publisher's client name -- the identity AMPS
 *                           de-duplicates a replay under, so it must be stable
 * @param consumerName       the consumer's client name -- also what its recovery
 *                           point is filed under in {@code recoveryPointTopic}, so
 *                           a replacement consumer must use the same one
 * @param bookmark           where the consumer starts: {@code now}, {@code epoch} or
 *                           {@code recent}. {@code recent} resumes where the bookmark
 *                           store says, which after a restart is what the recovery
 *                           point topic holds; {@code epoch} starts over, and deletes
 *                           that recovery point first
 * @param stateDir           where file-backed publish and bookmark stores live, or
 *                           {@code null} for in-memory stores
 * @param run                a tag for this publisher run, part of every record's SOW key
 * @param recoveryPointTopic the SOW topic the consumer's bookmarks are replicated to,
 *                           through the AMPS client's {@code SOWRecoveryPointAdapter},
 *                           so that a consumer restarted on another machine resumes
 *                           from it; {@code null} ({@code ha.recoveryPointTopic=none})
 *                           keeps bookmarks in the local store only
 */
public record HaSettings(List<String> uris, String topic, long count, Duration interval,
                         String publisherName, String consumerName, String bookmark,
                         Path stateDir, String run, String recoveryPointTopic) {

    public static final String DEFAULT_URIS =
            "tcp://127.0.0.1:9007/amps/json,tcp://127.0.0.1:9107/amps/json";

    /**
     * The AMPS Java client's default topic for a {@code SOWRecoveryPointAdapter}.
     * Both instance configs define it: a JSON SOW topic keyed on
     * {@code /clientName} and {@code /subId}, journaled and replicated.
     */
    public static final String DEFAULT_RECOVERY_POINT_TOPIC = RecoveryPointTopic.DEFAULT_TOPIC;

    /** The {@code ha.recoveryPointTopic} value that keeps bookmarks local. */
    public static final String NO_RECOVERY_POINT_TOPIC = "none";

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
        String recoveryPointTopic = or(lookup.apply("recoveryPointTopic"), DEFAULT_RECOVERY_POINT_TOPIC);
        if (NO_RECOVERY_POINT_TOPIC.equalsIgnoreCase(recoveryPointTopic)) {
            recoveryPointTopic = null;
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
                or(lookup.apply("run"), LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))),
                recoveryPointTopic);
    }

    /** Where the consumer starts, for {@link #bookmark()}. */
    public BookmarkStart bookmarkStart() {
        return BookmarkStart.parse(bookmark);
    }

    /**
     * {@link #recoveryPointTopic()} as the subscriber's builder takes it, with
     * the adapter's default field names; {@code null} keeps bookmarks local.
     */
    public RecoveryPointTopic recoveryPoint() {
        return recoveryPointTopic == null ? null : RecoveryPointTopic.of(recoveryPointTopic);
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
