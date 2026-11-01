package com.demo.amps.fix42.state;

import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.mock.EdgeCaseFlow;
import com.demo.amps.fix42.state.mock.FixEvent;
import com.demo.amps.fix42.state.mock.MockFixFlow;
import com.demo.amps.fix42.state.publish.OrderStatePublisher;
import com.demo.amps.fix42.state.spi.FeedAdapter;
import com.demo.amps.fix42.state.spi.MessageSink;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The demo's feed: the scripted flow's ten chains, then every edge case of
 * docs/06 the mock venue scripts, published once, then the application exits.
 *
 * <p>A {@link FeedAdapter} like a real session's: one thread, in order, each
 * message handed over as bytes and "acknowledged" -- here, simply followed by
 * the next -- only once {@link MessageSink#onMessage} returns. Excluded from
 * the {@code test} profile, so the suites drive the publisher themselves.
 */
@Component
@Profile("!test")
public class MockFlowRunner implements FeedAdapter, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MockFlowRunner.class);

    private final OrderStatePublisher publisher;

    public MockFlowRunner(OrderStatePublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        run(publisher);
        publisher.flush();
        log.info("done: {}", publisher.summary());
        log.info("inspect the result: the admin SQL console at http://127.0.0.1:8085/ can SELECT from "
                + "sow/fix42/order_state; the two-cancels orders are /13011 = 'EC-E1-1' and 'EC-E2-1'");
    }

    @Override
    public void run(MessageSink sink) throws Exception {
        List<FixEvent> events = EdgeCaseFlow.scriptedThenEdgeCases();
        log.info("publishing {} FIX 4.2 messages: the {} scripted chains, then {} edge-case chains",
                events.size(), MockFixFlow.chains().size(), EdgeCaseFlow.chains().size());
        FixWriter bytes = new FixWriter(512);
        String chain = null;
        for (FixEvent event : events) {
            if (!event.chainId().equals(chain)) {
                chain = event.chainId();
                log.info("--- {} ---", chain);
            }
            bytes.clear();
            event.message().writeTo(bytes);
            sink.onMessage(bytes.buffer(), 0, bytes.size());
        }
    }
}
