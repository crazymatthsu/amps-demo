package com.demo.amps.connectors.amps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crankuptheamps.client.JSONMessage;
import com.crankuptheamps.client.MemoryPublishStore;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.PublishStoreResizeHandler;
import com.crankuptheamps.client.Store;
import com.crankuptheamps.client.exception.StoreException;
import com.crankuptheamps.client.exception.TimedOutException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Against a real {@link MemoryPublishStore}, with no server: the store assigns the sequences
 * and keeps the entries exactly as it does under a client, so what the wrapper reports can be
 * checked against what the store itself says it holds.
 */
class ObservingStoreTest {

    /** A listener that writes down what it hears, and can be told to throw. */
    private static final class Recording implements PublishListener {

        final List<Long> persisted = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        boolean throwing;

        @Override
        public void persistedUpTo(long seqno) {
            if (throwing) {
                throw new IllegalStateException("listener broke");
            }
            persisted.add(seqno);
        }

        @Override
        public void failedWrite(long seqno, int reason) {
            failed.add(seqno + ":" + reason);
        }
    }

    private static Message publish(String topic, String data) {
        Message message = new JSONMessage(
                StandardCharsets.UTF_8.newEncoder(), StandardCharsets.UTF_8.newDecoder());
        message.setCommand(Message.Command.Publish).setTopic(topic).setData(data);
        return message;
    }

    private final Recording listener = new Recording();

    @Test
    @DisplayName("a store assigns the sequence, and the discard that follows reports it as persisted")
    void reportsEveryDiscardWithTheStoresOwnSequence() throws Exception {
        MemoryPublishStore real = new MemoryPublishStore(10);
        ObservingStore store = new ObservingStore(real, listener, "orders");
        assertThat(store.delegate()).isSameAs(real);

        Message first = publish("sow/orders", "{\"n\":1}");
        Message second = publish("sow/orders", "{\"n\":2}");
        Message third = publish("sow/orders", "{\"n\":3}");
        store.store(first);
        store.store(second);
        store.store(third);
        long one = first.getSequence();
        long two = second.getSequence();
        long three = third.getSequence();
        assertThat(one).isPositive();
        assertThat(two).isEqualTo(one + 1);
        assertThat(three).isEqualTo(one + 2);
        assertThat(store.unpersistedCount()).isEqualTo(3);
        assertThat(store.getLowestUnpersisted()).isEqualTo(one);
        assertThat(listener.persisted).as("storing is not persisting").isEmpty();

        store.discardUpTo(two);

        assertThat(listener.persisted).containsExactly(two);
        assertThat(real.unpersistedCount()).as("the real store discarded first").isEqualTo(1);
        assertThat(store.getLowestUnpersisted()).isEqualTo(three);
        assertThat(store.getLastPersisted()).isEqualTo(two);

        store.discardUpTo(three);
        assertThat(listener.persisted).containsExactly(two, three);
        assertThat(real.unpersistedCount()).isZero();

        // The logon ack of a fresh client discards up to 0: nothing to report.
        store.discardUpTo(0);
        assertThat(listener.persisted).containsExactly(two, three);
        assertThat(store.listenerFailures()).isZero();
    }

    @Test
    @DisplayName("a listener that throws is contained: the store has discarded, the client never sees it")
    void containsAThrowingListener() throws Exception {
        MemoryPublishStore real = new MemoryPublishStore(10);
        ObservingStore store = new ObservingStore(real, listener, "orders");
        Message message = publish("sow/orders", "{}");
        store.store(message);
        listener.throwing = true;

        store.discardUpTo(message.getSequence());

        assertThat(real.unpersistedCount()).isZero();
        assertThat(store.listenerFailures()).isEqualTo(1);
        assertThat(listener.persisted).isEmpty();
    }

