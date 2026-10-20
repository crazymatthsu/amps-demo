package com.demo.amps.ha.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RecoveryPointTopicTest {

    @Test
    void defaultsAreTheClientLibrarysOwn() {
        RecoveryPointTopic topic = RecoveryPointTopic.defaults();
        assertEquals("/ADMIN/bookmark_store", topic.topic());
        assertEquals("clientName", topic.clientNameField());
        assertEquals("subId", topic.subIdField());
        assertEquals("bookmark", topic.bookmarkField());
        assertEquals(Duration.ofSeconds(10), topic.timeout());
        assertEquals(topic, RecoveryPointTopic.of(RecoveryPointTopic.DEFAULT_TOPIC));
    }

    @Test
    void anotherTopicKeepsTheDefaultFieldNames() {
        RecoveryPointTopic topic = RecoveryPointTopic.of("/ops/bookmarks").withTimeout(Duration.ofSeconds(2));
        assertEquals("/ops/bookmarks", topic.topic());
        assertEquals("clientName", topic.clientNameField());
        assertEquals(Duration.ofSeconds(2), topic.timeout());
    }

    @Test
    void theFilterSelectsOneClientAndEscapesQuotes() {
        assertEquals("/clientName = 'orders-consumer'", RecoveryPointTopic.defaults().filterFor("orders-consumer"));
        assertEquals("/owner = 'o''brien'",
                new RecoveryPointTopic("/t", "owner", "subId", "bookmark", Duration.ofSeconds(1)).filterFor("o'brien"));
    }

    @Test
    void blanksAndNonPositiveTimeoutsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RecoveryPointTopic.of(""));
        assertThrows(IllegalArgumentException.class,
                () -> new RecoveryPointTopic("/t", " ", "subId", "bookmark", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> RecoveryPointTopic.defaults().withTimeout(Duration.ZERO));
        assertThrows(NullPointerException.class, () -> RecoveryPointTopic.defaults().withTimeout(null));
    }
}
