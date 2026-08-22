package com.demo.amps.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AmpsCliTest {

    @Test
    void helpExitsZero() {
        assertEquals(0, AmpsCli.run(new String[] {"--help"}));
    }

    @Test
    void invalidModeExitsTwo() {
        assertEquals(2, AmpsCli.run(new String[] {"--mode", "nope"}));
    }

    @Test
    void queryWithoutFilterExitsTwo() {
        assertEquals(2, AmpsCli.run(new String[] {"--mode", "query"}));
    }
}
