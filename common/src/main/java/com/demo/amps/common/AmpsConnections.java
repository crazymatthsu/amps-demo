package com.demo.amps.common;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.LoggedBookmarkStore;
import com.crankuptheamps.client.PublishStore;
import com.crankuptheamps.client.exception.AMPSException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Factories for the three client shapes the demos need.
 *
 * <p>The distinction matters more in AMPS than in most messaging systems, because
 * durability is a client-side choice as much as a server-side one:
 *
 * <ul>
 *   <li>{@link #connect} -- plain client, no local state. Fine for queries and for
 *       subscriptions that can miss messages across a disconnect.</li>
 *   <li>{@link #connectWithBookmarkStore} -- keeps a durable record of the last
 *       bookmark this named subscriber consumed, so a bookmark subscription can
 *       resume from exactly where it left off after a crash on either side.</li>
 *   <li>{@link #connectWithPublishStore} -- keeps unpersisted publishes on disk so
 *       a publisher can replay them after a reconnect and lose nothing.</li>
 * </ul>
 */
public final class AmpsConnections {

    private AmpsConnections() {
    }

    /** A plain client, connected and logged on. */
    public static Client connect(String clientName) throws AMPSException {
        Client client = new Client(clientName);
        try {
            client.connect(DemoConfig.uri());
            client.logon(DemoConfig.timeoutMillis());
        } catch (AMPSException e) {
            client.close();
            throw e;
        }
        return client;
    }

    /**
     * An HA client with a file-backed bookmark store.
     *
     * <p>The client name is the identity AMPS and the store use to correlate the
     * subscription across runs, so it must be stable -- a random name would resume
     * nothing. The store file lives under {@link DemoConfig#clientStateDir()}.
     */
    public static HAClient connectWithBookmarkStore(String clientName) throws AMPSException, IOException {
        Path stateDir = DemoConfig.clientStateDir();
        Files.createDirectories(stateDir);
        Path bookmarkFile = stateDir.resolve(clientName + ".bookmarks");

        HAClient client = new HAClient(clientName);
        try {
            client.setBookmarkStore(new LoggedBookmarkStore(bookmarkFile.toString()));
            client.setServerChooser(new DefaultServerChooser().add(DemoConfig.uri()));
            client.connectAndLogon();
        } catch (AMPSException | RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }

    /**
     * An HA client with a file-backed publish store, for at-least-once publishing.
     *
     * <p>Publishes are recorded locally and discarded only once AMPS acknowledges
     * them as persisted, so a reconnect replays anything in doubt. Combined with
     * the server's transaction log this is the AMPS equivalent of an idempotent
     * producer -- the sequence numbers let AMPS drop duplicates on replay.
     */
    public static HAClient connectWithPublishStore(String clientName) throws AMPSException, IOException {
        Path stateDir = DemoConfig.clientStateDir();
        Files.createDirectories(stateDir);
        Path publishFile = stateDir.resolve(clientName + ".publish");

        HAClient client = new HAClient(clientName);
        try {
            client.setPublishStore(new PublishStore(publishFile.toString()));
            client.setServerChooser(new DefaultServerChooser().add(DemoConfig.uri()));
            client.connectAndLogon();
        } catch (AMPSException | RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }
}
