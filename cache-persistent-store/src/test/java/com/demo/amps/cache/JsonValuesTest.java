package com.demo.amps.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JSON round trip must return the value it was given -- same structure,
 * same types -- because "the recovered cache equals the original" is this
 * module's headline claim and {@code equals()} is how every test states it.
 */
class JsonValuesTest {

    /** Serialize as the store does, parse as hydration does. */
    private Object roundTrip(Object value) {
        String json = JsonValues.GSON.toJson(value);
        return JsonValues.GSON.fromJson(json, Object.class);
    }

    @Test
    @DisplayName("integral numbers come back as Long, not Double")
    void integersStayIntegral() {
        Object back = roundTrip(42L);
        assertInstanceOf(Long.class, back);
        assertEquals(42L, back);
    }

    @Test
    @DisplayName("fractional numbers come back as Double")
    void fractionsStayDouble() {
        assertEquals(3.5, roundTrip(3.5));
    }

    @Test
    @DisplayName("strings, booleans and lists survive unchanged")
    void scalarsAndLists() {
        assertEquals("plain", roundTrip("plain"));
        assertEquals(true, roundTrip(true));
        assertEquals(List.of("a", 1L, false), roundTrip(List.of("a", 1L, false)));
    }

    @Test
    @DisplayName("a nested map -- the Map<String, ?> value shape -- round-trips whole")
    void nestedMaps() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Ada");
        profile.put("level", 7L);
        profile.put("scores", List.of(10L, 9L));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profile", profile);
        value.put("active", true);

        assertEquals(value, roundTrip(value));
    }

    @Test
    @DisplayName("HTML-ish characters are not escaped into \\u sequences")
    void noHtmlEscaping() {
        assertEquals("\"a<b>c&d\"", JsonValues.GSON.toJson("a<b>c&d"));
    }
}
