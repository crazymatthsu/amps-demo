package com.demo.amps.seqno.subscribe;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.LoggedBookmarkStore;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.common.MessageStreams;
import com.demo.amps.seqno.SeqnoConfig;
import com.demo.amps.seqno.fix.FixMessage;
import com.demo.amps.seqno.fix.FixTags;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The subscriber: a bookmark subscription that resumes where it left off, plus
 * the per-sender tag-8888 check that turns "resume" into "resume without a
 * gap".
 *
 * <p>Two durable stores, both keyed on stable identity ({@code docs/05}):
 *
 * <ul>
 *   <li>a {@link LoggedBookmarkStore}, which records which journal positions
 *       this subscription has discarded, so {@code MOST_RECENT} resumes after
 *       the last processed message across restarts;</li>
 *   <li>a {@link SequenceTracker} file, the high-water mark of 8888 per sender,
 *       which judges every message and makes a redelivered one harmless.</li>
 * </ul>
 *
 * <p>The ordering the code below is careful about: process, then advance and
 * save the mark, then discard the bookmark. A crash between the mark and the
 * discard redelivers a message the mark now rejects; a crash before the mark
 * reprocesses it. That is at-least-once, and the mark is what keeps it from
 * becoming visibly-more-than-once here, where "process" is a print.
 */
public final class BookmarkSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BookmarkSubscriber.class);

    private final SeqnoConfig config;
    private final HAClient client;

    public BookmarkSubscriber(SeqnoConfig config) throws Exception {
        this.config = config;
        Files.createDirectories(config.bookmarkFile().getParent());
        HAClient haClient = new HAClient(config.subscriberClientName());
        try {
            haClient.setBookmarkStore(new LoggedBookmarkStore(config.bookmarkFile().toString()));
            haClient.setServerChooser(new DefaultServerChooser().add(config.uri()));
            haClient.connectAndLogon();
        } catch (Exception e) {
            haClient.close();
            throw e;
        }
        this.client = haClient;
    }

    /**
     * Consumes what is available now -- resuming from the bookmark store -- and
     * returns once the stream has been idle for {@code config.idleMillis()}.
     * The tracker is advanced and saved as messages are processed.
     */
    public SubscriberReport consume(SequenceTracker tracker) throws Exception {
        Command command = new Command("subscribe")
                .setTopic(config.topic())
                .setSubId(config.subscriptionId())
                .setBookmark(Client.Bookmarks.MOST_RECENT)
                .setOptions(Message.Options.Timestamp)
                .setTimeout(config.timeoutMillis());

        List<String> gaps = new ArrayList<>();
        int[] processed = {0};
        int[] duplicates = {0};

        try (MessageStream stream = client.execute(command)) {
            MessageStreams.forEach(stream, config.idleMillis(), message -> {
                if (message.getCommand() != Message.Command.Publish || message.isDataNull()) {
                    return true;
                }
                FixMessage fix = FixMessage.parse(message.getData());
                String sender = fix.value(FixTags.SENDER_COMP_ID);
                long sequence = fix.optionalLong(FixTags.SENDER_SEQ_NUM).orElse(-1);
                if (sender.isEmpty() || sequence < 0) {
                    log.warn("message without sender or 8888, skipping: {}", fix.printable());
                    return true;
                }

                SequenceTracker.Verdict verdict = tracker.classify(sender, sequence);
                switch (verdict) {
                    case DUPLICATE -> {
                        duplicates[0]++;
                        log.info("  DUPLICATE 49={} 8888={} (mark {}), skipping",
                                sender, sequence, tracker.mark(sender));
                    }
                    case GAP -> {
                        String note = "GAP: 49=" + sender + " jumped from " + tracker.mark(sender)
                                + " to " + sequence;
                        gaps.add(note);
                        log.warn("  {} -- accepting and advancing, but the publisher invariant "
                                + "failed or the journal was truncated under the bookmark", note);
                        process(fix, sender, sequence, tracker);
                        processed[0]++;
                    }
                    case FIRST, IN_SEQUENCE -> {
                        process(fix, sender, sequence, tracker);
                        processed[0]++;
                    }
                }

                // Discard AFTER processing (and after the mark is saved, inside
                // process): this is the at-least-once boundary.
                try {
                    client.getBookmarkStore().discard(message);
                } catch (Exception e) {
                    log.warn("discard failed for 8888={}: {}", sequence, e.toString());
                }
                return true;
            });
        }

        return new SubscriberReport(processed[0], duplicates[0], gaps, tracker.marks());
    }

    private void process(FixMessage fix, String sender, long sequence, SequenceTracker tracker) {
        // "Processing" here is a log line; the point is the ordering around it.
        log.info("  processed 49={} 8888={} 11={} 55={}", sender, sequence,
                fix.value(FixTags.CL_ORD_ID), fix.value(FixTags.SYMBOL));
        tracker.advance(sender, sequence);
        try {
            // The mark is persisted before the caller discards the bookmark, so
            // a crash in the window redelivers a message the mark now rejects.
            tracker.save(config.highWaterMarkFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot persist high-water mark for " + sender, e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
