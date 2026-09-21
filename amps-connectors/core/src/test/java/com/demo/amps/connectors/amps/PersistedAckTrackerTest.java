package com.demo.amps.connectors.amps;

import static org.assertj.core.api.Assertions.assertThat;

import com.crankuptheamps.client.Message;
import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.runtime.Command;
import com.demo.amps.connectors.runtime.MessageContext;
import com.demo.amps.connectors.runtime.OutboundRecord;
import com.demo.amps.connectors.source.Acknowledger;
import com.demo.amps.connectors.source.InboundRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PersistedAckTrackerTest {

    /** An acknowledger that writes down every call: {@code ack(n)} or {@code batch(a..b)}. */
    private static final class Stream implements Acknowledger {

        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void ack(long seqno) {
            calls.add("ack(" + seqno + ")");
        }

        @Override
        public void ackBatch(long fromSeqno, long toSeqno) {
            calls.add("batch(" + fromSeqno + ".." + toSeqno + ")");
        }
    }

    private final PersistedAckTracker tracker = new PersistedAckTracker("orders");

    /** A context at in-side position {@code inSeqno} on {@code stream}, issued as {@code outSeqno}. */
    private static MessageContext context(Stream stream, long inSeqno, long outSeqno) {
        InboundRecord record = InboundRecord.of("{}", "K-" + inSeqno)
                .withSeqno(inSeqno).withAck(stream);
        MessageContext context = MessageContext.of(record, OutboundRecord.publish(
                "sow/orders", Command.PUBLISH, "{}", PayloadType.UNSET, record.key()));
        context.assignOutSeqno(outSeqno);
        return context;
    }

    @Test
    @DisplayName("a persisted ack drains everything at or below it, in sequence order, and nothing above")
    void drainsCumulativelyInOrder() {
        Stream a = new Stream();
        Stream b = new Stream();
        // Alternating streams, so nothing coalesces and every ack is visible on its own.
        tracker.track(context(a, 10, 101));
        tracker.track(context(b, 20, 102));
        tracker.track(context(a, 11, 103));
        tracker.track(context(b, 21, 104));
        assertThat(tracker.pending()).isEqualTo(4);

        tracker.persistedUpTo(103);

        assertThat(a.calls).containsExactly("ack(10)", "ack(11)");
        assertThat(b.calls).containsExactly("ack(20)");
        assertThat(tracker.pending()).isEqualTo(1);
        assertThat(tracker.persisted()).isEqualTo(3);
        assertThat(tracker.highWater()).isEqualTo(103);

        tracker.persistedUpTo(104);
        assertThat(b.calls).containsExactly("ack(20)", "ack(21)");
        assertThat(tracker.pending()).isZero();
        assertThat(tracker.persisted()).isEqualTo(4);
    }

    @Test
    @DisplayName("a run of contexts on one stream is acknowledged as one range over its in-side positions")
    void coalescesARunOnOneStreamIntoOneRange() {
        Stream partition = new Stream();
        for (int i = 0; i < 5; i++) {
            tracker.track(context(partition, 1_000 + i, 201 + i));
        }

        tracker.persistedUpTo(205);

        assertThat(partition.calls).containsExactly("batch(1000..1004)");
        assertThat(tracker.persisted()).isEqualTo(5);
    }

    @Test
    @DisplayName("runs are cut where the acknowledger changes: same instance, not equal streams")
    void cutsRunsAtAChangeOfAcknowledger() {
        Stream p0 = new Stream();
        Stream p1 = new Stream();
        tracker.track(context(p0, 50, 301));
        tracker.track(context(p0, 51, 302));
        tracker.track(context(p1, 70, 303));
        tracker.track(context(p0, 52, 304));
        tracker.track(context(p0, 53, 305));
        tracker.track(context(p0, 54, 306));

        tracker.persistedUpTo(306);

        assertThat(p0.calls).containsExactly("batch(50..51)", "batch(52..54)");
        assertThat(p1.calls).containsExactly("ack(70)");
    }

    @Test
    @DisplayName("contexts with different acknowledgers are acknowledged one by one")
    void acknowledgesMixedStreamsPerRecord() {
        List<Stream> streams = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Stream stream = new Stream();
            streams.add(stream);
            tracker.track(context(stream, 5 + i, 401 + i));
        }

        tracker.persistedUpTo(404);

        for (int i = 0; i < 4; i++) {
            assertThat(streams.get(i).calls).containsExactly("ack(" + (5 + i) + ")");
        }
    }

    @Test
    @DisplayName("a repeated or a lower persisted ack changes nothing")
    void repeatedOrLowerAcksAreIdempotent() {
        Stream stream = new Stream();
        tracker.track(context(stream, 1, 501));
        tracker.track(context(stream, 2, 502));
        tracker.persistedUpTo(502);
        assertThat(stream.calls).containsExactly("batch(1..2)");

        tracker.persistedUpTo(502);
        tracker.persistedUpTo(501);
        tracker.persistedUpTo(0);
        tracker.persistedUpTo(-1);

        assertThat(stream.calls).containsExactly("batch(1..2)");
        assertThat(tracker.persisted()).isEqualTo(2);
        assertThat(tracker.pending()).isZero();
        assertThat(tracker.highWater()).isEqualTo(502);
    }

    @Test
    @DisplayName("a context without a sequence has no ack to wait for and is acknowledged at once")
    void acknowledgesAnUnsequencedContextImmediately() {
        Stream stream = new Stream();
        InboundRecord record = InboundRecord.of("{}", "K").withSeqno(7).withAck(stream);
        MessageContext storeless = MessageContext.of(record, OutboundRecord.publish(
                "sow/orders", Command.PUBLISH, "{}", PayloadType.UNSET, "K"));
        assertThat(storeless.dataOutSeqno()).isZero();

        tracker.track(storeless);

        assertThat(stream.calls).containsExactly("ack(7)");
        assertThat(tracker.unsequenced()).isEqualTo(1);
        assertThat(tracker.pending()).isZero();
        assertThat(tracker.persisted()).as("not persisted: nobody said so").isZero();
    }

    @Test
    @DisplayName("a rejected write is acknowledged, removed and counted; a duplicate is counted apart")
    void failedWritesAreAcknowledgedAndCounted() {
        Stream stream = new Stream();
        tracker.track(context(stream, 1, 601));
        tracker.track(context(stream, 2, 602));
        tracker.track(context(stream, 3, 603));

        tracker.failedWrite(602, Message.Reason.BadSowKey);
        assertThat(stream.calls).containsExactly("ack(2)");
        assertThat(tracker.rejected()).isEqualTo(1);
        assertThat(tracker.duplicates()).isZero();
        assertThat(tracker.pending()).isEqualTo(2);

        tracker.failedWrite(601, Message.Reason.Duplicate);
        assertThat(stream.calls).containsExactly("ack(2)", "ack(1)");
        assertThat(tracker.duplicates()).isEqualTo(1);
        assertThat(tracker.rejected()).isEqualTo(1);

        // The client discards up to the failed sequence right after reporting it: the ack
        // that follows finds only what is left, and counts only that as persisted.
        tracker.persistedUpTo(603);
        assertThat(stream.calls).containsExactly("ack(2)", "ack(1)", "ack(3)");
        assertThat(tracker.persisted()).isEqualTo(1);
        assertThat(tracker.pending()).isZero();

        // A failed write for a sequence nobody is waiting on is still a fact worth counting.
        tracker.failedWrite(999, Message.Reason.NotEntitled);
        tracker.failedWrite(0, Message.Reason.Duplicate);
        assertThat(tracker.rejected()).isEqualTo(2);
        assertThat(tracker.duplicates()).isEqualTo(2);
        assertThat(stream.calls).hasSize(3);
    }

    @Test
    @DisplayName("an ack that lands before the context is parked still acknowledges it, exactly once")
    void acknowledgesAContextParkedAfterTheAckThatCoveredIt() {
        Stream stream = new Stream();
        // The receive thread got there first: the server answered before track() ran.
        tracker.persistedUpTo(700);
        assertThat(tracker.highWater()).isEqualTo(700);

        tracker.track(context(stream, 1, 700));

        assertThat(stream.calls).containsExactly("ack(1)");
        assertThat(tracker.pending()).isZero();
        assertThat(tracker.persisted()).isEqualTo(1);

        // ...and one parked above the high-water mark waits for its own ack.
        tracker.track(context(stream, 2, 701));
        assertThat(stream.calls).hasSize(1);
        assertThat(tracker.pending()).isEqualTo(1);
        tracker.persistedUpTo(701);
        assertThat(stream.calls).containsExactly("ack(1)", "ack(2)");
    }

    @Test
    @DisplayName("an acknowledger that throws is contained, and the rest of the drain still runs")
    void containsAThrowingAcknowledger() {
        Stream healthy = new Stream();
        Acknowledger broken = seqno -> {
            throw new IllegalStateException("offset store gone");
        };
        tracker.track(context(healthy, 1, 801));
        InboundRecord record = InboundRecord.of("{}", "K").withSeqno(2).withAck(broken);
        MessageContext failing = MessageContext.of(record, OutboundRecord.publish(
                "sow/orders", Command.PUBLISH, "{}", PayloadType.UNSET, "K"));
        failing.assignOutSeqno(802);
        tracker.track(failing);
        tracker.track(context(healthy, 3, 803));

        tracker.persistedUpTo(803);

        assertThat(healthy.calls).containsExactly("ack(1)", "ack(3)");
        assertThat(tracker.pending()).isZero();
        assertThat(tracker.persisted()).isEqualTo(3);
    }

    @Test
    @DisplayName("acks from the receive thread and tracking from the publishing thread never lose a record")
    void survivesConcurrentTrackingAndAcks() throws Exception {
        Stream stream = new Stream();
        int records = 5_000;
        CountDownLatch done = new CountDownLatch(2);
        Thread publishing = new Thread(() -> {
            for (int i = 1; i <= records; i++) {
                tracker.track(context(stream, i, i));
            }
            done.countDown();
        }, "publishing");
        Thread receiving = new Thread(() -> {
            for (int i = 1; i <= records; i++) {
                tracker.persistedUpTo(i);
            }
            done.countDown();
        }, "receiving");
        publishing.start();
        receiving.start();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        // Whatever was parked after the last ack is covered by one more.
        tracker.persistedUpTo(records);

        assertThat(tracker.pending()).isZero();
        assertThat(tracker.persisted()).isEqualTo(records);
        // Every position was acknowledged exactly once, as a single ack or inside one range.
        long acknowledged = 0;
        for (String call : stream.calls) {
            if (call.startsWith("ack(")) {
                acknowledged++;
            } else {
                String[] bounds = call.substring("batch(".length(), call.length() - 1).split("\\.\\.");
                acknowledged += Long.parseLong(bounds[1]) - Long.parseLong(bounds[0]) + 1;
            }
        }
        assertThat(acknowledged).isEqualTo(records);
    }

    @Test
    void statusReadsTheCounters() {
        Stream stream = new Stream();
        tracker.track(context(stream, 1, 901));
        tracker.track(context(stream, 2, 902));
        tracker.persistedUpTo(901);
        tracker.failedWrite(902, Message.Reason.Other);
        assertThat(tracker.status())
                .isEqualTo("pending=0 persisted=1 rejected=1 duplicates=0 unsequenced=0");
        assertThat(tracker.toString()).startsWith("PersistedAckTracker[orders ");
    }
}