    @Test
    @DisplayName("replay, flush and the rest reach the real store with their real effects")
    void delegatesTheStoreOperations() throws Exception {
        MemoryPublishStore real = new MemoryPublishStore(10);
        ObservingStore store = new ObservingStore(real, listener, "orders");
        // Nothing stored: a flush has nothing to wait for and returns at once.
        store.flush();
        store.flush(10);

        Message first = publish("sow/orders", "{\"n\":1}");
        Message second = publish("sow/orders", "{\"n\":2}");
        store.store(first);
        store.store(second);

        List<Long> replayed = new ArrayList<>();
        store.replay(message -> replayed.add(message.getSequence()));
        assertThat(replayed).containsExactly(first.getSequence(), second.getSequence());

        AtomicInteger single = new AtomicInteger();
        assertThat(store.replaySingle(message -> single.incrementAndGet(), second.getSequence()))
                .isTrue();
        assertThat(single.get()).isEqualTo(1);
        assertThat(store.replaySingle(message -> single.incrementAndGet(), second.getSequence() + 50))
                .as("nothing stored under that sequence").isFalse();

        // Two entries unpersisted: a bounded flush times out, exactly as publishFlush would.
        assertThatThrownBy(() -> store.flush(20)).isInstanceOf(TimedOutException.class);

        store.setErrorOnPublishGap(true);
        store.discardUpTo(second.getSequence());
        assertThat(store.getErrorOnPublishGap()).as("read back from the real store").isTrue();
        store.flush(10);
        assertThat(listener.persisted).containsExactly(second.getSequence());
        assertThat(store.toString()).startsWith("ObservingStore[");
        store.close();
    }

    @Test
    @DisplayName("every method of the Store interface goes to the wrapped store, untouched")
    void delegatesEveryMethod() throws Exception {
        List<String> calls = new ArrayList<>();
        Store fake = new Store() {
            @Override
            public void store(Message message) {
                calls.add("store");
            }

            @Override
            public void discardUpTo(long sequence) {
                calls.add("discardUpTo(" + sequence + ")");
            }

            @Override
            public void replay(StoreReplayer replayer) {
                calls.add("replay");
            }

            @Override
            public boolean replaySingle(StoreReplayer replayer, long sequence) {
                calls.add("replaySingle(" + sequence + ")");
                return true;
            }

            @Override
            public long unpersistedCount() {
                calls.add("unpersistedCount");
                return 7;
            }

            @Override
            public long getLowestUnpersisted() {
                calls.add("getLowestUnpersisted");
                return 8;
            }

            @Override
            public void flush() {
                calls.add("flush");
            }

            @Override
            public void flush(long timeout) {
                calls.add("flush(" + timeout + ")");
            }

            @Override
            public void setResizeHandler(PublishStoreResizeHandler handler) {
                calls.add("setResizeHandler");
            }

            @Override
            public long getLastPersisted() {
                calls.add("getLastPersisted");
                return 9;
            }

            @Override
            public void setMessage(Message message) {
                calls.add("setMessage");
            }

            @Override
            public boolean getErrorOnPublishGap() {
                calls.add("getErrorOnPublishGap");
                return true;
            }

            @Override
            public void setErrorOnPublishGap(boolean errorOnPublishGap) {
                calls.add("setErrorOnPublishGap(" + errorOnPublishGap + ")");
            }

            @Override
            public void close() throws IOException {
                calls.add("close");
                throw new IOException("buffer");
            }
        };
        ObservingStore store = new ObservingStore(fake, listener, "orders");

        store.store(null);
        store.discardUpTo(42);
        store.replay(null);
        assertThat(store.replaySingle(null, 43)).isTrue();
        assertThat(store.unpersistedCount()).isEqualTo(7);
        assertThat(store.getLowestUnpersisted()).isEqualTo(8);
        store.flush();
        store.flush(44);
        store.setResizeHandler(null);
        assertThat(store.getLastPersisted()).isEqualTo(9);
        store.setMessage(null);
        assertThat(store.getErrorOnPublishGap()).isTrue();
        store.setErrorOnPublishGap(false);
        assertThatThrownBy(store::close)
                .as("a checked exception out of close is narrowed to the store's own")
                .isInstanceOf(StoreException.class)
                .hasCauseInstanceOf(IOException.class);

        assertThat(calls).containsExactly("store", "discardUpTo(42)", "replay",
                "replaySingle(43)", "unpersistedCount", "getLowestUnpersisted", "flush",
                "flush(44)", "setResizeHandler", "getLastPersisted", "setMessage",
                "getErrorOnPublishGap", "setErrorOnPublishGap(false)", "close");
        assertThat(listener.persisted).containsExactly(42L);
    }
}
