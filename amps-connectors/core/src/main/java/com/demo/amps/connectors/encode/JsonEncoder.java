package com.demo.amps.connectors.encode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Writes the field map as a JSON object.
 *
 * <p>Values are written in their own types, not quoted: a {@code Long} is a JSON number, a
 * {@code Boolean} a JSON boolean, a nested {@code Map} a nested object, a {@code List} an
 * array, and a null value an explicit {@code null}. That matters on the far side, because an
 * AMPS filter or a {@code <Key>} on a numeric field compares numbers, and a price published as
 * {@code "185.50"} would never match {@code /price > 100}.
 *
 * <p>A connector that reads FIX and publishes JSON therefore produces strings, since FIX
 * carries no types -- a {@code derive} transform is how a field becomes a number on the way.
 */
public final class JsonEncoder implements PayloadEncoder {

    private final ObjectMapper mapper;

    /**
     * @param mapper the mapper to serialise with; shared, never reconfigured here
     */
    public JsonEncoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String encode(Map<String, Object> fields) {
        try {
            return mapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            // Only reachable when a bean transform put something jackson cannot write; a
            // decoded map is Strings, numbers, booleans, maps and lists all the way down.
            throw new IllegalArgumentException(
                    "fields could not be written as JSON: " + e.getOriginalMessage(), e);
        }
    }
}
