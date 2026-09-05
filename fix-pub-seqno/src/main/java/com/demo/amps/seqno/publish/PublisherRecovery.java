package com.demo.amps.seqno.publish;

import com.crankuptheamps.client.Client;
import com.demo.amps.seqno.SeqnoConfig;
import com.demo.amps.seqno.outbox.Outbox;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recovery procedure ({@code docs/04}), wired against a live client:
 * find the last sequence number AMPS holds, verify it, republish the gap from
 * the outbox, and confirm the two sides agree before publishing resumes.
 *
 * <p>Run on every connect, first run included -- a first run is a recovery
 * from an empty journal and needs no special case. The steps are: flush, so a
 * publish store's replay (if one is in use) has landed before L is read; SOW
 * lookup; journal scan to verify; {@link GapRecovery reconcile}; republish;
 * flush; re-read L and require it equals the outbox.
 */
public final class PublisherRecovery {

    private static final Logger log = LoggerFactory.getLogger(PublisherRecovery.class);

    private final Client client;
    private final SeqnoConfig config;
    private final Outbox outbox;
    private final SequencedPublisher publisher;
    private final SowLastSequenceLocator sowLocator;
    private final JournalLastSequenceLocator journalLocator;

    public PublisherRecovery(Client client, SeqnoConfig config, SequencedPublisher publisher) {
        this.client = client;
        this.config = config;
        this.outbox = publisher.outbox();
        this.publisher = publisher;
        this.sowLocator = new SowLastSequenceLocator(client, config);
        this.journalLocator = new JournalLastSequenceLocator(client, config);
    }

    /** Runs the whole procedure and returns what it did. Never republishes below L. */
    public RecoveryReport recover() throws Exception {
        long outboxLast = outbox.lastSequence();
        log.info("recovery: outbox holds up to sequence {}", outboxLast);

        // 1. Flush: nothing on a fresh connect, but a publish store (production
        //    B3) would have replayed into this connection during logon, and L
        //    must be read after those land.
        publisher.flush();

        // 2. SOW lookup.
        long lSow = sowLocator.locate(config.sender());
        boolean sowPresent = lSow > 0;
        log.info("recovery: SOW reports last sequence {}", sowPresent ? lSow : "none");

        // 3. Journal scan to verify. From the epoch when the SOW had no answer
        //    (the choice is then "new sender" vs "reset SOW", which only the
        //    whole journal settles), otherwise from the lookback.
        JournalScanResult scan = sowPresent
                ? journalLocator.scan(config.sender())
                : journalLocator.scanFromEpoch(config.sender());
        log.info("recovery: journal scan {}", scan);

        // 4. Reconcile -- a pure decision, unit-tested without a server.
        RecoveryDecision decision = GapRecovery.reconcile(
                sowPresent ? OptionalLong.of(lSow) : OptionalLong.empty(), scan, outboxLast);
        for (String alarm : decision.alarms()) {
            log.warn("recovery ALARM: {}", alarm);
        }

        if (decision.action() == RecoveryDecision.Action.HALT) {
            log.error("recovery HALT: {}", decision.haltReason());
            return new RecoveryReport(decision, 0, decision.resolvedL(), false);
        }

        // 5. Republish the gap (L, outboxLast], then flush.
        int resent = 0;
        if (decision.gapSize() > 0) {
            log.info("recovery: republishing {} message(s) in ({}, {}]",
                    decision.gapSize(), decision.republishFrom(), decision.republishTo());
            resent = publisher.republish(decision.republishFrom(), decision.republishTo());
            publisher.flush();
        } else {
            log.info("recovery: AMPS and the outbox agree at {}; nothing to republish", outboxLast);
        }

        // 6. Re-verify: read L again and require it equals the outbox.
        long finalL = sowLocator.locate(config.sender());
        boolean verified = finalL == outboxLast;
        if (verified) {
            log.info("recovery complete: AMPS now holds up to sequence {} (matches the outbox)", finalL);
        } else {
            log.error("recovery INCOMPLETE: AMPS holds {} but the outbox reaches {}", finalL, outboxLast);
        }
        return new RecoveryReport(decision, resent, finalL, verified);
    }
}
