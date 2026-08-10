package com.demo.amps.clients;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crankuptheamps.client.Message;
import org.junit.jupiter.api.Test;

/**
 * Cheap guards on the parts of the client module that do not need a server.
 * Everything else in {@code clients} is inherently an integration test and lives
 * behind a running AMPS instance.
 */
class DemoRegistryTest {

    @Test
    void commandNamesCoverWhatTheDemosPrint() {
        assertTrue(Messages.commandName(Message.Command.SOW).equals("sow"));
        assertTrue(Messages.commandName(Message.Command.OOF).equals("OOF"));
        assertTrue(Messages.commandName(Message.Command.SOWAndSubscribe)
                .equals("sow_and_subscribe"));
        assertTrue(Messages.commandName(Message.Command.DeltaPublish).equals("delta_publish"));
        // Anything unmapped should still render, not throw.
        assertTrue(Messages.commandName(-1).startsWith("command("));
    }

    @Test
    void reasonNamesExplainOofMessages() {
        assertTrue(Messages.reasonName(Message.Reason.Expired).equals("expired"));
        assertTrue(Messages.reasonName(Message.Reason.Deleted).equals("deleted"));
        assertTrue(Messages.reasonName(Message.Reason.Match).contains("filter"));
    }

    @Test
    void truncationKeepsOutputOnOneLine() {
        String long_ = "x".repeat(500);

        String truncated = Messages.truncate(long_, 40);

        assertTrue(truncated.length() <= 40);
        assertTrue(truncated.endsWith("..."));
        assertFalse(Messages.truncate("a\nb", 10).contains("\n"));
    }
}
