package com.demo.amps.qfj2.config;

import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.qfj2.amps.AmpsPublisher;
import com.demo.amps.qfj2.amps.ReconnectingAmpsPublisher;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * The connection the AMPS destinations publish on: {@code /amps/fix}, since
 * the drop-copy topics are fix-typed, wrapped so it is reopened after a
 * failure. Lazy, so an instance whose flow has no AMPS destination never
 * opens it.
 *
 * <p>The checkpoint store's own {@code /amps/json} connection is not a bean;
 * {@code AmpsSeqnoReplicator} owns it, for the same reopen-on-failure reason.
 */
@Configuration
public class AmpsClientConfiguration {

    @Bean(destroyMethod = "close")
    @Lazy
    public AmpsPublisher ampsFixPublisher(QfjProperties properties) throws AMPSException {
        QfjProperties.Amps settings = properties.amps();
        if (!settings.uri().endsWith("/fix")) {
            throw new IllegalStateException("qfj.amps.uri must end /amps/fix; got " + settings.uri());
        }
        return new ReconnectingAmpsPublisher(settings.clientName(), settings.uri(), settings.timeoutMs(),
                Duration.ofMillis(settings.connectWaitMs()), Duration.ofMillis(settings.reconnectWaitMs()));
    }
}
