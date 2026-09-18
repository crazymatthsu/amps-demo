package com.demo.amps.qfj2.amps;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.exception.AMPSException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects an AMPS client, retrying while the server is still coming up.
 *
 * <p>Both of the engine's connections go through here. In the compose stack
 * the engines start alongside AMPS, and an AMPS that is recovering its SOW is
 * not yet accepting logons; a FIX engine that died on the first refused
 * connection would have to be restarted by hand. The budget is bounded so a
 * misconfigured URI still fails, with the last reason attached.
 */
public final class AmpsClients {

    private static final Logger log = LoggerFactory.getLogger(AmpsClients.class);

    private static final Duration RETRY_PAUSE = Duration.ofSeconds(2);

    private AmpsClients() {
    }

    /**
     * A connected, logged-on client.
     *
     * @param clientName     the client's identity to the server
     * @param uri            {@code tcp://host:port/amps/<messageType>}
     * @param logonTimeoutMs how long one logon attempt may take
     * @param retryBudget    how long to keep retrying a refused connection
     */
    public static Client connect(String clientName, String uri, long logonTimeoutMs, Duration retryBudget)
            throws AMPSException {
        Instant deadline = Instant.now().plus(retryBudget);
        int attempt = 0;
        while (true) {
            attempt++;
            Client client = new Client(clientName);
            AMPSException failure;
            try {
                client.connect(uri);
                client.logon(logonTimeoutMs);
                log.info("connected to AMPS at {} as '{}'{}", uri, clientName,
                        attempt > 1 ? " (attempt " + attempt + ")" : "");
                return client;
            } catch (AMPSException e) {
                failure = e;
                client.close();
            }
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw failure;
            }
            log.warn("AMPS at {} not reachable as '{}' ({}); retrying for another {}s",
                    uri, clientName, failure.getMessage(), remaining.toSeconds());
            try {
                Thread.sleep(Math.min(RETRY_PAUSE.toMillis(), Math.max(1, remaining.toMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure;
            }
        }
    }
}
