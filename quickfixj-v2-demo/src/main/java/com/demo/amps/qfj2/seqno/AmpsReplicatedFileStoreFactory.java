package com.demo.amps.qfj2.seqno;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.FileStoreFactory;
import quickfix.MessageStore;
import quickfix.MessageStoreFactory;
import quickfix.RuntimeError;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * The {@link MessageStoreFactory} to hand QuickFIX/J: creates the ordinary
 * file store for a session, <b>recovers</b> its numbers against the AMPS
 * checkpoint, and wraps it so every later change is replicated.
 *
 * <p>Recovery happens here, before QuickFIX/J ever reads the store, because
 * this is the one moment the engine is guaranteed not to be mid-session.
 * The sequence:
 *
 * <ol>
 *   <li>open the file store (QuickFIX/J's own {@code FileStoreFactory});
 *       an absent file reads as 1/1, which is what a DR box starts with</li>
 *   <li>read the session's checkpoint from AMPS</li>
 *   <li>{@link SeqnoRecovery#decide} which numbers to start with</li>
 *   <li>write them to the file if they differ</li>
 *   <li>publish the resulting state as a checkpoint, so AMPS is current
 *       whatever policy applied</li>
 * </ol>
 *
 * <p>If AMPS cannot be read and {@code requireAmps} is set (and the policy
 * is not file-wins), the engine refuses to start: starting on a stale file is
 * exactly the failure this store exists to prevent, and the error says which
 * knob overrides that.
 */
public final class AmpsReplicatedFileStoreFactory implements MessageStoreFactory, Closeable {

    private static final Logger log = LoggerFactory.getLogger(AmpsReplicatedFileStoreFactory.class);

    private final FileStoreFactory files;
    private final SeqnoReplicator replicator;
    private final WriteBehindPublisher publisher;
    private final RecoveryPolicy policy;
    private final boolean requireAmps;
    private final String source;
    private final Map<String, RecoveryReport> reports = new ConcurrentHashMap<>();

    public AmpsReplicatedFileStoreFactory(SessionSettings settings, SeqnoReplicator replicator,
                                          WriteBehindPublisher publisher, RecoveryPolicy policy,
                                          boolean requireAmps, String source) {
        this.files = new FileStoreFactory(settings);
        this.replicator = replicator;
        this.publisher = publisher;
        this.policy = policy;
        this.requireAmps = requireAmps;
        this.source = source;
    }

    @Override
    public MessageStore create(SessionID sessionID) {
        MessageStore file = files.create(sessionID);
        String id = sessionID.toString();
        try {
            int fileSender = file.getNextSenderMsgSeqNum();
            int fileTarget = file.getNextTargetMsgSeqNum();
            Optional<SeqnoSnapshot> checkpoint = loadCheckpoint(id, fileSender, fileTarget);
            SeqnoRecovery.Decision decision = SeqnoRecovery.decide(policy, fileSender, fileTarget, checkpoint);
            if (decision.nextSenderMsgSeqNum() != fileSender) {
                file.setNextSenderMsgSeqNum(decision.nextSenderMsgSeqNum());
            }
            if (decision.nextTargetMsgSeqNum() != fileTarget) {
                file.setNextTargetMsgSeqNum(decision.nextTargetMsgSeqNum());
            }
            AtomicLong revision = new AtomicLong(checkpoint.map(SeqnoSnapshot::revision).orElse(0L));
            AmpsReplicatedFileStore store = new AmpsReplicatedFileStore(file, id, publisher, source, revision);
            RecoveryReport report = new RecoveryReport(id, fileSender, fileTarget, checkpoint, decision);
            reports.put(id, report);
            log.info(report.describe());
            publisher.offer(store.snapshot());
            return store;
        } catch (IOException e) {
            closeQuietly(file);
            throw new RuntimeError("cannot initialise the sequence-number store for " + id + ": " + e, e);
        } catch (RuntimeException e) {
            // Includes quickfix.RuntimeError, which the AMPS lookup above throws
            // when the checkpoint is required and unreadable.
            closeQuietly(file);
            throw e;
        }
    }

    private Optional<SeqnoSnapshot> loadCheckpoint(String id, int fileSender, int fileTarget) {
        try {
            return replicator.load(id);
        } catch (Exception e) {
            if (requireAmps && policy != RecoveryPolicy.FILE_WINS) {
                throw new RuntimeError("cannot read the AMPS sequence-number checkpoint for " + id + " (" + e
                        + "): refusing to start on the file's " + fileSender + "/" + fileTarget + " alone. "
                        + "Fix the AMPS connection, or set qfj.seqno.require-amps=false or "
                        + "qfj.seqno.recovery=file-wins to start without it.", e);
            }
            log.warn("cannot read the AMPS checkpoint for {} ({}); starting on the file's {}/{} because "
                    + "require-amps is off or the policy is file-wins", id, e.toString(), fileSender, fileTarget);
            return Optional.empty();
        }
    }

    /** What recovery did for the session, once {@link #create} has run for it. */
    public Optional<RecoveryReport> lastRecovery(SessionID sessionID) {
        return Optional.ofNullable(reports.get(sessionID.toString()));
    }

    public WriteBehindPublisher publisher() {
        return publisher;
    }

    public SeqnoReplicator replicator() {
        return replicator;
    }

    public RecoveryPolicy policy() {
        return policy;
    }

    private static void closeQuietly(MessageStore store) {
        if (store instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Already failing; the original error is the one to report.
            }
        }
    }

    /** Drains and stops the write-behind thread. The replicator is closed by its owner. */
    @Override
    public void close() {
        publisher.close();
    }
}
