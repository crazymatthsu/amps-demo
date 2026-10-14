package com.demo.amps.fix42.publish;

import com.crankuptheamps.client.Client;
import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.config.PublishMode;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * <p>Payloads go to the client as bytes, not Strings. Each publishing thread
 * owns one {@link FixWriter}; a payload is encoded into it and handed to the
 * byte-array {@code publish} overloads, which copy it into the transport's
 * send buffer before returning. The String overloads would turn the same
 * bytes into a String, back into chars and into bytes again on every publish.
 */
@Component
public class AmpsDeltaPublisher {

    private static final Logger log = LoggerFactory.getLogger(AmpsDeltaPublisher.class);

    private final Client client;
    private final PublishPlanner planner;
    private final Fix42Properties properties;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong deltas = new AtomicLong();
    private final ThreadLocal<FixWriter> writer = ThreadLocal.withInitial(() -> new FixWriter(512));
    /** Topic names encoded once; the byte-array overloads take the topic as bytes as well. */
    private final ConcurrentMap<String, byte[]> topicBytes = new ConcurrentHashMap<>();

    public AmpsDeltaPublisher(Client client, PublishPlanner planner, Fix42Properties properties) {
        this.client = client;
        this.planner = planner;
        this.properties = properties;
    }

    /** Plans and sends one message; returns what it sent. */
    public List<PublishInstruction> send(FixMessage message) throws Exception {
        List<PublishInstruction> instructions = planner.plan(message);
        FixWriter out = writer.get();
        for (PublishInstruction instruction : instructions) {
            out.clear();
            instruction.payload().writeTo(out);
            byte[] topic = topicBytes.computeIfAbsent(instruction.topic(), AmpsDeltaPublisher::encode);
            if (instruction.mode() == PublishMode.FULL) {
                client.publish(topic, 0, topic.length, out.buffer(), 0, out.size());
                published.incrementAndGet();
            } else {
                client.deltaPublish(topic, 0, topic.length, out.buffer(), 0, out.size());
                deltas.incrementAndGet();
            }
            // Guarded because printable() is an ARGUMENT: slf4j's {} defers
            // formatting, not argument evaluation, so an unguarded call would
            // build the log text on every publish even when the level discards
            // it. Set logging.level.com.demo.amps.fix42=WARN and this costs
            // nothing.
            if (log.isInfoEnabled()) {
                log.info("{} {} [{}] {}",
                        instruction.mode() == PublishMode.FULL ? "publish      " : "delta_publish",
                        instruction.topic(),
                        instruction.routeName(),
                        out.printable());
            }
        }
        return instructions;
    }

    /**
     * Blocks until the server has acknowledged everything sent so far.
     *
     * <p>AMPS publishes are asynchronous. Without this, a query issued
     * immediately after the last send can legitimately return a record that is
     * one message behind -- which reads as a chaining bug and is not one.
     */
    public void flush() throws Exception {
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

    private static byte[] encode(String topic) {
        return topic.getBytes(StandardCharsets.ISO_8859_1);
    }
}
