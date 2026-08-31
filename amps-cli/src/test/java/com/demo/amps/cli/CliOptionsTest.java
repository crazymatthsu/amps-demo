package com.demo.amps.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CliOptionsTest {

    @Test
    void queryRequiresFilter() {
        CliArgs args = CliArgs.parse(new String[] {"--mode", "query", "--topic", "fix.native.orders"});
        CliOptions options = CliOptions.from(args);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, options::validate);
        assertTrue(error.getMessage().contains("--filter"));
    }

    @Test
    void acceptsSowAndSubscribeAlias() {
        CliArgs args = CliArgs.parse(new String[] {"--mode", "sow-and-subscribe"});
        assertEquals(CliOptions.Mode.SNAPSHOT_SUBSCRIBE, CliOptions.from(args).mode());
    }

    @Test
    void defaultsMatchRepoConnectionPattern() {
        CliOptions options = CliOptions.from(CliArgs.parse(new String[0]));
        options.validate();
        assertEquals("fix.native.orders", options.topic());
        assertEquals(CliOptions.Mode.SNAPSHOT, options.mode());
        assertEquals(CliOptions.OutputFormat.RAW, options.output());
        assertTrue(options.uri().endsWith("/amps/fix"), options.uri());
        assertEquals("amps-cli", options.clientName());
    }

    @Test
    void urlOverridesMessageTypePath() {
        CliOptions options = CliOptions.from(CliArgs.parse(new String[] {
                "--url", "tcp://example:9107/amps/nvfix",
                "--message-type", "fix"
        }));
        assertEquals("tcp://example:9107/amps/nvfix", options.uri());
    }
}
