package com.demo.amps.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArgsTest {

    @Test
    void parsesSeparatedValues() {
        Args args = Args.parse(new String[] {"--topic", "orders", "--count", "42"});

        assertEquals("orders", args.get("topic", "?"));
        assertEquals(42, args.getInt("count", 0));
    }

    @Test
    void parsesEqualsForm() {
        Args args = Args.parse(new String[] {"--filter=/quantity > 100"});

        assertEquals("/quantity > 100", args.get("filter", ""));
    }

    @Test
    void treatsBareFlagAsTrue() {
        Args args = Args.parse(new String[] {"--verbose", "--topic", "orders"});

        assertTrue(args.has("verbose"));
        assertTrue(args.getBoolean("verbose", false));
        assertEquals("orders", args.get("topic", "?"));
    }

    @Test
    void collectsPositionalArguments() {
        Args args = Args.parse(new String[] {"verify", "--count", "3", "extra"});

        assertEquals(List.of("verify", "extra"), args.positional());
        assertEquals("verify", args.positional(0, "snapshot"));
        assertEquals("fallback", args.positional(9, "fallback"));
    }

    @Test
    void fallsBackWhenAbsent() {
        Args args = Args.parse(new String[0]);

        assertEquals("default", args.get("missing", "default"));
        assertEquals(7, args.getInt("missing", 7));
        assertFalse(args.has("missing"));
    }
}
