package com.demo.amps.qfj2.seqno;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import quickfix.ConfigError;
import quickfix.FileStoreFactory;
import quickfix.FileUtil;
import quickfix.MessageStore;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * Manual resequencing: read or rewrite the numbers in the file store, in
 * AMPS, or copy one onto the other.
 *
 * <p>The file is edited through QuickFIX/J's own {@code FileStoreFactory}, so
 * the bytes on disk are exactly what the engine will read -- never by hand.
 * Run it with the engine <b>stopped</b>: QuickFIX/J caches the numbers in
 * memory and holds the file open, so an edit under a running engine is
 * overwritten by its next heartbeat. AMPS can be edited while the engine
 * runs, but the engine reads its checkpoint only at startup, so the edit
 * takes effect on the next start (and {@code amps-wins} must be the policy
 * for it to win over the file).
 */
public final class SeqnoAdmin {

    /**
     * The file store's numbers. QuickFIX/J 2.x keeps them in two files next to
     * the message store, {@code <prefix>.senderseqnums} and
     * {@code <prefix>.targetseqnums}; {@code senderSeqNumFile} is the first,
     * and its directory is where all of them are.
     */
    public record FileNumbers(int nextSenderMsgSeqNum, int nextTargetMsgSeqNum, Instant creationTime,
                              Path senderSeqNumFile) {
        public String numbers() {
            return nextSenderMsgSeqNum + "/" + nextTargetMsgSeqNum;
        }
    }

    /** Both sides for one session. */
    public record State(String sessionId, Optional<FileNumbers> file, Optional<SeqnoSnapshot> amps) {
        public boolean inSync() {
            return file.isPresent() && amps.isPresent()
                    && file.get().nextSenderMsgSeqNum() == amps.get().nextSenderMsgSeqNum()
                    && file.get().nextTargetMsgSeqNum() == amps.get().nextTargetMsgSeqNum();
        }
    }

    private final SessionSettings settings;
    private final SeqnoReplicator replicator;
    private final String source;

    /**
     * @param settings   the engine's QuickFIX/J settings (for FileStorePath and the session list)
     * @param replicator where the checkpoints are; {@link SeqnoReplicator#NONE} for file-only use
     * @param source     stamped on checkpoints this tool writes
     */
    public SeqnoAdmin(SessionSettings settings, SeqnoReplicator replicator, String source) {
        this.settings = settings;
        this.replicator = replicator;
        this.source = source;
    }

    /** Every session the settings define. */
    public List<SessionID> sessions() {
        List<SessionID> ids = new ArrayList<>();
        for (Iterator<SessionID> it = settings.sectionIterator(); it.hasNext();) {
            ids.add(it.next());
        }
        return ids;
    }

