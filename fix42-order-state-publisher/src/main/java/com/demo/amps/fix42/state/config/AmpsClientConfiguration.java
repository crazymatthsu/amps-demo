package com.demo.amps.fix42.state.config;

import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.FailedWriteHandler;
import com.crankuptheamps.client.FixedDelayStrategy;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.MemoryPublishStore;
import com.crankuptheamps.client.PublishStore;
import com.crankuptheamps.client.exception.NameInUseException;
import com.demo.amps.fix42.state.publish.RepairQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The AMPS connection: an {@code HAClient} with a publish store (decision 4,
 * docs/04 §6).
 *
 * <p>With a store, every publish is written to the store before it is sent and
 * returns at once; the server acknowledges it as persisted -- for a journalled
 * topic only, which is why the flow journals every topic this client writes --
 * and the acknowledgement discards it; after a reconnect the client replays
 * what is still in the store and the server drops duplicates by client name
 * and sequence. The {@link FailedWriteHandler} reports what the server refused;
 * the {@link RepairQueue} carries it to the publishing thread.
 *
 * <p>The client name is the identity all of this hangs off, so it is stable,
 * and a transaction-logged instance allows one connection per name: a
 * restarted publisher waits for the old session to be reaped
 * ({@code NameInUse}) rather than running beside it, and a second instance
 * started by mistake is refused for the same reason (docs/04 §5).
 */
@Configuration
public class AmpsClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AmpsClientConfiguration.class);

    /** How long a first connect may take before the start fails, against an instance that is not there. */
    private static final int FIRST_CONNECT_BUDGET_MS = 30_000;
    private static final int NAME_IN_USE_ATTEMPTS = 15;
    private static final long NAME_IN_USE_WAIT_MS = 2_000;

    @Bean
    public RepairQueue repairQueue() {
        return new RepairQueue();
    }

    @Bean(destroyMethod = "close")
    public HAClient ampsClient(OrderStateProperties properties, RepairQueue repairs) throws Exception {
        return connect(properties.amps(), repairs);
    }

    /**
     * Builds, connects and logs on, retrying {@code NameInUse} while the
     * previous session under this name is reaped.
     */
    public static HAClient connect(OrderStateProperties.Amps amps, FailedWriteHandler failedWrites) throws Exception {
        if (!amps.uri().endsWith("/fix")) {
            throw new IllegalStateException("fix42-order-state.amps.uri must select the 'fix' message type "
                    + "(a URI ending /amps/fix). Got: " + amps.uri());
        }
        for (int attempt = 1; ; attempt++) {
            HAClient client = new HAClient(amps.clientName());
            try {
                switch (amps.publishStore()) {
                    case FILE -> {
                        Path dir = Path.of(amps.publishStoreDir());
                        Files.createDirectories(dir);
                        client.setPublishStore(new PublishStore(dir.resolve(amps.clientName() + ".publish").toString()));
                    }
                    case MEMORY -> client.setPublishStore(new MemoryPublishStore(10_000));
                    case NONE -> {
                        // At most once: no store, and nothing the server refuses is ever reported.
                    }
                }
                client.setFailedWriteHandler(failedWrites);
                client.setExceptionListener(e -> log.warn("[{}] client reported: {}", amps.clientName(), e.toString()));
                client.setServerChooser(new DefaultServerChooser().add(amps.uri()));
                client.setTimeout((int) amps.timeoutMs());
                // Bounded for the first connect only; once up, an outage is what
                // an HA client is for, and reconnects never give up.
                client.setReconnectDelayStrategy(
                        new FixedDelayStrategy((int) amps.reconnectDelayMs(), FIRST_CONNECT_BUDGET_MS));
                client.connectAndLogon();
                client.setReconnectDelayStrategy(new FixedDelayStrategy((int) amps.reconnectDelayMs()));
                log.info("connected to AMPS at {} as '{}' with a {} publish store", amps.uri(), amps.clientName(),
                        amps.publishStore());
                return client;
            } catch (Exception e) {
                client.close();
                if (nameInUse(e) && attempt < NAME_IN_USE_ATTEMPTS) {
                    log.info("client name '{}' is still in use (the previous session is not yet reaped); "
                            + "retrying in {} ms", amps.clientName(), NAME_IN_USE_WAIT_MS);
                    Thread.sleep(NAME_IN_USE_WAIT_MS);
                    continue;
                }
                throw e;
            }
        }
    }

    static boolean nameInUse(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NameInUseException) {
                return true;
            }
        }
        return false;
    }
}
