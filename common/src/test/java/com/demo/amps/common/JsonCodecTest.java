package com.demo.amps.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.demo.amps.market.v1.InstrumentSnapshot;
import com.demo.amps.market.v1.Order;
import com.demo.amps.market.v1.OrderStatus;
import com.demo.amps.market.v1.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * These are not decorative tests: each one pins down a protobuf-JSON behaviour
 * that AMPS depends on. If one of them fails, something in the server config
 * (SOW keys, content filters) silently stops matching.
 */
class JsonCodecTest {

    @Test
    @DisplayName("field names are lowerCamelCase, matching the SOW key /orderId")
    void fieldNamesMatchSowKey() {
        String json = JsonCodec.toJson(Fixtures.order(1));

        assertTrue(json.contains("\"orderId\""),
                "SOW <Key>/orderId</Key> requires the JSON member to be orderId: " + json);
        assertFalse(json.contains("\"order_id\""),
                "preservingProtoFieldNames() must stay off or the SOW key stops resolving");
    }

    @Test
    @DisplayName("int32 stays a JSON number so AMPS filters can compare it numerically")
    void numericFieldsAreNotQuoted() {
        Order order = Fixtures.order(1).toBuilder().setQuantity(750).build();

        String json = JsonCodec.toJson(order);

        assertTrue(json.contains("\"quantity\":750"),
                "quantity must be an unquoted number for `/quantity > 500` to work: " + json);
    }

    @Test
    @DisplayName("default values are present, so filters and consumers see zeros")
    void defaultsArePrinted() {
        Order order = Fixtures.order(1).toBuilder().setFilledQuantity(0).build();

        String json = JsonCodec.toJson(order);

        assertTrue(json.contains("\"filledQuantity\":0"),
                "a full publish must carry every field, including defaults: " + json);
    }

    @Test
    @DisplayName("enums serialize by name, so filters read /status = 'ORDER_STATUS_FILLED'")
    void enumsSerializeByName() {
        Order order = Fixtures.order(1).toBuilder().setStatus(OrderStatus.ORDER_STATUS_FILLED).build();

        assertTrue(JsonCodec.toJson(order).contains("\"status\":\"ORDER_STATUS_FILLED\""));
    }

    @Test
    @DisplayName("round-trips through JSON without loss")
    void roundTrips() {
        Order original = Fixtures.order(7);

        Order parsed = JsonCodec.fromJson(JsonCodec.toJson(original), Order.getDefaultInstance());

        assertEquals(original, parsed);
    }

    @Test
    @DisplayName("unknown fields from a newer publisher do not break an older consumer")
    void unknownFieldsAreIgnored() {
        String fromNewerSchema =
                "{\"orderId\":\"ORD-00001\",\"symbol\":\"AAPL\",\"quantity\":100,"
                        + "\"someFutureField\":{\"nested\":true}}";

        Order parsed = JsonCodec.fromJson(fromNewerSchema, Order.getDefaultInstance());

        assertEquals("ORD-00001", parsed.getOrderId());
        assertEquals(100, parsed.getQuantity());
    }

    @Test
    @DisplayName("an instrument snapshot is dominated by static reference data")
    void snapshotIsMostlyStatic() {
        InstrumentSnapshot snapshot = Fixtures.instrument("AAPL");

        int whole = JsonCodec.byteSize(JsonCodec.toJson(snapshot));
        int quoteOnly = JsonCodec.byteSize(JsonCodec.toJson(snapshot.getQuote()));

        assertTrue(whole > 1000, "expected a ~1.5 KB record, got " + whole + " B");
        assertTrue(quoteOnly * 8 < whole,
                "the volatile part should be a small fraction of the record: "
                        + quoteOnly + " B of " + whole + " B");
    }

    @Test
    @DisplayName("Side enum survives the round trip")
    void sideRoundTrips() {
        Order sell = Fixtures.order(1).toBuilder().setSide(Side.SIDE_SELL).build();

        assertEquals(Side.SIDE_SELL,
                JsonCodec.fromJson(JsonCodec.toJson(sell), Order.getDefaultInstance()).getSide());
    }
}
