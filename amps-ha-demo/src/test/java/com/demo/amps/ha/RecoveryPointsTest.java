package com.demo.amps.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryPointsTest {

    @Test
    void parsesWhatTheAdapterWrites() {
        RecoveryPoints.Entry entry = RecoveryPoints.Entry.parse(
                "{\"clientName\":\"ha-demo-consumer\",\"subId\":\"ha-demo-consumer\",\"bookmark\":\"17434824616529380601|1200|\"}");
        assertEquals("ha-demo-consumer", entry.clientName());
        assertEquals("ha-demo-consumer", entry.subId());
        assertEquals("17434824616529380601|1200|", entry.bookmark());
        assertEquals(List.of("17434824616529380601|1200|"), entry.bookmarks());
    }

    @Test
    void aBookmarkListIsOnePositionPerPublisher() {
        RecoveryPoints.Entry entry = RecoveryPoints.Entry.parse(
                "{\"clientName\":\"c\",\"subId\":\"s\",\"bookmark\":\"17434824616529380601|12|,2966034254101942861|40|\"}");
        assertEquals(List.of("17434824616529380601|12|", "2966034254101942861|40|"), entry.bookmarks());
    }

    @Test
    void documentsThatAreNotRecoveryPointsAreRecognisedAsSuch() {
        assertNull(RecoveryPoints.Entry.parse("{\"id\":\"r1-7\",\"run\":\"r1\",\"seq\":7}"));
        assertNull(RecoveryPoints.Entry.parse("{\"clientName\":1,\"subId\":\"s\",\"bookmark\":\"b\"}"));
        assertNull(RecoveryPoints.Entry.parse("{\"clientName\":\"c\",\"subId\":\"s\"}"));
        assertNull(RecoveryPoints.Entry.parse("[1,2,3]"));
        assertNull(RecoveryPoints.Entry.parse("not json at all"));
    }
}
