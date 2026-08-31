package com.demo.amps.hazelcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code decode(encode(v)).equals(v)} -- the equality every recovery
 * guarantee reduces to -- for both the untyped default and a typed POJO
 * codec.
 */
class GsonValueCodecTest {

    @Test
    @DisplayName("untyped values round-trip with their types intact")
    void untypedRoundTrip() {
        ValueCodec<Object> codec = GsonValueCodec.untyped();

        Object number = codec.decode(codec.encode(42L));
        assertInstanceOf(Long.class, number, "integral values must not become 42.0");
        assertEquals(42L, number);

        Map<String, Object> value = Map.of(
                "name", "Ada",
                "level", 7L,
                "scores", List.of(10L, 9L),
                "active", true);
        assertEquals(value, codec.decode(codec.encode(value)));
    }

    @Test
    @DisplayName("a typed codec maps POJOs")
    void typedRoundTrip() {
        ValueCodec<Position> codec = new GsonValueCodec<>(Position.class);
        Position position = new Position("AAPL", 250, "187.50");

        assertEquals(position, codec.decode(codec.encode(position)));
    }

    /** A plain Gson-mappable value class, as an IMap value would be. */
    static final class Position {
        final String symbol;
        final long quantity;
        final String price;

        Position(String symbol, long quantity, String price) {
            this.symbol = symbol;
            this.quantity = quantity;
            this.price = price;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Position that
                    && quantity == that.quantity
                    && symbol.equals(that.symbol)
                    && price.equals(that.price);
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, quantity, price);
        }
    }
}
