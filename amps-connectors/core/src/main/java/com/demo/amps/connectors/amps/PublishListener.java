package com.demo.amps.connectors.amps;

/**
 * What a publisher reports about the fate of what it sent, by AMPS client sequence.
 *
 * <p>The two callbacks are the two things the AMPS client learns about a publish after the
 * call that issued it has returned. A <em>persisted</em> ack is cumulative -- the server says
 * "everything up to this sequence is in the transaction log" -- and the client hands it to its
 * publish store as {@code discardUpTo(seqno)}; {@link #persistedUpTo(long)} is that same
 * number, seen through {@code ObservingStore}. A <em>failed write</em> is the opposite fate
 * for one sequence: the server refused the command, or already had it (a replay after a
 * reconnect answered as a duplicate); the client reports it through its
 * {@code FailedWriteHandler} and discards the entry either way, so nothing will retry it.
 *
 * <p>Both are delivered on the AMPS client's <em>receive</em> thread, between the acks it
 * is processing. An implementation must be cheap and must not throw -- the store wrapper
 * contains an exception so the client never sees it, but everything queued behind the
 * callback waits while it runs.
 *
 * <p>The sequences are out-side numbers, the ones the publish store assigned; a source never
 * sees them. {@code PersistedAckTracker} is what turns them back into in-side acknowledgments.
 */
public interface PublishListener {

    /**
     * AMPS has persisted every command up to and including {@code seqno}.
     *
     * @param seqno the highest client sequence the server has acknowledged as persisted; a
     *     number already reported, or a lower one, says nothing new
     */
    void persistedUpTo(long seqno);

    /**
     * AMPS refused, or already had, the command with this sequence. The client discards it
     * either way, so it will not be replayed.
     *
     * @param seqno the command's client sequence, or {@code 0} when the ack carried none
     * @param reason the server's reason, one of the {@code Message.Reason} constants --
     *     {@code Duplicate} for a replay the server had already persisted
     */
    void failedWrite(long seqno, int reason);
}
