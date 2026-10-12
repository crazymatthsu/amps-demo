package com.demo.amps.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrderRecordTest {

    @Test
    void keyIsRunAndSequence() {
        OrderRecord record = OrderRecord.of("r1", "pub", 42);
        assertEquals("r1-42", record.id());
        assertEquals("r1", record.run());
        assertEquals(42, record.seq());
        assertEquals("pub", record.publisher());
        assertTrue(record.qty() > 0);
    }

    @Test
    void roundTripsThroughJson() {
        OrderRecord record = OrderRecord.of("r1", "pub", 7);
        OrderRecord parsed = OrderRecord.parse(record.toJson());
        assertEquals(record, parsed);
    }

    @Test
    void consumerFastPathReadsRunAndSequence() {
        String json = OrderRecord.of("run-x", "pub", 12345).toJson();
        OrderRecord.RunAndSeq runAndSeq = OrderRecord.runAndSeqOf(json);
        assertEquals("run-x", runAndSeq.run());
        assertEquals(12345, runAndSeq.seq());
    }

    @Test
    void documentsThatAreNotOursAreRecognisedAsSuch() {
        assertNull(OrderRecord.runAndSeqOf("{\"id\":\"x\",\"price\":1.5}"));
        assertNull(OrderRecord.runAndSeqOf("[1,2,3]"));
        assertNull(OrderRecord.runAndSeqOf("{\"run\":{\"nested\":true},\"seq\":1}"));
    }
}
