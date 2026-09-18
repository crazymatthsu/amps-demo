package com.demo.amps.qfj2.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.demo.amps.qfj2.amps.AmpsPublisher;
import com.demo.amps.qfj2.engine.Direction;
import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.mock.ExecutionReports;
import com.demo.amps.qfj2.support.RecordingDestination;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.fix42.NewOrderSingle;

class DestinationDispatcherTest {

    private static final SessionID SESSION = new SessionID("FIX.4.2", "DROPCOPY", "VENUE");
    private static final FixContext CONTEXT = new FixContext(SESSION, Direction.INBOUND, Instant.now());

    @Test
    @DisplayName("every destination whose filter matches gets the message")
    void deliversToEveryAcceptingDestination() {
        RecordingDestination all = new RecordingDestination("all");
        RecordingDestination execs = new RecordingDestination("execs", Set.of("8"));
        DestinationDispatcher dispatcher = new DestinationDispatcher(List.of(execs, all));

        dispatcher.dispatch(ExecutionReports.sample("T", 1, "AAPL"), CONTEXT);
        dispatcher.dispatch(new NewOrderSingle(), CONTEXT);

        assertThat(all.count()).isEqualTo(2);
        assertThat(execs.count()).isEqualTo(1);
        assertThat(dispatcher.deliveredCount("all")).isEqualTo(2);
        assertThat(dispatcher.deliveredCount("execs")).isEqualTo(1);
        assertThat(dispatcher.dispatchedCount()).isEqualTo(2);
        assertThat(dispatcher.unroutedCount()).isZero();
    }

    @Test
    @DisplayName("a message no destination accepts is counted, not an error")
    void countsUnroutedMessages() {
        DestinationDispatcher dispatcher = new DestinationDispatcher(List.of(new RecordingDestination("execs", Set.of("8"))));
        dispatcher.dispatch(new NewOrderSingle(), CONTEXT);
        assertThat(dispatcher.unroutedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the first failure stops the dispatch and names the destination")
    void stopsAtTheFirstFailureAndNamesIt() {
        RecordingDestination first = new RecordingDestination("first");
        RecordingDestination second = new RecordingDestination("second");
        first.failNext(1);
        DestinationDispatcher dispatcher = new DestinationDispatcher(List.of(first, second));

        assertThatThrownBy(() -> dispatcher.dispatch(ExecutionReports.sample("T", 1, "AAPL"), CONTEXT))
                .isInstanceOf(DeliveryException.class)
                .hasMessageContaining("destination 'first' failed for 35=8")
                .extracting(e -> ((DeliveryException) e).destination()).isEqualTo("first");
        assertThat(first.count()).isZero();
        assertThat(second.count()).as("not reached: the message will be redelivered whole").isZero();
        assertThat(dispatcher.deliveredCount("first")).isZero();
    }

    @Test
    @DisplayName("an AMPS destination publishes the raw wire form, flushing when told to")
    void ampsDestinationPublishesTheWireFormAndFlushes() throws Exception {
        AmpsPublisher publisher = mock(AmpsPublisher.class);
        Message report = ExecutionReports.sample("T", 7, "MSFT");
        new AmpsDestination("blotter", publisher, "sow/dropcopy/fix42/execs", Set.of("8"), true)
                .deliver(report, CONTEXT);
        verify(publisher).publish(eq("sow/dropcopy/fix42/execs"), eq(report.toString()), eq(true));

        AmpsPublisher unflushed = mock(AmpsPublisher.class);
        new AmpsDestination("audit", unflushed, "dropcopy/fix42/audit", Set.of(), false)
                .deliver(report, CONTEXT);
        verify(unflushed).publish(eq("dropcopy/fix42/audit"), anyString(), eq(false));
    }

    @Test
    @DisplayName("an AMPS failure surfaces as a delivery failure for that destination")
    void ampsFailurePropagates() throws Exception {
        AmpsPublisher publisher = mock(AmpsPublisher.class);
        doThrow(new IllegalStateException("boom")).when(publisher).publish(anyString(), anyString(), anyBoolean());
        DestinationDispatcher dispatcher = new DestinationDispatcher(List.of(
                new AmpsDestination("blotter", publisher, "t", Set.of(), true)));
        assertThatThrownBy(() -> dispatcher.dispatch(ExecutionReports.sample("T", 1, "AAPL"), CONTEXT))
                .isInstanceOf(DeliveryException.class)
                .hasMessageContaining("blotter")
                .hasRootCauseMessage("boom");
    }

    @Test
    @DisplayName("a FIX destination never echoes onto the session the message came from")
    void fixDestinationSkipsTheSourceSession() throws Exception {
        FixSessionDestination destination = new FixSessionDestination("echo", SESSION, Set.of());
        // No session is registered, so a real send would throw SessionNotFound.
        destination.deliver(ExecutionReports.sample("T", 1, "AAPL"), CONTEXT);
    }

    @Test
    @DisplayName("a FIX destination whose session does not exist fails rather than dropping the message")
    void fixDestinationFailsWhenTargetSessionUnknown() {
        FixSessionDestination destination =
                new FixSessionDestination("downstream", new SessionID("FIX.4.2", "DROPCOPY", "NOWHERE"), Set.of());
        assertThatThrownBy(() -> destination.deliver(ExecutionReports.sample("T", 1, "AAPL"), CONTEXT))
                .isInstanceOf(SessionNotFound.class);
    }
}
