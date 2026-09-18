package com.demo.amps.qfj2.seqno;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import quickfix.MessageStore;

/**
 * A QuickFIX/J {@link MessageStore} that is the ordinary file store with
 * every sequence-number change also handed to the write-behind publisher.
 *
 * <p>Order matters and is fixed: the delegate (QuickFIX/J's own
 * {@code FileStore}, which writes {@code .seqnums} synchronously) is updated
 * first, then a snapshot of the numbers it now holds is offered for
 * replication. The file is therefore always at least as new as AMPS, which is
 * the invariant the recovery policies assume. Message bodies
 * ({@link #set}/{@link #get}, what a resend request is answered from) stay in
 * the file only; they are not replicated.
 */
public final class AmpsReplicatedFileStore implements MessageStore, Closeable {

    private final MessageStore delegate;
    private final String sessionId;
    private final WriteBehindPublisher publisher;
    private final String source;
    private final AtomicLong revision;

    /**
     * @param delegate  the file store, already initialised
     * @param sessionId the session's id, the checkpoint's SOW key
     * @param publisher where snapshots go
     * @param source    stamped on every snapshot
     * @param revision  continues from the last checkpoint AMPS held, so
     *                  revisions stay monotonic across a failover
     */
    public AmpsReplicatedFileStore(MessageStore delegate, String sessionId, WriteBehindPublisher publisher,
                                   String source, AtomicLong revision) {
        this.delegate = delegate;
        this.sessionId = sessionId;
        this.publisher = publisher;
        this.source = source;
        this.revision = revision;
    }

    /** The numbers as the file holds them right now. */
    public SeqnoSnapshot snapshot() throws IOException {
        return new SeqnoSnapshot(sessionId, delegate.getNextSenderMsgSeqNum(), delegate.getNextTargetMsgSeqNum(),
                delegate.getCreationTime().toInstant(), Instant.now(), source, revision.incrementAndGet());
    }

    public MessageStore delegate() {
        return delegate;
    }

    public String sessionId() {
        return sessionId;
    }

    private void replicate() throws IOException {
        publisher.offer(snapshot());
    }

    @Override
    public boolean set(int sequence, String message) throws IOException {
        return delegate.set(sequence, message);
    }

    @Override
    public void get(int startSequence, int endSequence, Collection<String> messages) throws IOException {
        delegate.get(startSequence, endSequence, messages);
    }

    @Override
    public int getNextSenderMsgSeqNum() throws IOException {
        return delegate.getNextSenderMsgSeqNum();
    }

    @Override
    public int getNextTargetMsgSeqNum() throws IOException {
        return delegate.getNextTargetMsgSeqNum();
    }

    @Override
    public void setNextSenderMsgSeqNum(int next) throws IOException {
        delegate.setNextSenderMsgSeqNum(next);
        replicate();
    }

    @Override
    public void setNextTargetMsgSeqNum(int next) throws IOException {
        delegate.setNextTargetMsgSeqNum(next);
        replicate();
    }

    @Override
    public void incrNextSenderMsgSeqNum() throws IOException {
        delegate.incrNextSenderMsgSeqNum();
        replicate();
    }

    @Override
    public void incrNextTargetMsgSeqNum() throws IOException {
        delegate.incrNextTargetMsgSeqNum();
        replicate();
    }

    @Override
    public Date getCreationTime() throws IOException {
        return delegate.getCreationTime();
    }

    @Override
    public void reset() throws IOException {
        delegate.reset();
        replicate();
    }

    @Override
    public void refresh() throws IOException {
        delegate.refresh();
        replicate();
    }

    @Override
    public void close() throws IOException {
        if (delegate instanceof Closeable closeable) {
            closeable.close();
        }
    }
}
