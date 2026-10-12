package com.demo.amps.ha;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runnable demo: a publisher, a consumer, or both in one JVM, against the
 * compose stack -- while you kill an instance.
 *
 * <pre>
 *   ./amps-ha-demo/scripts/ha-compose.sh start
 *   ./gradlew :amps-ha-demo:run --args="both"          # then, in another shell:
 *   ./amps-ha-demo/scripts/ha-compose.sh failover      # SIGKILL the primary
 *   ./amps-ha-demo/scripts/ha-compose.sh revive primary
 *   ./amps-ha-demo/scripts/ha-compose.sh kill secondary
 * </pre>
 *
 * <p>Settings come from {@code -Dha.*} or {@code HA_*}; see {@link HaSettings}.
 * The interesting ones: {@code ha.count} and {@code ha.intervalMs} decide how
 * long the run lasts (the default is a minute, enough to pull two plugs by
 * hand), {@code ha.stateDir} switches to file-backed stores so the demo itself
 * can be killed and restarted, and {@code ha.bookmark=epoch} replays the whole
 * journal into the consumer's per-run ledgers.
 *
 * <p>The exit code is the verdict in {@code both} mode: {@code 0} when every
 * message the publisher sent reached the consumer exactly once and in order,
 * {@code 1} otherwise.
 */
public final class HaDemo {

    private static final Logger log = LoggerFactory.getLogger(HaDemo.class);
    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration STATUS_EVERY = Duration.ofSeconds(3);

