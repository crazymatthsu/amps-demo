package com.demo.amps.fix42.state.it;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.HAClient;
import com.demo.amps.fix42.state.config.AmpsClientConfiguration;
import com.demo.amps.fix42.state.config.OrderStateProperties;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.RecordWriter;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.publish.AmpsPublishSink;
import com.demo.amps.fix42.state.publish.AuditRouter;
import com.demo.amps.fix42.state.publish.OrderStatePublisher;
import com.demo.amps.fix42.state.publish.RepairQueue;
import com.demo.amps.fix42.state.recovery.MissResolver;
import com.demo.amps.fix42.state.recovery.SowCacheLoader;
import com.demo.amps.fix42.state.spi.OrderStateHook;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.util.List;
import java.util.Optional;

/**
 * One AMPS instance on the {@code fix42-order-state} flow, and a publisher
 * wired exactly as {@code Fix42OrderStatePublisherApplication} wires it --
 * the shipped {@code application.yml} bound under the {@code demo} profile,
 * an {@code HAClient} with a memory store -- plus a plain reader client.
 *
 * <p>Skipped, not failed, when no AMPS image is configured.
 */
final class OrderStateFixture implements AutoCloseable {

    static final String BLOTTER = "sow/fix42/order_state";
    static final String ORDERS_AUDIT = "sow/fix42/orders_audit";
    static final String EXECS = "sow/fix42/execs";
    static final String EXECS_AUDIT = "sow/fix42/execs_audit";
    static final String REJECTS = "sow/fix42/rejects";
    static final String UNROUTED = "fix42/unrouted";
    static final String MALFORMED = "fix42/malformed";

    final AmpsTestServer server;
    final OrderStateProperties properties;
    final RepairQueue repairs = new RepairQueue();
    HAClient client;
    OrderStatePublisher publisher;
    Client reader;
    Client jsonReader;
    SowReader sow;
    SowReader views;
    SowCacheLoader.Report loaded;

    private OrderStateFixture(AmpsTestServer server, OrderStateProperties properties) {
        this.server = server;
        this.properties = properties;
    }

    /** Skips the calling test class when there is no AMPS to run against. */
    static void assumeAvailable() {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.FIX42_ORDER_STATE);
        assumeThat(unavailable).as("integration test prerequisites: %s", unavailable.orElse("")).isEmpty();
    }

    /**
     * A fresh instance and a publisher on it.
     *
     * @param profile   {@code demo} (strict, memory store) or null (lenient, overridden to a memory store)
     * @param overrides {@code key=value} under {@code fix42-order-state.}
     */
    static OrderStateFixture start(String clientName, String profile, String... overrides) throws Exception {
        assumeAvailable();
        AmpsTestServer server = AmpsTestServer.start(AmpsFlow.FIX42_ORDER_STATE);
        OrderStateProperties properties = settings(server, clientName, profile, overrides);
        properties.validate();
        OrderStateFixture fixture = new OrderStateFixture(server, properties);
        try {
            fixture.connect(List.of());
        } catch (Exception | Error e) {
            fixture.close();
            throw e;
        }
        return fixture;
    }

    /** The shipped configuration for {@code server}, with a memory store and {@code overrides}. */
    static OrderStateProperties settings(AmpsTestServer server, String clientName, String profile,
                                         String... overrides) {
        String[] all = new String[overrides.length + 2];
        all[0] = "amps.client-name=" + clientName;
        all[1] = "amps.publish-store=MEMORY";
        System.arraycopy(overrides, 0, all, 2, overrides.length);
        OrderStateProperties properties = OrderStateConfigurations.shipped(server.uri(), profile, all);
        properties.validate();
        return properties;
    }

    /**
     * (Re)connects the publisher with a fresh machine, wired as the
     * application wires it -- miss lookups, terminal retention, and the cache
     * rebuilt from the SOW before the first message -- and the readers.
     */
    void connect(List<OrderStateHook> hooks) throws Exception {
        connect(properties, hooks);
    }

    /** {@link #connect(List)} under different settings: a restart with another recovery mode. */
    void connect(OrderStateProperties settings, List<OrderStateHook> hooks) throws Exception {
        client = AmpsClientConfiguration.connect(settings.amps(), repairs);
        OrderStateMachine machine = new OrderStateMachine(settings.machineOptions());
        machine.setTerminalRetention(settings.orderState().terminalRetention(), System::currentTimeMillis);
        if (settings.orderState().missLookup()) {
            machine.setMissLookup(new MissResolver(client, settings));
        }
        try {
            loaded = new SowCacheLoader(settings).load(client, machine);
        } catch (Exception | Error e) {
            client.close();
            client = null;
            throw e;
        }
        publisher = new OrderStatePublisher(machine, new RecordWriter(settings.machineOptions()),
                new AuditRouter(settings), new AmpsPublishSink(client), repairs, hooks, settings);
        if (reader == null) {
            reader = new Client("fix42os-it-reader-" + System.nanoTime());
            reader.connect(server.uri());
            reader.logon(properties.amps().timeoutMs());
            sow = new SowReader(reader, properties.amps().timeoutMs());
            jsonReader = new Client("fix42os-it-json-" + System.nanoTime());
            jsonReader.connect(server.uri().replace("/amps/fix", "/amps/json"));
            jsonReader.logon(properties.amps().timeoutMs());
            views = new SowReader(jsonReader, properties.amps().timeoutMs());
        }
    }

    /** New reader clients: a server restart drops the old ones, which are plain clients. */
    void reconnectReaders() throws Exception {
        for (Client c : new Client[] {reader, jsonReader}) {
            if (c != null) {
                c.close();
            }
        }
        reader = new Client("fix42os-it-reader-" + System.nanoTime());
        reader.connect(server.uri());
        reader.logon(properties.amps().timeoutMs());
        sow = new SowReader(reader, properties.amps().timeoutMs());
        jsonReader = new Client("fix42os-it-json-" + System.nanoTime());
        jsonReader.connect(server.uri().replace("/amps/fix", "/amps/json"));
        jsonReader.logon(properties.amps().timeoutMs());
        views = new SowReader(jsonReader, properties.amps().timeoutMs());
    }

    /** Every event through the publisher, as a feed adapter would deliver them: bytes, one at a time. */
    void publish(List<FixEvent> events) throws Exception {
        FixWriter bytes = new FixWriter(512);
        for (FixEvent event : events) {
            bytes.clear();
            event.message().writeTo(bytes);
            publisher.onMessage(bytes.buffer(), 0, bytes.size());
        }
    }

    void flush() throws Exception {
        publisher.flush();
    }

    /** Closes the publisher's client (not the server), as a publisher restart would. */
    void disconnectPublisher() throws Exception {
        if (publisher != null) {
            publisher.close();
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Override
    public void close() {
        try {
            disconnectPublisher();
        } catch (Exception ignored) {
            // the instance is going away regardless
        }
        for (Client c : new Client[] {reader, jsonReader}) {
            if (c != null) {
                c.close();
            }
        }
        if (server != null) {
            server.close();
        }
    }
}
