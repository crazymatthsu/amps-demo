package com.demo.amps.seqno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SeqnoConfigTest {

    private static SeqnoConfig config(String uri, String sender) {
        return new SeqnoConfig(uri, SeqnoConfig.DEFAULT_TOPIC, sender, Path.of("build/state"),
                10_000, 3000, Duration.ofHours(24));
    }

    @Test
    void rejectsAUriThatDoesNotSelectTheFixMessageType() {
        // The topic is fix-typed; a /amps/json URI would connect and then fail
        // to parse anything, which is the confusing failure this guards against.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> config("tcp://127.0.0.1:9007/amps/json", "PUB-A"));
        assertTrue(error.getMessage().contains("fix"));
    }

    @Test
    void rejectsABlankSender() {
        assertThrows(IllegalArgumentException.class, () -> config("tcp://h:9007/amps/fix", " "));
    }

    @Test
    void derivesStableClientAndSubscriptionNamesFromIdentity() {
        SeqnoConfig config = config("tcp://127.0.0.1:9007/amps/fix", "PUB-A");
        // The publisher's client name is a pure function of the sender: a
        // transaction-logged instance correlates its per-publisher state on it,
        // so it must be the same after every restart.
        assertEquals("fix-pub-PUB-A", config.publisherClientName());
        assertEquals(config.publisherClientName(), config("tcp://127.0.0.1:9007/amps/fix", "PUB-A")
                .publisherClientName());
        assertTrue(config.subscriptionId().startsWith(config.subscriberClientName()));
        assertTrue(config.outboxFile().toString().contains("PUB-A"));
    }

    @Test
    void withSenderChangesOnlyTheSender() {
        SeqnoConfig base = config("tcp://127.0.0.1:9007/amps/fix", "PUB-A");
        SeqnoConfig other = base.withSender("PUB-B");
        assertEquals("PUB-B", other.sender());
        assertEquals(base.topic(), other.topic());
        assertEquals(base.uri(), other.uri());
    }
}
