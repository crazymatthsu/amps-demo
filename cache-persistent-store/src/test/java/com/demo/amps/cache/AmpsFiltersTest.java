package com.demo.amps.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Filter-literal quoting: chosen per value, because the AMPS expression
 * language has no escape for an embedded delimiter. The one impossible case
 * -- both quote characters in one key -- must be rejected before the key is
 * ever stored, which is what keeps every stored key loadable and deletable.
 */
class AmpsFiltersTest {

    @Test
    @DisplayName("plain values take single quotes")
    void plainValues() {
        assertEquals("/key = 'user-42'", AmpsFilters.equalTo("key", "user-42"));
        assertEquals("/outerKey = 'aaa bbb'", AmpsFilters.equalTo("outerKey", "aaa bbb"));
    }

    @Test
    @DisplayName("a value with an apostrophe switches to double quotes")
    void apostrophes() {
        assertEquals("/key = \"o'brien\"", AmpsFilters.equalTo("key", "o'brien"));
    }

    @Test
    @DisplayName("a value with double quotes keeps single quotes")
    void doubleQuotes() {
        assertEquals("/key = 'say \"hi\"'", AmpsFilters.equalTo("key", "say \"hi\""));
    }

    @Test
    @DisplayName("a value with both quote styles is rejected, with the key named")
    void bothQuoteStyles() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> AmpsFilters.checkKey("both ' and \""));
        assertEquals(true, rejected.getMessage().contains("both ' and \""));
    }

    @Test
    @DisplayName("checkKey passes through anything expressible")
    void checkKeyPassesThrough() {
        assertEquals("o'brien", AmpsFilters.checkKey("o'brien"));
        assertEquals("útf-8 ключ", AmpsFilters.checkKey("útf-8 ключ"));
        assertThrows(NullPointerException.class, () -> AmpsFilters.checkKey(null));
    }
}
