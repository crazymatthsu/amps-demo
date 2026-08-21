package com.demo.amps.fix42;

import com.demo.amps.fix42.mock.FixEvent;
import com.demo.amps.fix42.mock.MockFixFlow;
import com.demo.amps.fix42.publish.AmpsDeltaPublisher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Publishes the scripted mock flow once, then lets the application exit.
 *
 * <p>Excluded from the {@code test} profile so the integration suite can drive
 * the publisher itself and assert between steps, rather than racing a runner
 * that fires on context start.
 */
@Component
@Profile("!test")
public class MockFlowRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MockFlowRunner.class);

    private final AmpsDeltaPublisher publisher;

    public MockFlowRunner(AmpsDeltaPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<FixEvent> events = MockFixFlow.events();
        log.info("publishing {} FIX 4.2 messages across {} order chains",
                events.size(), MockFixFlow.chains().size());

        String chain = null;
        for (FixEvent event : events) {
            if (!event.chainId().equals(chain)) {
                chain = event.chainId();
                log.info("--- {} ({}) ---", chain, event.scope().token());
            }
            log.info("35={} {}", event.msgType(), event.description());
            publisher.send(event.message());
        }

        publisher.flush();
        log.info("done: {} full publishes, {} delta publishes",
                publisher.fullPublishCount(), publisher.deltaPublishCount());
        log.info("inspect the result: the admin SQL console at http://127.0.0.1:8085/ "
                + "can SELECT from any of these topics");
    }
}
