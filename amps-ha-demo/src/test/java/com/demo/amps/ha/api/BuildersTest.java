package com.demo.amps.ha.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the builders refuse before they touch the network. Connecting is the
 * integration test's business.
 */
class BuildersTest {

    @Test
    void aPublisherNeedsANameAndAtLeastOneServer() {
        assertThrows(IllegalArgumentException.class, () -> HaPublisher.builder(" "));
        assertThrows(IllegalArgumentException.class, () -> HaPublisher.builder("p").connect());
        assertThrows(IllegalArgumentException.class, () -> HaPublisher.builder("p").uris(List.of()).connect());
        assertThrows(IllegalArgumentException.class, () -> HaPublisher.builder("p").uris("tcp://a:1/amps/json", "").connect());
        assertThrows(NullPointerException.class, () -> HaPublisher.builder("p").settings(null));
    }

    @Test
    void aSubscriberNeedsANameAndAtLeastOneServer() {
        assertThrows(IllegalArgumentException.class, () -> HaBookmarkSubscriber.builder(""));
        assertThrows(IllegalArgumentException.class, () -> HaBookmarkSubscriber.builder("s").connect());
        assertThrows(IllegalArgumentException.class, () -> HaBookmarkSubscriber.builder("s").trackerSuffix(" "));
        assertThrows(NullPointerException.class, () -> HaBookmarkSubscriber.builder("s").uris((List<String>) null));
    }

    @Test
    void theTrackerIsNamedAfterTheSubscriber() {
        assertEquals("-bookmarks", HaBookmarkSubscriber.DEFAULT_TRACKER_SUFFIX);
    }
}