    /** The session whose id prints as {@code id}, e.g. {@code FIX.4.2:DROPCOPY->VENUE}. */
    public SessionID session(String id) {
        return sessions().stream()
                .filter(candidate -> candidate.toString().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no session '" + id
                        + "' in the QuickFIX/J settings; available: " + sessions()));
    }

    /** Where QuickFIX/J keeps the session's next sender number (the target's is beside it). */
    public Path senderSeqNumFile(SessionID sessionID) throws ConfigError {
        String path = settings.getString(sessionID, FileStoreFactory.SETTING_FILE_STORE_PATH);
        return Path.of(path).toAbsolutePath().resolve(FileUtil.sessionIdFileName(sessionID) + ".senderseqnums");
    }

    /** Whether a file store exists for the session, without creating one. */
    public boolean hasFileStore(SessionID sessionID) throws ConfigError {
        return Files.isRegularFile(senderSeqNumFile(sessionID));
    }

    public State show(SessionID sessionID) throws Exception {
        Optional<FileNumbers> file = hasFileStore(sessionID)
                ? Optional.of(readFile(sessionID))
                : Optional.empty();
        return new State(sessionID.toString(), file, replicator.load(sessionID.toString()));
    }

    /** Rewrites either or both numbers in the file (creating the store if absent). */
    public FileNumbers setFile(SessionID sessionID, OptionalInt nextSender, OptionalInt nextTarget)
            throws Exception {
        MessageStore store = new FileStoreFactory(settings).create(sessionID);
        try {
            if (nextSender.isPresent()) {
                store.setNextSenderMsgSeqNum(nextSender.getAsInt());
            }
            if (nextTarget.isPresent()) {
                store.setNextTargetMsgSeqNum(nextTarget.getAsInt());
            }
            return numbersOf(store, sessionID);
        } finally {
            close(store);
        }
    }

    /**
     * Publishes a checkpoint with either or both numbers replaced. Numbers not
     * given keep AMPS's current value, or the file's if AMPS has none.
     */
    public SeqnoSnapshot setAmps(SessionID sessionID, OptionalInt nextSender, OptionalInt nextTarget)
            throws Exception {
        String id = sessionID.toString();
        Optional<SeqnoSnapshot> existing = replicator.load(id);
        Optional<FileNumbers> file = hasFileStore(sessionID)
                ? Optional.of(readFile(sessionID)) : Optional.empty();
        int baseSender = existing.map(SeqnoSnapshot::nextSenderMsgSeqNum)
                .orElseGet(() -> file.map(FileNumbers::nextSenderMsgSeqNum).orElse(1));
        int baseTarget = existing.map(SeqnoSnapshot::nextTargetMsgSeqNum)
                .orElseGet(() -> file.map(FileNumbers::nextTargetMsgSeqNum).orElse(1));
        Instant creation = existing.map(SeqnoSnapshot::creationTime)
                .orElseGet(() -> file.map(FileNumbers::creationTime).orElseGet(Instant::now));
        SeqnoSnapshot snapshot = new SeqnoSnapshot(id,
                nextSender.orElse(baseSender), nextTarget.orElse(baseTarget),
                creation, Instant.now(), source, existing.map(s -> s.revision() + 1).orElse(1L));
        replicator.publish(snapshot);
        return snapshot;
    }

    /** Publishes the file's numbers as the checkpoint. */
    public SeqnoSnapshot fileToAmps(SessionID sessionID) throws Exception {
        if (!hasFileStore(sessionID)) {
            throw new IllegalStateException("no file store for " + sessionID + " at " + senderSeqNumFile(sessionID));
        }
        FileNumbers file = readFile(sessionID);
        return setAmps(sessionID, OptionalInt.of(file.nextSenderMsgSeqNum()),
                OptionalInt.of(file.nextTargetMsgSeqNum()));
    }

    /** Writes the checkpoint's numbers into the file. */
    public FileNumbers ampsToFile(SessionID sessionID) throws Exception {
        SeqnoSnapshot checkpoint = replicator.load(sessionID.toString()).orElseThrow(() ->
                new IllegalStateException("no checkpoint in AMPS for " + sessionID));
        return setFile(sessionID, OptionalInt.of(checkpoint.nextSenderMsgSeqNum()),
                OptionalInt.of(checkpoint.nextTargetMsgSeqNum()));
    }

    private FileNumbers readFile(SessionID sessionID) throws Exception {
        MessageStore store = new FileStoreFactory(settings).create(sessionID);
        try {
            return numbersOf(store, sessionID);
        } finally {
            close(store);
        }
    }

    private FileNumbers numbersOf(MessageStore store, SessionID sessionID) throws IOException, ConfigError {
        return new FileNumbers(store.getNextSenderMsgSeqNum(), store.getNextTargetMsgSeqNum(),
                store.getCreationTime().toInstant(), senderSeqNumFile(sessionID));
    }

    private static void close(MessageStore store) throws IOException {
        if (store instanceof Closeable closeable) {
            closeable.close();
        }
    }
}
