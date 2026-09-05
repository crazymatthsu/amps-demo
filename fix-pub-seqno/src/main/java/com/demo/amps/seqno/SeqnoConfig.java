package com.demo.amps.seqno;

import com.demo.amps.common.DemoConfig;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Everything the publisher, the recovery and the subscriber need to know,
 * as one immutable value.
 *
 * <p>Resolved from system properties and environment variables by
 * {@link #fromEnvironment()} for the demo, and constructed directly by the
 * integration test with the throwaway container's URI and a private state
 * directory. System properties win over environment variables, as in
 * {@link DemoConfig}: {@code -Dseqno.sender=PUB-B} points one run at another
 * sender without exporting anything.
 *
 * @param uri           the AMPS URI, which must select the {@code fix}
 *                      message type ({@code .../amps/fix}) because the topic
 *                      is declared {@code <MessageType>fix</MessageType>}
 * @param topic         the journalled, sender-keyed topic
 * @param sender        tag 49 on every message this publisher sends; the
 *                      identity AMPS keys the checkpoint on, and the stem of
 *                      the publisher's stable client name
 * @param stateDir      where the outbox, the bookmark store and the
 *                      subscriber's high-water marks live
 * @param timeoutMillis command timeout
 * @param idleMillis    how long a bookmark scan waits with nothing arriving
 *                      before deciding it has reached the head of the journal
 * @param lookback      how far back the verification scan starts when the SOW
 *                      already has an answer; must exceed the longest outage
 *                      the publisher may have had
 */
public record SeqnoConfig(String uri, String topic, String sender, Path stateDir,
                          long timeoutMillis, int idleMillis, Duration lookback) {

    public static final String DEFAULT_TOPIC = "fix/seqno/orders";
    public static final String DEFAULT_SENDER = "PUB-A";

    public SeqnoConfig {
        if (!uri.endsWith("/fix")) {
            throw new IllegalArgumentException("the AMPS URI must select the 'fix' message type "
                    + "(end with /amps/fix), because " + topic + " is a fix-typed topic; got " + uri);
        }
        if (sender == null || sender.isBlank()) {
            throw new IllegalArgumentException("sender (tag 49) must not be blank");
        }
    }

    /** The demo's settings: AMPS_HOST / AMPS_PORT from {@link DemoConfig}, plus the seqno.* overrides. */
    public static SeqnoConfig fromEnvironment() {
        return new SeqnoConfig(
                get("seqno.uri", "SEQNO_URI", DemoConfig.uri("fix")),
                get("seqno.topic", "SEQNO_TOPIC", DEFAULT_TOPIC),
                get("seqno.sender", "SEQNO_SENDER", DEFAULT_SENDER),
                Path.of(get("seqno.stateDir", "SEQNO_STATE_DIR",
                        DemoConfig.clientStateDir().resolve("fix-pub-seqno").toString())).toAbsolutePath(),
                DemoConfig.timeoutMillis(),
                Integer.parseInt(get("seqno.idleMs", "SEQNO_IDLE_MS", "3000")),
                Duration.ofHours(Long.parseLong(get("seqno.lookbackHours", "SEQNO_LOOKBACK_HOURS", "24"))));
    }

    /**
     * The publisher's AMPS client name. Derived from the sender and nothing
     * else: a transaction-logged instance keys its per-publisher state on
     * this name, so it has to be the same after every restart, on every host.
     */
    public String publisherClientName() {
        return "fix-pub-" + sender;
    }

    /** The subscriber's client name; equally stable, for its bookmark store. */
    public String subscriberClientName() {
        return "fix-sub-" + topicStem();
    }

    /** The subscription id the bookmark store is keyed on. */
    public String subscriptionId() {
        return subscriberClientName() + "-orders";
    }

    public Path outboxFile() {
        return stateDir.resolve("outbox-" + sender + ".log");
    }

    public Path bookmarkFile() {
        return stateDir.resolve(subscriberClientName() + ".bookmarks");
    }

    public Path highWaterMarkFile() {
        return stateDir.resolve(subscriberClientName() + ".hwm.properties");
    }

    public SeqnoConfig withSender(String newSender) {
        return new SeqnoConfig(uri, topic, newSender, stateDir, timeoutMillis, idleMillis, lookback);
    }

    public SeqnoConfig withLookback(Duration newLookback) {
        return new SeqnoConfig(uri, topic, sender, stateDir, timeoutMillis, idleMillis, newLookback);
    }

    private String topicStem() {
        return topic.replace('/', '-');
    }

    private static String get(String systemProperty, String environmentVariable, String fallback) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