    private HaDemo() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "both";
        HaSettings settings = HaSettings.fromEnvironment();
        log.info("amps-ha-demo {}: instances {}, topic '{}', run '{}'", mode, settings.uris(), settings.topic(),
                settings.run());
        int exit = switch (mode) {
            case "publisher" -> runPublisher(settings);
            case "consumer" -> runConsumer(settings);
            case "both" -> runBoth(settings);
            default -> {
                System.err.println("usage: amps-ha-demo [publisher|consumer|both]   (settings: -Dha.uris, -Dha.topic, "
                        + "-Dha.count, -Dha.intervalMs, -Dha.publisher, -Dha.consumer, -Dha.bookmark, -Dha.stateDir, -Dha.run)");
                yield 2;
            }
        };
        System.exit(exit);
    }

    /** Publisher and consumer in one process; the verdict is the exit code. */
    static int runBoth(HaSettings settings) throws Exception {
        try (HaClients.Connected consuming = HaClients.consumer(settings.consumerName(), settings.uris(), settings.stateDir());
             HaClients.Connected publishing = HaClients.publisher(settings.publisherName(), settings.uris(), settings.stateDir())) {
            OrderConsumer consumer = new OrderConsumer(consuming.client(), settings.topic(), settings.consumerName());
            consumer.start(settings.bookmarkValue());
            OrderPublisher publisher = new OrderPublisher(publishing.client(), settings.topic(), settings.run());
            SequenceLedger ledger = consumer.ledger(settings.run());

            log.info("publishing {} messages every {} ms -- now is the time to run 'ha-compose.sh failover'",
                    settings.count(), settings.interval().toMillis());
            Instant[] lastStatus = {Instant.now()};
            publisher.publishRange(1, settings.count(), settings.interval(), seq -> {
                if (Duration.between(lastStatus[0], Instant.now()).compareTo(STATUS_EVERY) >= 0) {
                    lastStatus[0] = Instant.now();
                    log.info("published {} (unacknowledged {}) via {} | consumer {} via {}", seq, publisher.unpersisted(),
                            publishing.journal().lastLogonUri(), ledger, consuming.journal().lastLogonUri());
                }
            });

            boolean flushed = publisher.flush(FLUSH_TIMEOUT);
            log.info("publisher done: {} published, flush {}, {} unacknowledged", settings.count(),
                    flushed ? "complete" : "INCOMPLETE", publisher.unpersisted());
            boolean drained = awaitAll(ledger, settings.count(), DRAIN_TIMEOUT);

            log.info("publisher connection journal:\n{}", publishing.journal().describe());
            log.info("consumer connection journal:\n{}", consuming.journal().describe());
            return verdict(settings, publisher, publishing.journal(), consuming.journal(), ledger, flushed, drained);
        }
    }

    /** Publisher only: publish, flush, report what the server acknowledged. */
    static int runPublisher(HaSettings settings) throws Exception {
        try (HaClients.Connected publishing = HaClients.publisher(settings.publisherName(), settings.uris(), settings.stateDir())) {
            OrderPublisher publisher = new OrderPublisher(publishing.client(), settings.topic(), settings.run());
            log.info("publishing {} messages every {} ms as run '{}'", settings.count(), settings.interval().toMillis(),
                    settings.run());
            Instant[] lastStatus = {Instant.now()};
            publisher.publishRange(1, settings.count(), settings.interval(), seq -> {
                if (Duration.between(lastStatus[0], Instant.now()).compareTo(STATUS_EVERY) >= 0) {
                    lastStatus[0] = Instant.now();
                    log.info("published {} (unacknowledged {}) via {}", seq, publisher.unpersisted(),
                            publishing.journal().lastLogonUri());
                }
            });
            boolean flushed = publisher.flush(FLUSH_TIMEOUT);
            log.info("done: {} published, flush {}, {} unacknowledged; failovers: {}", settings.count(),
                    flushed ? "complete" : "INCOMPLETE", publisher.unpersisted(),
                    Math.max(0, publishing.journal().logonUris().size() - 1));
            log.info("connection journal:\n{}", publishing.journal().describe());
            return flushed ? 0 : 1;
        }
    }

    /**
     * Consumer only: subscribe and report until interrupted, or until a run
     * reaches {@code ha.count} messages.
     */
    static int runConsumer(HaSettings settings) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stop.set(true)));
        try (HaClients.Connected consuming = HaClients.consumer(settings.consumerName(), settings.uris(), settings.stateDir())) {
            OrderConsumer consumer = new OrderConsumer(consuming.client(), settings.topic(), settings.consumerName());
            consumer.start(settings.bookmarkValue());
            log.info("consuming; a run is complete at {} messages. Ctrl-C to stop.", settings.count());
            while (!stop.get()) {
                Thread.sleep(STATUS_EVERY.toMillis());
                Map<String, SequenceLedger> ledgers = consumer.ledgers();
                if (ledgers.isEmpty()) {
                    log.info("nothing yet via {}", consuming.journal().lastLogonUri());
                    continue;
                }
                ledgers.forEach((run, ledger) -> log.info("run {}: {} via {}", run, ledger.report(settings.count()),
                        consuming.journal().lastLogonUri()));
                if (ledgers.values().stream().anyMatch(l -> l.hasAll(settings.count()))) {
                    log.info("a run reached {} messages; connection journal:\n{}", settings.count(),
                            consuming.journal().describe());
                    return ledgers.values().stream().allMatch(l -> l.duplicates() == 0 && l.outOfOrder() == 0) ? 0 : 1;
                }
            }
            return 0;
        }
    }

    private static boolean awaitAll(SequenceLedger ledger, long expected, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        Instant lastStatus = Instant.now();
        while (!ledger.hasAll(expected)) {
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            if (Duration.between(lastStatus, Instant.now()).compareTo(STATUS_EVERY) >= 0) {
                lastStatus = Instant.now();
                log.info("waiting for the consumer: {}", ledger.report(expected));
            }
            Thread.sleep(100);
        }
        return true;
    }

    private static int verdict(HaSettings settings, OrderPublisher publisher, ConnectionJournal publisherJournal,
                               ConnectionJournal consumerJournal, SequenceLedger ledger, boolean flushed, boolean drained) {
        // Logons minus the first: a failover is a new logon somewhere. (Not the
        // Disconnected count -- one failover announces that twice, once for the
        // connection that dropped and once for the redial of the dead instance
        // that fails before the chooser moves on.)
        long publisherFailovers = Math.max(0, publisherJournal.logonUris().size() - 1);
        long consumerFailovers = Math.max(0, consumerJournal.logonUris().size() - 1);
        boolean lossless = flushed && drained && ledger.duplicates() == 0 && ledger.outOfOrder() == 0;
        String summary = String.format(
                "VERDICT: %s%n  published %d, unacknowledged %d%n  consumer %s%n  publisher failed over %d time(s), "
                        + "consumer failed over %d time(s)%n  publisher logons %s%n  consumer logons %s",
                lossless ? "no message lost, none duplicated, order preserved" : "PROBLEM, see the counts",
                settings.count(), publisher.unpersisted(), ledger.report(settings.count()),
                publisherFailovers, consumerFailovers, publisherJournal.logonUris(), consumerJournal.logonUris());
        if (publisherFailovers == 0 && consumerFailovers == 0) {
            summary += "\n  (no instance was killed during this run -- run 'ha-compose.sh failover' next time)";
        }
        log.info(summary);
        return lossless ? 0 : 1;
    }
}
