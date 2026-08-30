package com.demo.amps.hazelcast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure logic under the bulk load/delete paths: filter text and chunking.
 * The server-facing halves are exercised by the integration suite; these are
 * the parts a filter typo would silently corrupt.
 */
class AmpsTierStoreFilterTest {

    @Test
    @DisplayName("a key chunk becomes /map AND (OR of /key equalities)")
    void keysFilterShape() {
        assertEquals("/map = 'orders' AND (/key = 'a')",
                AmpsTierStore.keysFilter("orders", List.of("a")));
        assertEquals("/map = 'orders' AND (/key = 'a' OR /key = 'b' OR /key = 'c')",
                AmpsTierStore.keysFilter("orders", List.of("a", "b", "c")));
    }

    @Test
    @DisplayName("quote-bearing keys pick their literal style inside the OR chain")
    void quotedKeys() {
        assertEquals("/map = 'orders' AND (/key = \"o'brien\" OR /key = 'say \"hi\"')",
                AmpsTierStore.keysFilter("orders", List.of("o'brien", "say \"hi\"")));
    }

    @Test
    @DisplayName("chunking covers every element exactly once, in order")
    void chunking() {
        assertEquals(List.of(), AmpsTierStore.chunks(List.of(), 32));
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d"), List.of("e")),
                AmpsTierStore.chunks(List.of("a", "b", "c", "d", "e"), 2));
        assertEquals(List.of(List.of("a", "b", "c")),
                AmpsTierStore.chunks(List.of("a", "b", "c"), 32));
    }
}
