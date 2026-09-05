package com.demo.amps.seqno.publish;

import com.crankuptheamps.client.Client;
import com.demo.amps.seqno.SeqnoConfig;
import com.demo.amps.seqno.fix.FixMessage;
import com.demo.amps.seqno.fix.FixTags;
import com.demo.amps.seqno.outbox.Outbox;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The publish path, in two deliberately separate steps: <b>enqueue</b> (assign
 * the sequence number and record it durably) and <b>transmit</b> (send the
 * recorded bytes to AMPS).
 *
 * <p>Splitting them is what lets the demo reproduce the failure the whole
 * design exists for: a publisher that has committed a message to its own
 * store but died before AMPS received it. {@link #publish} does both in the
 * usual order; the crash phase calls {@link #enqueue} for a batch and
 * {@link #transmit} for only some of it, then drops the connection.
 *
 * <p>Assigning the sequence number ({@link #enqueue}) writes the outbox entry
 * before anything is sent, so a message that ever reaches the wire is always
 * in the outbox first. Transmission sends the bytes <em>as stored</em>, so a
 * republish is identical to the original -- there is no second rendering that
 * could differ.
 */
public final class SequencedPublisher {

    private static final Logger log = LoggerFactory.getLogger(SequencedPublisher.class);

    private static final DateTimeFormatter SENDING_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final Client client;
    private final Outbox outbox;
    private final SeqnoConfig config;
    private final AtomicLong transmitted = new AtomicLong();

    public SequencedPublisher(Client client, Outbox outbox, SeqnoConfig config) {
        this.client = client;
        this.outbox = outbox;
        this.config = config;
    }

    /**
     * Stamps identity onto a business message and records it in the outbox
     * under the next sequence number. Does <em>not</em> send it.
     *
     * <p>The stamp is tag 49 (the sender), tag 8888 (the assigned sequence),
     * and tag 52 (sending time). Everything else came from the feed.
     *
     * @return the sequence number assigned
     */
    public long enqueue(FixMessage business) {
        long sequence = outbox.lastSequence() + 1;
        FixMessage stamped = business.toBuilder()
                .set(FixTags.SENDER_COMP_ID, config.sender())
                .set(FixTags.SENDER_SEQ_NUM, sequence)
                .set(FixTags.SENDING_TIME, SENDING_TIME.format(Instant.now()))
                .build();
        outbox.append(sequence, stamped.render());
        return sequence;
    }

    /** Sends the stored payload for {@code sequence}, exactly as recorded. */
    public void transmit(long sequence) throws Exception {
        Outbox.Entry entry = outbox.get(sequence).orElseThrow(() ->
                new IllegalArgumentException("no outbox entry for sequence " + sequence));
        client.publish(config.topic(), entry.payload());
        transmitted.incrementAndGet();
    }

    /** Enqueue then transmit: the normal path. Returns the sequence number sent. */
    public long publish(FixMessage business) throws Exception {
        long sequence = enqueue(business);
        transmit(sequence);
        return sequence;
    }

    /** Publishes each message in order; returns the last sequence number. */
    public long publishAll(List<FixMessage> messages) throws Exception {
        long last = outbox.lastSequence();
        for (FixMessage message : messages) {
            last = publish(message);
        }
        return last;
    }

    /**
     * Re-sends every outbox entry in {@code (fromExclusive, toInclusive]}, in
     * order. This is the republish of the gap: the payloads are the originals,
     * so AMPS receives byte-for-byte what it would have the first time.
     *
     * @return how many messages were re-sent
     */
    public int republish(long fromExclusive, long toInclusive) throws Exception {
        int count = 0;
        for (Outbox.Entry entry : outbox.after(fromExclusive)) {
            if (entry.sequence() > toInclusive) {
                break;
            }
            client.publish(config.topic(), entry.payload());
            transmitted.incrementAndGet();
            count++;
        }
        log.debug("republished {} message(s) in ({}, {}]", count, fromExclusive, toInclusive);
        return count;
    }

    /** Blocks until AMPS has processed everything sent so far. */
    public void flush() throws Exception {
        client.publishFlush(config.timeoutMillis());
    }

    /** How many publishes this instance has issued, republishes included. */
    public long transmittedCount() {
        return transmitted.get();
    }

    public Outbox outbox() {
        return outbox;
    }
}
