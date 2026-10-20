package com.demo.amps.ha.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crankuptheamps.client.ConnectionStateListener;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConnectionJournalTest {

    private static final String PRIMARY = "tcp://primary:9007/amps/json";
    private static final String SECONDARY = "tcp://secondary:9007/amps/json";

    private final AtomicReference<URI> current = new AtomicReference<>();
    private final ConnectionJournal journal = new ConnectionJournal("test", current::get);

    @Test
    void recordsEveryStateWithTheServerForConnectedAndLoggedOn() {
        current.set(URI.create(PRIMARY));
        journal.connectionStateChanged(ConnectionStateListener.Connected);
        journal.connectionStateChanged(ConnectionStateListener.LoggedOn);
        journal.connectionStateChanged(ConnectionStateListener.Disconnected);
        current.set(URI.create(SECONDARY));
        journal.connectionStateChanged(ConnectionStateListener.Connected);
        journal.connectionStateChanged(ConnectionStateListener.LoggedOn);
        journal.connectionStateChanged(ConnectionStateListener.PublishReplayed);
        journal.connectionStateChanged(ConnectionStateListener.Resubscribed);

        List<ConnectionJournal.Event> events = journal.events();
        assertEquals(7, events.size());
        assertEquals("Connected", events.get(0).name());
        assertEquals(PRIMARY, events.get(0).uri());
        assertEquals("Disconnected", events.get(2).name());
        assertNull(events.get(2).uri(), "only Connected and LoggedOn carry the server");
        assertEquals("PublishReplayed", events.get(5).name());
        assertEquals("Resubscribed", events.get(6).name());

        assertEquals(List.of(PRIMARY, SECONDARY), journal.logonUris());
        assertEquals(SECONDARY, journal.lastLogonUri());
        assertEquals(2, journal.count(ConnectionStateListener.LoggedOn));
        assertEquals(1, journal.count(ConnectionStateListener.Disconnected));
        assertTrue(journal.describe().contains("LoggedOn " + SECONDARY));
    }

    @Test
    void isEmptyBeforeTheFirstEvent() {
        assertEquals(List.of(), journal.events());
        assertNull(journal.lastLogonUri());
        assertEquals("", journal.describe());
    }

    @Test
    void aMissingOrFailingUriLookupIsRecordedAsNoServer() {
        journal.connectionStateChanged(ConnectionStateListener.LoggedOn);
        assertNull(journal.events().get(0).uri());
        assertEquals(List.of(), journal.logonUris(), "a logon without a server is not a logon anywhere");
        ConnectionJournal failing = new ConnectionJournal("failing", () -> {
            throw new IllegalStateException("no connection");
        });
        failing.connectionStateChanged(ConnectionStateListener.Connected);
        assertNull(failing.events().get(0).uri());
    }

    @Test
    void awaitsALogonToAServerWhetherItHappenedBeforeOrAfterTheCall() throws Exception {
        current.set(URI.create(PRIMARY));
        journal.connectionStateChanged(ConnectionStateListener.LoggedOn);
        assertTrue(journal.awaitLoggedOnTo("primary:9007", Duration.ofMillis(10)), "already recorded");
        assertFalse(journal.awaitLoggedOnTo("secondary:9007", Duration.ofMillis(50)), "not yet");

        Thread later = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            current.set(URI.create(SECONDARY));
            journal.connectionStateChanged(ConnectionStateListener.LoggedOn);
        });
        later.start();
        assertTrue(journal.awaitLoggedOnTo("secondary:9007", Duration.ofSeconds(5)), "recorded while waiting");
        later.join();
    }

    @Test
    void namesTheListenerConstantsAndTheHostAndPortOfAUri() {
        assertEquals("Shutdown", ConnectionJournal.name(ConnectionStateListener.Shutdown));
        assertEquals("HeartbeatInitiated", ConnectionJournal.name(ConnectionStateListener.HeartbeatInitiated));
        assertEquals("Unknown(4242)", ConnectionJournal.name(4242));
        assertEquals("primary:9007", ConnectionJournal.hostAndPort(PRIMARY));
    }
}
