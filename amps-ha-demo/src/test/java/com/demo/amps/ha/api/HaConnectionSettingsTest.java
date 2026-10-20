package com.demo.amps.ha.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HaConnectionSettingsTest {

    @Test
    void defaultsSuitAPairOnOneNetwork() {
        HaConnectionSettings settings = HaConnectionSettings.defaults();
        assertEquals(Duration.ofSeconds(10), settings.logonTimeout());
        assertEquals(Duration.ofMillis(250), settings.reconnectDelay());
        assertEquals(Duration.ofSeconds(30), settings.firstConnectBudget());
        assertEquals(Duration.ofSeconds(1), settings.heartbeat());
        assertEquals(1, settings.heartbeatSeconds());
        assertEquals(10_000, settings.memoryStoreBlocks());
    }

    @Test
    void withersChangeOneFieldAndKeepTheRest() {
        HaConnectionSettings settings = HaConnectionSettings.defaults()
                .withLogonTimeout(Duration.ofSeconds(3))
                .withReconnectDelay(Duration.ofSeconds(1))
                .withFirstConnectBudget(Duration.ofMinutes(1))
                .withHeartbeat(Duration.ofSeconds(5))
                .withMemoryStoreBlocks(42);
        assertEquals(new HaConnectionSettings(Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofMinutes(1),
                Duration.ofSeconds(5), 42), settings);
        assertEquals(HaConnectionSettings.defaults().withHeartbeat(Duration.ZERO).memoryStoreBlocks(),
                HaConnectionSettings.DEFAULT_MEMORY_STORE_BLOCKS);
    }

    @Test
    void aZeroHeartbeatDisablesItAndSubSecondOnesRoundDownToDisabled() {
        assertEquals(0, HaConnectionSettings.defaults().withHeartbeat(Duration.ZERO).heartbeatSeconds());
        assertEquals(0, HaConnectionSettings.defaults().withHeartbeat(Duration.ofMillis(500)).heartbeatSeconds());
        assertEquals(2, HaConnectionSettings.defaults().withHeartbeat(Duration.ofMillis(2_900)).heartbeatSeconds());
    }

    @Test
    void nonPositiveTimingsAreRejected() {
        HaConnectionSettings defaults = HaConnectionSettings.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.withLogonTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> defaults.withReconnectDelay(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> defaults.withFirstConnectBudget(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> defaults.withHeartbeat(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMemoryStoreBlocks(0));
        assertThrows(NullPointerException.class, () -> defaults.withLogonTimeout(null));
    }
}
