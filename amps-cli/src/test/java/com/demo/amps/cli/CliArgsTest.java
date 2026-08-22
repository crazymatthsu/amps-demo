package com.demo.amps.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CliArgsTest {

    @Test
    void parsesSeparatedValues() {
        CliArgs args = CliArgs.parse(new String[] {"--topic", "fix.native.orders", "--max", "42"});

        assertEquals("fix.native.orders", args.get("topic", "?"));
        assertEquals(42, args.getInt("max", 0));
    }

    @Test
    void parsesEqualsForm() {
        CliArgs args = CliArgs.parse(new String[] {"--filter=/39 = '2'"});

        assertEquals("/39 = '2'", args.get("filter", ""));
    }

    @Test
    void treatsBareFlagAsTrue() {
        CliArgs args = CliArgs.parse(new String[] {"--help", "--topic", "orders"});

        assertTrue(args.has("help"));
        assertEquals("orders", args.get("topic", "?"));
    }
}
