package com.demo.amps.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.demo.amps.market.v1.InstrumentSnapshot;
import com.demo.amps.market.v1.Order;
import com.demo.amps.market.v1.PriceLevel;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeltaBuilderTest {

    private static final List<String> ORDER_KEY = List.of("order_id");
    private static final List<String> SYMBOL_KEY = List.of("symbol");

    @Test
    @DisplayName("a quote tick sends only the quote, not the reference data")
    void tickOnlySendsTheQuote() {
        InstrumentSnapshot before = Fixtures.instrument("AAPL");
        InstrumentSnapshot after = Fixtures.tick(before, new Random(1));

        DeltaBuilder.Delta delta = DeltaBuilder.between(before, after, SYMBOL_KEY);
        String json = delta.json();

        assertTrue(json.contains("\"quote\""), json);
        assertFalse(json.contains("\"reference\""),
                "static reference data must not be re-sent on a tick: " + json);
        assertFalse(json.contains("prospectusUrl"), json);
        assertTrue(json.contains("\"symbol\":\"AAPL\""),
                "the SOW key must travel with every delta: " + json);
    }

    @Test
    @DisplayName("the delta is an order of magnitude smaller than the full record")
    void deltaIsMuchSmaller() {
        InstrumentSnapshot before = Fixtures.instrument("MSFT");
        InstrumentSnapshot after = Fixtures.tick(before, new Random(2));

        int fullBytes = JsonCodec.byteSize(JsonCodec.toJson(after));
        int deltaBytes = JsonCodec.byteSize(DeltaBuilder.between(before, after, SYMBOL_KEY).json());

        assertTrue(deltaBytes * 8 < fullBytes,
                "expected at least an 8x reduction, got " + deltaBytes + " B vs " + fullBytes + " B");
    }

    @Test
    @DisplayName("nested messages merge field by field")
    void nestedMessagesMergeRecursively() {
        InstrumentSnapshot before = Fixtures.instrument("GOOG");
        InstrumentSnapshot after = before.toBuilder()
                .setQuote(before.getQuote().toBuilder().setBid(123.45).build())
                .build();

        String json = DeltaBuilder.between(before, after, SYMBOL_KEY).json();

        assertTrue(json.contains("\"bid\":123.45"), json);
        assertFalse(json.contains("\"ask\""),
                "an untouched sibling field must not appear in the delta: " + json);
    }

    @Test
    @DisplayName("a field reset to its default value is still sent explicitly")
    void resettingToDefaultIsExplicit() {
        Order before = Fixtures.order(1).toBuilder().setFilledQuantity(50).build();
        Order after = before.toBuilder().setFilledQuantity(0).build();

        String json = DeltaBuilder.between(before, after, ORDER_KEY).json();

        assertTrue(json.contains("\"filledQuantity\":0"),
                "omitting the field would leave the stale 50 in the SOW: " + json);
    }

    @Test
    @DisplayName("an unchanged record produces a key-only delta")
    void noChangeProducesKeyOnlyDelta() {
        Order order = Fixtures.order(3);

        DeltaBuilder.Delta delta = DeltaBuilder.between(order, order, ORDER_KEY);

        assertTrue(delta.isEmpty() || delta.changedFields().size() == 1);
        assertEquals("{\"orderId\":\"" + order.getOrderId() + "\"}", delta.json());
    }

    @Test
    @DisplayName("arrays are replaced as a unit, so touching one level re-sends the ladder")
    void arraysAreReplacedWholesale() {
        InstrumentSnapshot before = Fixtures.instrument("TSLA");
        InstrumentSnapshot after = before.toBuilder()
                .setBook(0, PriceLevel.newBuilder(before.getBook(0)).setBidSize(999).build())
                .build();

        String json = DeltaBuilder.between(before, after, SYMBOL_KEY).json();

        assertTrue(json.contains("\"book\""), json);
        assertEquals(before.getBookCount(), countOccurrences(json, "\"level\""),
                "every level should be present, proving arrays are not merged positionally");
    }

    @Test
    @DisplayName("clearing a submessage cannot be expressed as a merge")
    void clearingRequiresAFullPublish() {
        InstrumentSnapshot before = Fixtures.instrument("NVDA");
        InstrumentSnapshot after = before.toBuilder().clearQuote().build();

        DeltaBuilder.Delta delta = DeltaBuilder.between(before, after, SYMBOL_KEY);

        assertTrue(delta.fullPublishRequired(),
                "callers must fall back to publish() when a field disappears");
    }

    @Test
    @DisplayName("applying the delta to the old record reproduces the new record")
    void deltaAppliedReproducesTarget() {
        InstrumentSnapshot before = Fixtures.instrument("AMZN");
        InstrumentSnapshot after = Fixtures.tick(before, new Random(4));

        DeltaBuilder.Delta delta = DeltaBuilder.between(before, after, SYMBOL_KEY);

        assertEquals(after, DeltaBuilder.apply(before, delta));
    }

    @Test
    @DisplayName("a delta resetting a counter to zero still overwrites the stored value")
    void deltaResetOverwrites() {
        Order before = Fixtures.order(1).toBuilder().setFilledQuantity(50).build();
        Order after = before.toBuilder().setFilledQuantity(0).build();

        DeltaBuilder.Delta delta = DeltaBuilder.between(before, after, ORDER_KEY);

        assertEquals(0, ((Order) DeltaBuilder.apply(before, delta)).getFilledQuantity());
    }

    @Test
    @DisplayName("protobuf's own merge primitives cannot stand in for the AMPS merge")
    void protobufMergePrimitivesAreNotEquivalent() {
        Order before = Fixtures.order(1).toBuilder().setFilledQuantity(50).build();
        Order after = before.toBuilder().setFilledQuantity(0).build();
        String deltaJson = DeltaBuilder.between(before, after, ORDER_KEY).json();

        // 1. The JSON parser refuses a builder whose fields are already populated:
        //    it parses records, it does not merge them.
        assertThrows(IllegalArgumentException.class,
                () -> JsonCodec.merge(deltaJson, before.toBuilder()));

        // 2. Builder.mergeFrom ignores source fields holding default values, so the
        //    reset to zero disappears. This is why DeltaBuilder.apply exists.
        Order viaProtobufMerge = before.toBuilder()
                .mergeFrom(JsonCodec.fromJson(deltaJson, Order.getDefaultInstance()))
                .build();
        assertEquals(50, viaProtobufMerge.getFilledQuantity(),
                "protobuf merge drops default-valued scalars -- documenting, not endorsing");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
