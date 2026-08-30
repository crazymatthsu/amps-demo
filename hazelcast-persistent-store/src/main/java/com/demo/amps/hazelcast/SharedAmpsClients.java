package com.demo.amps.hazelcast;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.PublishStore;
import com.demo.amps.cache.CacheStoreException;
import com.demo.amps.common.DemoConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One AMPS connection per member per instance URI, shared by every map's
 * store -- fifty caches must mean fifty {@code MapStore} instances, never
 * fifty TCP connections.
 *
 * <p>Refcounted: each map's {@code init} acquires, each {@code destroy}
 * releases, and the connection closes when the last map lets go (member
 * shutdown destroys every map store, so the member's connection dies with
 * it).
 *
 * <p>The client is an {@link HAClient} with a file-backed publish store: it
 * reconnects by itself if AMPS restarts under a running member, and replays
 * any publish the server had not acknowledged -- at-least-once across the
 * reconnect, deduplicated server-side by sequence number. The store file
 * lives under {@link DemoConfig#clientStateDir()} keyed by the client name,
 * so a STABLE name (the {@code amps.clientName} property) is what makes
 * replay work across member restarts too; the generated default is unique
 * per process, which is correct for the common case of one member per JVM.
 */
final class SharedAmpsClients {

    private static final Logger log = LoggerFactory.getLogger(SharedAmpsClients.class);
    private static final Map<String, Shared> CLIENTS = new HashMap<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private record Shared(HAClient client, AtomicInteger refs) {
    }

    private SharedAmpsClients() {
    }

    /** The shared client for {@code uri}, connecting it on first acquisition. */
    static synchronized Client acquire(String uri, String clientNameOrNull) {
        Shared shared = CLIENTS.get(uri);
        if (shared == null) {
            String name = clientNameOrNull != null ? clientNameOrNull
                    : "hz-store-" + ProcessHandle.current().pid() + "-"
                            + SEQUENCE.incrementAndGet();
            shared = new Shared(connect(uri, name), new AtomicInteger());
            CLIENTS.put(uri, shared);
            log.info("opened shared AMPS client '{}' for {}", name, uri);
        }
        shared.refs().incrementAndGet();
        return shared.client();
    }

    /** Releases one hold on the {@code uri} client, closing it on the last. */
    static synchronized void release(String uri) {
        Shared shared = CLIENTS.get(uri);
        if (shared == null) {
            return;
        }
        if (shared.refs().decrementAndGet() <= 0) {
            CLIENTS.remove(uri);
            shared.client().close();
            log.info("closed shared AMPS client for {}", uri);
        }
    }

    private static HAClient connect(String uri, String name) {
        HAClient client = new HAClient(name);
        try {
            Path stateDir = DemoConfig.clientStateDir();
            Files.createDirectories(stateDir);
            client.setPublishStore(new PublishStore(
                    stateDir.resolve(name + ".publish").toString()));
            client.setServerChooser(new DefaultServerChooser().add(uri));
            client.connectAndLogon();
            return client;
        } catch (Exception e) {
            client.close();
            throw new CacheStoreException("could not connect AMPS client '" + name
                    + "' to " + uri, e);
        }
    }
}
