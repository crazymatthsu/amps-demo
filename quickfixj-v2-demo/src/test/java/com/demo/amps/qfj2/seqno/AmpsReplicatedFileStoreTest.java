package com.demo.amps.qfj2.seqno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.qfj2.support.InMemorySeqnoReplicator;
import java.io.Closeable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.FileStoreFactory;
import quickfix.MessageStore;
import quickfix.RuntimeError;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * The store and its factory against QuickFIX/J's real file store in a temp
 * directory, with the replicator in memory: what gets replicated, and what a
 * starting session does with what it finds.
 */
class AmpsReplicatedFileStoreTest {

    private static final SessionID ID = new SessionID("FIX.4.2", "DROPCOPY", "VENUE");

    @TempDir
    Path dir;

    private SessionSettings settings() {
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "initiator");
        settings.setString("FileStorePath", dir.resolve("store").toString());
        settings.setString(ID, "BeginString", "FIX.4.2");
        settings.setString(ID, "SenderCompID", "DROPCOPY");
        settings.setString(ID, "TargetCompID", "VENUE");
        return settings;
    }

    private AmpsReplicatedFileStoreFactory factory(InMemorySeqnoReplicator replicator, RecoveryPolicy policy,
                                                   boolean requireAmps, String source) {
        WriteBehindPublisher publisher = new WriteBehindPublisher(replicator, Duration.ZERO,
                Duration.ofMillis(50), Duration.ofSeconds(5));
        return new AmpsReplicatedFileStoreFactory(settings(), replicator, publisher, policy, requireAmps, source);
    }

    private static SeqnoSnapshot checkpoint(int sender, int target, long revision, String source) {
        return new SeqnoSnapshot(ID.toString(), sender, target, Instant.parse("2026-09-17T08:00:00Z"),
                Instant.parse("2026-09-17T09:00:00Z"), source, revision);
    }

    /** What the file on disk says, read the way the engine reads it. */
    private String fileNumbers() throws Exception {
        MessageStore plain = new FileStoreFactory(settings()).create(ID);
        try {
            return plain.getNextSenderMsgSeqNum() + "/" + plain.getNextTargetMsgSeqNum();
        } finally {
            ((Closeable) plain).close();
        }
    }

    private void presetFile(int sender, int target) throws Exception {
        MessageStore plain = new FileStoreFactory(settings()).create(ID);
        plain.setNextSenderMsgSeqNum(sender);
        plain.setNextTargetMsgSeqNum(target);
        ((Closeable) plain).close();
    }

    @Test
    @DisplayName("every increment writes the file first, then replicates the new numbers")
    void incrementsReplicateTheNewNumbers() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        try (AmpsReplicatedFileStoreFactory factory = factory(replicator, RecoveryPolicy.AMPS_WINS, true, "host-a")) {
            MessageStore store = factory.create(ID);
            store.incrNextSenderMsgSeqNum();
            store.incrNextSenderMsgSeqNum();
            store.incrNextSenderMsgSeqNum();
            store.incrNextTargetMsgSeqNum();
            store.incrNextTargetMsgSeqNum();
            assertThat(factory.publisher().flush(Duration.ofSeconds(5))).isTrue();

            SeqnoSnapshot latest = replicator.latest(ID.toString()).orElseThrow();
            assertThat(latest.numbers()).isEqualTo("4/3");
            assertThat(latest.source()).isEqualTo("host-a");
            assertThat(fileNumbers()).isEqualTo("4/3");

            // The startup checkpoint came first, and revisions only go up.
            assertThat(replicator.history().get(0).numbers()).isEqualTo("1/1");
            assertThat(replicator.history()).extracting(SeqnoSnapshot::revision).isSorted().doesNotHaveDuplicates();
            ((Closeable) store).close();
        }
    }

    @Test
    @DisplayName("nothing in AMPS: the file stands and becomes the first checkpoint")
    void startsFromTheFileWhenAmpsHasNothing() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        presetFile(5, 9);
        try (AmpsReplicatedFileStoreFactory factory = factory(replicator, RecoveryPolicy.AMPS_WINS, true, "host-a")) {
            MessageStore store = factory.create(ID);
            RecoveryReport report = factory.lastRecovery(ID).orElseThrow();
            assertThat(report.applied()).isEqualTo(SeqnoRecovery.Applied.FILE);
            assertThat(report.ampsCheckpoint()).isEmpty();
            assertThat(store.getNextSenderMsgSeqNum()).isEqualTo(5);
            assertThat(store.getNextTargetMsgSeqNum()).isEqualTo(9);
            assertThat(factory.publisher().flush(Duration.ofSeconds(5))).isTrue();
            assertThat(replicator.latest(ID.toString())).get().extracting(SeqnoSnapshot::numbers).isEqualTo("5/9");
            ((Closeable) store).close();
        }
    }

    @Test
    @DisplayName("an empty disk takes AMPS's numbers, writes them to the file, and carries the revision on")
    void recoversFromAmpsOntoAnEmptyDisk() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        replicator.seed(checkpoint(12, 40, 57, "prod-a"));
        try (AmpsReplicatedFileStoreFactory factory = factory(replicator, RecoveryPolicy.AMPS_WINS, true, "dr-b")) {
            MessageStore store = factory.create(ID);
            RecoveryReport report = factory.lastRecovery(ID).orElseThrow();
            assertThat(report.applied()).isEqualTo(SeqnoRecovery.Applied.AMPS);
            assertThat(report.fileSenderBefore()).isEqualTo(1);
            assertThat(report.fileTargetBefore()).isEqualTo(1);
            assertThat(store.getNextSenderMsgSeqNum()).isEqualTo(12);
            assertThat(store.getNextTargetMsgSeqNum()).isEqualTo(40);
            assertThat(fileNumbers()).isEqualTo("12/40");

            assertThat(factory.publisher().flush(Duration.ofSeconds(5))).isTrue();
            SeqnoSnapshot latest = replicator.latest(ID.toString()).orElseThrow();
            assertThat(latest.numbers()).isEqualTo("12/40");
            assertThat(latest.source()).isEqualTo("dr-b");
            assertThat(latest.revision()).isEqualTo(58);
            ((Closeable) store).close();
        }
    }

    @Test
    @DisplayName("file-wins keeps the file and publishes it over the AMPS checkpoint")
    void fileWinsPublishesTheFile() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        replicator.seed(checkpoint(12, 40, 57, "prod-a"));
        presetFile(5, 6);
        try (AmpsReplicatedFileStoreFactory factory = factory(replicator, RecoveryPolicy.FILE_WINS, true, "host-a")) {
            MessageStore store = factory.create(ID);
            assertThat(factory.lastRecovery(ID).orElseThrow().applied()).isEqualTo(SeqnoRecovery.Applied.FILE);
            assertThat(store.getNextSenderMsgSeqNum()).isEqualTo(5);
            assertThat(factory.publisher().flush(Duration.ofSeconds(5))).isTrue();
            assertThat(replicator.latest(ID.toString())).get().extracting(SeqnoSnapshot::numbers).isEqualTo("5/6");
            ((Closeable) store).close();
        }
    }

    @Test
    @DisplayName("highest merges per number")
    void highestMerges() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        replicator.seed(checkpoint(12, 40, 57, "prod-a"));
        presetFile(20, 6);
        try (AmpsReplicatedFileStoreFactory factory = factory(replicator, RecoveryPolicy.HIGHEST, true, "host-a")) {
            MessageStore store = factory.create(ID);
            assertThat(factory.lastRecovery(ID).orElseThrow().applied()).isEqualTo(SeqnoRecovery.Applied.MERGED);
            assertThat(store.getNextSenderMsgSeqNum()).isEqualTo(20);
            assertThat(store.getNextTargetMsgSeqNum()).isEqualTo(40);
            assertThat(fileNumbers()).isEqualTo("20/40");
            ((Closeable) store).close();
        }
    }

    @Test
    @DisplayName("AMPS unreadable and required: the engine refuses to start, and says which knob overrides")
    void refusesToStartWhenAmpsIsUnreachable() {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        replicator.failNextLoads(1);
        try (AmpsReplicatedFileStoreFactory factory = factory(replicator, RecoveryPolicy.AMPS_WINS, true, "host-a")) {
            assertThatThrownBy(() -> factory.create(ID))
                    .isInstanceOf(RuntimeError.class)
                    .hasMessageContaining("refusing to start")
                    .hasMessageContaining("require-amps");
        }
    }

    @Test
    @DisplayName("AMPS unreadable but not required: start on the file, replicate once AMPS is back")
    void startsOnTheFileWhenAmpsIsUnreachableAndNotRequired() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        replicator.failNextLoads(1);
        presetFile(3, 4);
        try (AmpsReplicatedFileStoreFactory factory = factory(replicator, RecoveryPolicy.AMPS_WINS, false, "host-a")) {
            MessageStore store = factory.create(ID);
            assertThat(factory.lastRecovery(ID).orElseThrow().applied()).isEqualTo(SeqnoRecovery.Applied.FILE);
            assertThat(factory.publisher().flush(Duration.ofSeconds(5))).isTrue();
            assertThat(replicator.latest(ID.toString())).get().extracting(SeqnoSnapshot::numbers).isEqualTo("3/4");
            ((Closeable) store).close();
        }
    }

    @Test
    @DisplayName("a QuickFIX/J reset replicates 1/1 like any other change")
    void resetReplicatesOneOne() throws Exception {
        InMemorySeqnoReplicator replicator = new InMemorySeqnoReplicator();
        presetFile(30, 31);
        try (AmpsReplicatedFileStoreFactory factory = factory(replicator, RecoveryPolicy.AMPS_WINS, true, "host-a")) {
            MessageStore store = factory.create(ID);
            store.reset();
            assertThat(factory.publisher().flush(Duration.ofSeconds(5))).isTrue();
            assertThat(replicator.latest(ID.toString())).get().extracting(SeqnoSnapshot::numbers).isEqualTo("1/1");
            assertThat(fileNumbers()).isEqualTo("1/1");
            ((Closeable) store).close();
        }
    }
}
