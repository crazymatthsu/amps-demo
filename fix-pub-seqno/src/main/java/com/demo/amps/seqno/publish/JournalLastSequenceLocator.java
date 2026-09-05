package com.demo.amps.seqno.publish;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.common.MessageStreams;
import com.demo.amps.seqno.SeqnoConfig;
import com.demo.amps.seqno.fix.FixMessage;
import com.demo.amps.seqno.fix.FixTags;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds -- and verifies -- the last sequence number AMPS holds for a sender by
 * replaying the transaction log.
 *
 * <p>A bookmark subscription with the filter {@code /49 = 'sender'}, started
 * from a timestamp a lookback before now (or from the epoch when the SOW had
 * no answer), replays every message of the sender in that window and then goes
 * live. Reading to the head of the journal yields the whole tail of the
 * sender's sequence, so the maximum 8888 is L and the same pass sees any gap
 * or duplicate ({@link JournalScanResult}).
 *
 * <p>A bookmark subscription has no end marker -- it becomes live when it
 * catches up -- so "reached the head" is "nothing arrived for {@code
 * idleMillis}", the same idle-timeout convention the repository's
 * bookmark-replay demo uses.
 */
public final class JournalLastSequenceLocator {

    private static final Logger log = LoggerFactory.getLogger(JournalLastSequenceLocator.class);

    /** AMPS journal-timestamp bookmark format, e.g. {@code 20260905T153000}. */
    private static final DateTimeFormatter BOOKMARK_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final Client client;
    private final SeqnoConfig config;

    public JournalLastSequenceLocator(Client client, SeqnoConfig config) {
        this.client = client;
        this.config = config;
    }

    /**
     * Scans from {@code config.lookback()} before now. Use when the SOW gave a
     * positive answer and the scan only has to confirm the recent tail.
     */
    public JournalScanResult scan(String sender) throws Exception {
        return scanFrom(sender, bookmarkFor(Instant.now().minus(config.lookback())));
    }

    /** Scans the whole journal. Use when the SOW had no answer at all. */
    public JournalScanResult scanFromEpoch(String sender) throws Exception {
        return scanFrom(sender, Client.Bookmarks.EPOCH);
    }

    /** Scans from an explicit lookback, for tests and for widening after a partial scan. */
    public JournalScanResult scanFrom(String sender, Duration lookback) throws Exception {
        return scanFrom(sender, bookmarkFor(Instant.now().minus(lookback)));
    }

    private JournalScanResult scanFrom(String sender, String bookmark) throws Exception {
        Command command = new Command("subscribe")
                .setTopic(config.topic())
                .setBookmark(bookmark)
                .setFilter(SenderFilter.forSender(sender))
                .setTimeout(config.timeoutMillis());

        JournalScanResult.Builder result = JournalScanResult.builder();
        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, config.idleMillis(), message -> {
                if (message.isDataNull()) {
                    return true;
                }
                FixMessage parsed = FixMessage.parse(message.getData());
                OptionalLong sequence = parsed.optionalLong(FixTags.SENDER_SEQ_NUM);
                if (sequence.isPresent()) {
                    result.observe(sequence.getAsLong());
                } else {
                    log.warn("journal message for sender {} has no tag 8888: {}",
                            sender, parsed.printable());
                }
                return true;
            });
        }
        JournalScanResult scan = result.build();
        log.debug("journal scan for {} from {}: {}", sender, bookmark, scan);
        return scan;
    }

    private static String bookmarkFor(Instant instant) {
        return BOOKMARK_TIMESTAMP.format(instant);
    }
}
