package com.demo.amps.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArgsTest {

    @Test
    @DisplayName("--key value and --key=value are the same thing")
    void bothOptionForms() {
        assertEquals("orders", Args.parse(new String[] {"--topic", "orders"}).get("topic", null));
        assertEquals("orders", Args.parse(new String[] {"--topic=orders"}).get("topic", null));
    }

    @Test
    @DisplayName("a bare --flag is true")
    void bareFlag() {
        Args args = Args.parse(new String[] {"--yes"});
        assertTrue(args.has("yes"));
        assertEquals("true", args.get("yes", null));
    }

    @Test
    @DisplayName("a flag directly before another flag does not swallow it")
    void flagBeforeFlag() {
        Args args = Args.parse(new String[] {"--dry-run", "--topic", "orders"});
        assertTrue(args.has("dry-run"));
        assertEquals("orders", args.get("topic", null));
    }

    @Test
    @DisplayName("filter values keep their spaces and quotes")
    void filterValuesSurviveIntact() {
        Args args = Args.parse(new String[] {"--filter", "/quantity > 3000 AND /side = 'SIDE_BUY'"});
        assertEquals("/quantity > 3000 AND /side = 'SIDE_BUY'", args.get("filter", null));
    }

    @Test
    @DisplayName("a negative number is a value, not a flag")
    void negativeNumbersAreValues() {
        // Guards the "--key looks like the next token starts with --" rule.
        Args args = Args.parse(new String[] {"--max", "-1"});
        assertEquals("-1", args.get("max", null));
    }

    @Test
    @DisplayName("require names the missing option instead of defaulting")
    void requireFailsLoudly() {
        Args args = Args.parse(new String[0]);
        Args.UsageException e = assertThrows(Args.UsageException.class,
                () -> args.require("topic", "the topic to dump"));
        assertTrue(e.getMessage().contains("--topic"), e.getMessage());
        assertTrue(e.getMessage().contains("the topic to dump"), e.getMessage());
    }

    @Test
    @DisplayName("reject catches a misspelled option rather than ignoring it")
    void rejectCatchesTypos() {
        Args args = Args.parse(new String[] {"--topic", "orders", "--dryrun"});
        Args.UsageException e = assertThrows(Args.UsageException.class,
                () -> args.reject("topic", "filter", "yes"));
        assertTrue(e.getMessage().contains("--dryrun"), e.getMessage());
    }

    @Test
    @DisplayName("reject passes when every option is known")
    void rejectAllowsKnownOptions() {
        Args.parse(new String[] {"--topic", "orders", "--yes"}).reject("topic", "yes");
    }

    @Test
    @DisplayName("a non-numeric value for a numeric option is a usage error, not a crash")
    void numericValidation() {
        Args args = Args.parse(new String[] {"--max", "lots"});
        assertThrows(Args.UsageException.class, () -> args.getInt("max", 0));
    }

    @Test
    @DisplayName("comma lists are split and trimmed, and empties dropped")
    void commaLists() {
        Args args = Args.parse(new String[] {"--topic", " orders , instruments ,,"});
        assertEquals(List.of("orders", "instruments"), args.list("topic"));
        assertEquals(List.of(), Args.parse(new String[0]).list("topic"));
    }

    @Test
    @DisplayName("an empty value falls back rather than passing an empty string on")
    void emptyValuesFallBack() {
        Args args = Args.parse(new String[] {"--filter="});
        assertEquals("fallback", args.get("filter", "fallback"));
        assertFalse(args.get("filter", "fallback").isEmpty());
    }
}
