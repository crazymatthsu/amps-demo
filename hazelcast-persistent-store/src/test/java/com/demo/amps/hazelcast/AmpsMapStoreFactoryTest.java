package com.demo.amps.hazelcast;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The factory is the configuration edge, so its job is to fail at
 * configuration time with an error a person can act on -- not at first map
 * use with a stack trace from the middle of a partition thread.
 */
class AmpsMapStoreFactoryTest {

    private final AmpsMapStoreFactory factory = new AmpsMapStoreFactory();

    @Test
    @DisplayName("a map without amps.topic is rejected immediately, by name")
    void missingTopicFailsFast() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.newMapStore("orders", new Properties()));

        assertTrue(error.getMessage().contains("orders"));
        assertTrue(error.getMessage().contains("amps.topic"));
    }

    @Test
    @DisplayName("a configured map gets an adapter (not yet connected -- init does that)")
    void configuredMapGetsAdapter() {
        Properties properties = new Properties();
        properties.setProperty("amps.topic", HazelcastTiers.PERSISTENT);

        assertInstanceOf(AmpsHazelcastMapStore.class,
                factory.newMapStore("orders", properties));
    }
}
