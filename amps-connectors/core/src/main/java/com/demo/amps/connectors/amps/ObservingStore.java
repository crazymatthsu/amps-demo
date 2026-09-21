package com.demo.amps.connectors.amps;

import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.PublishStoreResizeHandler;
import com.crankuptheamps.client.Store;
import com.crankuptheamps.client.exception.DisconnectedException;
import com.crankuptheamps.client.exception.StoreException;
import com.crankuptheamps.client.exception.TimedOutException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A publish {@link Store} that tells a {@link PublishListener} what the client tells it.
 *
 * <p>The AMPS client has no "persisted" callback of its own. What it has is the publish
 * store: every persisted ack the server sends becomes {@code discardUpTo(sequence)} on the
 * store, cumulative, on the receive thread -- that is how the store learns it may drop what
 * it was keeping for replay. Wrapping the store is therefore the one place the number can be
 * seen without changing the client, and it is seen exactly when the client itself acts on
 * it. The logon ack goes through the same method, which is what covers acks that were in
 * flight when a connection dropped: the server tells a reconnecting client where it is, the
 * client discards up to there, and the listener hears about it like any other ack.
 *
 * <p>Every other method delegates untouched. The store this wraps is the real
 * {@code MemoryPublishStore} or {@code PublishStore}, and the client drives it hard --
 * {@code store} on every publish, {@code replay} on every logon, {@code flush} from
 * {@code publishFlush} -- so nothing here may cost more than a call.
 *
 * <p>The listener is called <em>after</em> the delegate has discarded, and an exception out
 * of it is contained here: the client would absorb it anyway, but it would absorb it
 * silently, and a listener that throws on the receive thread is a bug worth a counter and a
 * log line rather than a stall nobody can explain.
 */
final class ObservingStore implements Store {

    private static final Logger log = LoggerFactory.getLogger(ObservingStore.class);

    private final Store delegate;
    private final PublishListener listener;
    private final String connectorName;
    private final AtomicLong listenerFailures = new AtomicLong();

    /**
     * @param delegate the real store
     * @param listener who is told about every {@code discardUpTo}
     * @param connectorName the connector's name, for the log lines
     */
    ObservingStore(Store delegate, PublishListener listener, String connectorName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.connectorName = connectorName;
    }

    /** The store underneath. */
    Store delegate() {
        return delegate;
    }

    /** How many times the listener threw; each one was contained and logged. */
    long listenerFailures() {
        return listenerFailures.get();
    }

    @Override
    public void store(Message message) throws StoreException {
        delegate.store(message);
    }

    @Override
    public void discardUpTo(long sequence) throws StoreException {
        // The delegate first: if it refuses (a publish gap it was told to refuse, a store
        // that cannot write), nothing was persisted as far as this client is concerned.
        delegate.discardUpTo(sequence);
        if (sequence > 0) {
            notifyPersisted(sequence);
        }
    }

    private void notifyPersisted(long sequence) {
        try {
            listener.persistedUpTo(sequence);
        } catch (RuntimeException e) {
            long count = listenerFailures.incrementAndGet();
            if (count <= 10 || count % 1_000 == 0) {
                log.warn("[{}] publish listener threw on persisted ack {} ({} failure(s) so far)",
                        connectorName, sequence, count, e);
            }
        }
    }

    @Override
    public void replay(StoreReplayer replayer) throws StoreException, DisconnectedException {
        delegate.replay(replayer);
    }

    @Override
    public boolean replaySingle(StoreReplayer replayer, long sequence)
            throws StoreException, DisconnectedException {
        return delegate.replaySingle(replayer, sequence);
    }

    @Override
    public long unpersistedCount() {
        return delegate.unpersistedCount();
    }

    @Override
    public long getLowestUnpersisted() {
        return delegate.getLowestUnpersisted();
    }

    @Override
    public void flush() throws TimedOutException {
        delegate.flush();
    }

    @Override
    public void flush(long timeout) throws TimedOutException {
        delegate.flush(timeout);
    }

    @Override
    public void setResizeHandler(PublishStoreResizeHandler handler) {
        delegate.setResizeHandler(handler);
    }

    @Override
    public long getLastPersisted() throws StoreException {
        return delegate.getLastPersisted();
    }

    @Override
    public void setMessage(Message message) {
        delegate.setMessage(message);
    }

    @Override
    public boolean getErrorOnPublishGap() {
        return delegate.getErrorOnPublishGap();
    }

    @Override
    public void setErrorOnPublishGap(boolean errorOnPublishGap) {
        delegate.setErrorOnPublishGap(errorOnPublishGap);
    }

    /**
     * {@inheritDoc}
     *
     * @throws StoreException what the delegate threw, wrapped when it was not one already:
     *     {@code Store} inherits {@code throws Exception} from {@code AutoCloseable}, and
     *     narrowing it here keeps an interrupt out of the signature of a resource nobody
     *     closes in a try-with-resources
     */
    @Override
    public void close() throws StoreException {
        try {
            delegate.close();
        } catch (StoreException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("interrupted while closing the publish store", e);
        } catch (Exception e) {
            throw new StoreException("closing the publish store failed", e);
        }
    }

    @Override
    public String toString() {
        return "ObservingStore[" + delegate + "]";
    }
}
