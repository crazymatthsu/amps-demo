package com.demo.amps.connectors.source;

import com.demo.amps.connectors.config.ConnectorProperties;
import java.util.function.Predicate;

/**
 * A {@link SourceFactory} that hands out one {@link FakeRecordSource} the test already holds.
 *
 * <p>Two shapes, for the two kinds of test. Given no predicate it claims everything, which is
 * what a test wiring a whole connector wants: whatever the configuration says its transport
 * is, the source it gets is the one the test can push records into. Given a predicate it
 * claims only what the predicate says, which is what a test about <em>resolution</em> wants --
 * that is where "no factory claims this block" has to be reachable.
 */
public class FakeSourceFactory implements SourceFactory {

    private final FakeRecordSource source;
    private final Predicate<ConnectorProperties> supports;

    /** Claims every connector. */
    public FakeSourceFactory(FakeRecordSource source) {
        this(source, connector -> true);
    }

    /** Claims the connectors {@code supports} accepts. */
    public FakeSourceFactory(FakeRecordSource source, Predicate<ConnectorProperties> supports) {
        this.source = source;
        this.supports = supports;
    }

    @Override
    public boolean supports(ConnectorProperties connector) {
        return supports.test(connector);
    }

    @Override
    public FakeRecordSource create(ConnectorProperties connector) {
        return source;
    }

    /** The source this factory hands out, for the assertions afterwards. */
    public FakeRecordSource source() {
        return source;
    }
}
