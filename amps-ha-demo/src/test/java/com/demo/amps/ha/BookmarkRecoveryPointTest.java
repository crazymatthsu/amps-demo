package com.demo.amps.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.JSONMessage;
import com.crankuptheamps.client.MemoryBookmarkStore;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.RecoveryPoint;
import com.crankuptheamps.client.RecoveryPointAdapter;
import com.crankuptheamps.client.fields.BookmarkField;
import com.crankuptheamps.client.fields.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the record in the recovery point topic means, shown on the AMPS
 * client's own {@link MemoryBookmarkStore} with the SOW replaced by a map.
 * This is the contract {@link com.demo.amps.ha.api.HaBookmarkSubscriber} builds on, and the part of
 * the failover test that needs no server: the store is the library's, only the
 * adapter is a stand-in.
 *
 * <p>Bookmarks here are {@code publisher|sequence|}, the shape AMPS uses;
 * publisher {@code 1} stands for one instance's journal.
 */
class BookmarkRecoveryPointTest {

    static final String SUB = "orders-sub";

    @Test
    void aStoreSeededFromARecoveryPointResumesThereAndFiltersTheRedeliveredMessage() throws Exception {
        MapAdapter adapter = new MapAdapter(Map.of(SUB, "1|5|"));
        MemoryBookmarkStore store = new MemoryBookmarkStore(1, adapter);

        assertEquals("1|5|", store.getMostRecent(new Field(SUB)).toString(),
                "MOST_RECENT should be the recovery point");
        assertTrue(store.isDiscarded(message("1|5|")),
                "a replay from the recovery point delivers that message again; the store must drop it");
        assertFalse(store.isDiscarded(message("1|6|")), "the message after the recovery point is new");
        assertEquals(Client.Bookmarks.EPOCH, store.getMostRecent(new Field("another-sub")).toString(),
                "a subscription with no recovery point starts from the epoch");
        assertEquals(List.of(), adapter.updates, "recovery must not write anything back");
    }

    @Test
    void processingAMessageMovesTheRecoveryPointPastIt() throws Exception {
        MapAdapter adapter = new MapAdapter(Map.of(SUB, "1|5|"));
        MemoryBookmarkStore store = new MemoryBookmarkStore(1, adapter);

        store.discard(receive(store, "1|6|"));
        assertEquals("1|6|", adapter.points.get(SUB));
        store.discard(receive(store, "1|7|"));
        assertEquals("1|7|", adapter.points.get(SUB));
        assertEquals("1|7|", store.getMostRecent(new Field(SUB)).toString());
        assertEquals(List.of(SUB + "=1|6|", SUB + "=1|7|"), adapter.updates);
    }

    @Test
    void theRecoveryPointNeverPassesAMessageStillBeingProcessed() throws Exception {
        MapAdapter adapter = new MapAdapter(Map.of());
        MemoryBookmarkStore store = new MemoryBookmarkStore(1, adapter);

        Message first = receive(store, "1|1|");
        Message second = receive(store, "1|2|");
        store.discard(second);
        assertFalse(adapter.points.containsKey(SUB),
                "the first message is still in flight: a replacement must get it again, so nothing to resume from yet");
        store.discard(first);
        assertEquals("1|2|", adapter.points.get(SUB), "once the first is done, everything up to the second is");
    }

    @Test
    void purgingASubscriptionForgetsItsRecoveryPoint() throws Exception {
        MapAdapter adapter = new MapAdapter(Map.of(SUB, "1|5|"));
        MemoryBookmarkStore store = new MemoryBookmarkStore(1, adapter);

        store.purge(new Field(SUB));
        assertFalse(adapter.points.containsKey(SUB));
        assertEquals(Client.Bookmarks.EPOCH, store.getMostRecent(new Field(SUB)).toString());
    }

    /** What the client does with a delivered message before the handler sees it: check, then log. */
    private static Message receive(MemoryBookmarkStore store, String bookmark) throws Exception {
        Message message = message(bookmark);
        assertFalse(store.isDiscarded(message), bookmark + " should be new");
        store.log(message);
        return message;
    }

    private static Message message(String bookmark) {
        Message message = new JSONMessage(StandardCharsets.UTF_8.newEncoder(), StandardCharsets.UTF_8.newDecoder());
        message.setSubId(SUB);
        message.setBookmark(bookmark);
        return message;
    }

    /** The SOW topic as a map: subscription id to bookmark, and every update in order. */
    static final class MapAdapter implements RecoveryPointAdapter {
        final Map<String, String> points = new LinkedHashMap<>();
        final List<String> updates = new ArrayList<>();
        private final Iterator<Map.Entry<String, String>> recovery;

        MapAdapter(Map<String, String> initial) {
            points.putAll(initial);
            recovery = new ArrayList<>(points.entrySet()).iterator();
        }

        @Override
        public boolean hasNext() {
            return recovery.hasNext();
        }

        @Override
        public RecoveryPoint next() {
            Map.Entry<String, String> entry = recovery.next();
            return new Point(entry.getKey(), entry.getValue());
        }

        @Override
        public Iterator<RecoveryPoint> iterator() {
            return this;
        }

        @Override
        public void update(RecoveryPoint recoveryPoint) {
            String bookmark = recoveryPoint.getBookmark().toString();
            points.put(recoveryPoint.getSubId().toString(), bookmark);
            updates.add(recoveryPoint.getSubId() + "=" + bookmark);
        }

        @Override
        public void purge(Field subId) {
            points.remove(subId.toString());
        }

        @Override
        public void purge() {
            points.clear();
        }

        @Override
        public void close() {
        }
    }

    record Point(String subId, String bookmark) implements RecoveryPoint {
        @Override
        public Field getSubId() {
            return new Field(subId);
        }

        @Override
        public BookmarkField getBookmark() {
            BookmarkField field = new BookmarkField();
            byte[] bytes = bookmark.getBytes(StandardCharsets.UTF_8);
            field.set(bytes, 0, bytes.length);
            return field;
        }

        @Override
        public RecoveryPoint copy() {
            return this;
        }
    }
}
