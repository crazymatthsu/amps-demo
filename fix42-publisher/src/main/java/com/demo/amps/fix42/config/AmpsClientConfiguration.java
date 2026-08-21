package com.demo.amps.fix42.config;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.exception.AMPSException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The AMPS connection, as a Spring bean.
 *
 * <p>The message type is part of the URI, not of any later command:
 * {@code tcp://host:9007/amps/fix} says "AMPS protocol, FIX payloads", and one
 * transport on the server serves every type. Connecting with {@code /amps/json}
 * against these topics fails in a confusing way, so the check below is worth
 * its three lines.
 */
@Configuration
public class AmpsClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AmpsClientConfiguration.class);

    /**
     * A connected, logged-on client.
     *
     * <p>{@code destroyMethod = "close"} hands the lifecycle to Spring: the
     * context closing disconnects, which matters for the short-lived runs this
     * application is built for.
     */
    @Bean(destroyMethod = "close")
    public Client ampsClient(Fix42Properties properties) throws AMPSException {
        Fix42Properties.Amps settings = properties.amps();
        if (!settings.uri().endsWith("/fix")) {
            throw new IllegalStateException("fix42.amps.uri must select the 'fix' message type "
                    + "(a URI ending /amps/fix), because every topic this publisher writes to is "
                    + "declared <MessageType>fix</MessageType>. Got: " + settings.uri());
        }

        Client client = new Client(settings.clientName());
        try {
            client.connect(settings.uri());
            client.logon(settings.timeoutMs());
        } catch (AMPSException e) {
            client.close();
            throw e;
        }
        log.info("connected to AMPS at {} as '{}'", settings.uri(), settings.clientName());
        return client;
    }
}
