package com.demo.amps.fix42.publish;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.exception.AMPSException;
import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixFrame;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends planned publishes to AMPS.
 *
 * <p>The only class here that needs a live server. It issues exactly two
 * commands -- {@code publish} for a whole message and {@code delta_publish} for
 * a field subset -- and counts what it sent, because "did the amend actually
 * reach the blotter?" is the first question anyone asks of a publisher.
 *
 * <p>Two ways in. {@link #send(byte[], int, int)}, or {@link #send(FixFrame)}
 * for a message already indexed, is the hot path a feed should call: the
 * message is read in place, planned into a per-thread {@link PublishPlan}, and
 * each payload goes to the client's byte-array overloads, which copy it into
 * the transport's send buffer before returning. After warm-up it allocates
 * nothing, unless the per-publish INFO line is enabled. {@link #send(FixMessage)}
 * is the readable path: it runs the same hot path on the rendered message and
 * returns what was sent, as instructions.
 */
@Component
public class AmpsDeltaPublisher {

    private static final Logger log = LoggerFactory.getLogger(AmpsDeltaPublisher.class);

    private final Client client;
    private final PublishPlanner planner;
    private final Fix42Properties properties;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong deltas = new AtomicLong();
    private final ThreadLocal<Scratch> scratch;

    public AmpsDeltaPublisher(Client client, PublishPlanner planner, Fix42Properties properties) {
        this.client = client;
        this.planner = planner;
        this.properties = properties;
        this.scratch = ThreadLocal.withInitial(() -> new Scratch(planner.newPlan()));
    }

    /**
     * Plans and sends one message held as raw FIX bytes.
     *
     * @return the number of publishes issued
     */
    public int send(byte[] payload, int offset, int length) throws AMPSException {
        return send(scratch.get().frame.wrap(payload, offset, length));
    }

    /**
     * Plans and sends one indexed message.
     *
     * @return the number of publishes issued
     */
    public int send(FixFrame message) throws AMPSException {
        PublishPlan plan = scratch.get().plan;
        planner.plan(message, plan);
        for (int i = 0, n = plan.size(); i < n; i++) {
            PublishPlan.Publish publish = plan.get(i);
            byte[] topic = publish.topicBytes();
            if (publish.mode() == PublishMode.FULL) {
                client.publish(topic, 0, topic.length,
                        publish.payload(), publish.payloadOffset(), publish.payloadLength());
                published.incrementAndGet();
            } else {
                client.deltaPublish(topic, 0, topic.length,
                        publish.payload(), publish.payloadOffset(), publish.payloadLength());
                deltas.incrementAndGet();
            }
            // Guarded because printable() is an ARGUMENT: slf4j's {} defers
            // formatting, not argument evaluation, so an unguarded call would
            // build the log text on every publish even when the level discards
            // it. Set logging.level.com.demo.amps.fix42=WARN and this costs
            // nothing.
            if (log.isInfoEnabled()) {
                log.info("{} {} [{}] {}",
                        publish.mode() == PublishMode.FULL ? "publish      " : "delta_publish",
                        publish.topic(),
                        publish.routeName(),
                        publish.printable());
            }
        }
        return plan.size();
    }

    /** Plans and sends one message; returns what it sent. */
    public List<PublishInstruction> send(FixMessage message) throws AMPSException {
        Scratch mine = scratch.get();
        mine.source.clear();
        message.writeTo(mine.source);
        send(mine.frame.wrap(mine.source));
        return PublishPlanner.materialize(mine.plan, message, mine.source.buffer());
    }

    /**
     * Blocks until the server has acknowledged everything sent so far.
     *
     * <p>AMPS publishes are asynchronous. Without this, a query issued
     * immediately after the last send can legitimately return a record that is
     * one message behind -- which reads as a chaining bug and is not one.
     */
    public void flush() throws AMPSException {
        client.publishFlush(properties.amps().timeoutMs());
    }

    /** Count of whole-message publishes issued. */
    public long fullPublishCount() {
        return published.get();
    }

    /** Count of delta publishes issued. */
    public long deltaPublishCount() {
        return deltas.get();
    }

    /** What one publishing thread needs: a frame over the message, the plan, and a buffer for the readable path. */
    private static final class Scratch {

        final FixFrame frame = new FixFrame();
        final FixWriter source = new FixWriter(512);
        final PublishPlan plan;

        Scratch(PublishPlan plan) {
            this.plan = plan;
        }
    }
}
