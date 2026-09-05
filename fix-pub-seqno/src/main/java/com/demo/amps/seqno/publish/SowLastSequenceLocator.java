package com.demo.amps.seqno.publish;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.common.MessageStreams;
import com.demo.amps.seqno.SeqnoConfig;
import com.demo.amps.seqno.fix.FixMessage;
import com.demo.amps.seqno.fix.FixTags;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the last sequence number AMPS holds for a sender by reading the SOW.
 *
 * <p>The topic is keyed on tag 49, so the SOW holds exactly one record per
 * sender: its most recent message. One keyed query -- filter {@code /49 =
 * 'sender'} -- returns zero or one record, and its tag 8888 is the answer.
 * O(1), served from the SOW index without touching the journal.
 *
 * <p>The value is trustworthy only while every publisher respects the prefix
 * invariant; a publisher that resent an older message would leave the SOW
 * holding that older message's 8888. {@link JournalLastSequenceLocator} is
 * the check that catches exactly that, which is why the recovery uses both.
 */
public final class SowLastSequenceLocator {

    private static final Logger log = LoggerFactory.getLogger(SowLastSequenceLocator.class);

    private final Client client;
    private final SeqnoConfig config;

    public SowLastSequenceLocator(Client client, SeqnoConfig config) {
        this.client = client;
        this.config = config;
    }

    /**
     * The last 8888 the SOW holds for {@code sender}, or 0 when the SOW has no
     * record of it. A record present but carrying no 8888 is a corrupt
     * checkpoint and is reported as such rather than silently treated as 0.
     */
    public long locate(String sender) throws Exception {
        Command command = new Command("sow")
                .setTopic(config.topic())
                .setFilter(SenderFilter.forSender(sender))
                .setTimeout(config.timeoutMillis());

        AtomicLong last = new AtomicLong(0);
        int[] records = {0};
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, config.idleMillis(), message -> {
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    records[0]++;
                    FixMessage record = FixMessage.parse(message.getData());
                    last.set(record.optionalLong(FixTags.SENDER_SEQ_NUM).orElseThrow(() ->
                            new IllegalStateException("SOW record for sender " + sender
                                    + " carries no tag 8888: " + record.printable())));
                }
                return true;
            });
        }
        if (records[0] > 1) {
            // The key is /49, so this cannot happen unless the topic is
            // misconfigured; fail loudly rather than pick one.
            throw new IllegalStateException("expected at most one SOW record for sender " + sender
                    + " on a topic keyed by /49, found " + records[0]);
        }
        log.debug("SOW lookup for {}: {} record(s), last 8888 = {}", sender, records[0], last.get());
        return last.get();
    }
}
