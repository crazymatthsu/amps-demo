package com.demo.amps.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayloadsTest {

    @Test
    @DisplayName("an unknown --format is refused, naming the two that exist")
    void formatValidation() {
        assertEquals(Payloads.LINES, Payloads.requireKnownFormat("lines"));
        assertEquals(Payloads.JSONL, Payloads.requireKnownFormat("jsonl"));
        Args.UsageException e = assertThrows(Args.UsageException.class,
                () -> Payloads.requireKnownFormat("csv"));
        assertEquals(true, e.getMessage().contains("lines"));
        assertEquals(true, e.getMessage().contains("jsonl"));
    }

    @Test
    @DisplayName("in lines format a line is the payload, untouched")
    void linesArePayloads() {
        String json = "{\"orderId\":\"ORD-1\",\"quantity\":100}";
        assertEquals(json, Payloads.toPayload(json, Payloads.LINES));
        // FIX payloads are SOH-separated and must survive byte for byte.
        String fix = "35=811=A139=2";
        assertEquals(fix, Payloads.toPayload(fix, Payloads.LINES));
    }

    @Test
    @DisplayName("lines format carries no topic, so the caller must supply one")
    void linesHaveNoTopic() {
        assertNull(Payloads.topicOf("{\"orderId\":\"ORD-1\"}", Payloads.LINES));
    }

    @Test
    @DisplayName("a jsonl envelope yields back its data and topic")
    void jsonlEnvelopeRoundTrip() {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("topic", "orders");
        envelope.addProperty("bookmark", "123|456|");
        envelope.addProperty("data", "{\"orderId\":\"ORD-1\"}");

        String line = envelope.toString();
        assertEquals("{\"orderId\":\"ORD-1\"}", Payloads.toPayload(line, Payloads.JSONL));
        assertEquals("orders", Payloads.topicOf(line, Payloads.JSONL));
    }

    @Test
    @DisplayName("jsonl survives the payloads that break the lines format")
    void jsonlIsLossless() {
        // The whole reason jsonl exists: embedded newlines, quotes and SOH all
        // come back exactly as they went in.
        String awkward = "line one\nline two\r\n\"quoted\"SOH\ttab";
        JsonObject envelope = new JsonObject();
        envelope.addProperty("data", awkward);
        assertEquals(awkward, Payloads.toPayload(envelope.toString(), Payloads.JSONL));
    }

    @Test
    @DisplayName("a jsonl record without a data member is rejected, not silently empty")
    void jsonlRequiresData() {
        assertThrows(IllegalArgumentException.class,
                () -> Payloads.toPayload("{\"topic\":\"orders\"}", Payloads.JSONL));
    }

    @Test
    @DisplayName("a jsonl envelope with no topic falls back to the caller's --topic")
    void jsonlTopicOptional() {
        assertNull(Payloads.topicOf("{\"data\":\"{}\"}", Payloads.JSONL));
    }
}
