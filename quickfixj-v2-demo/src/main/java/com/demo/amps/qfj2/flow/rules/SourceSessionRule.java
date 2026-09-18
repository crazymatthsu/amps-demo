package com.demo.amps.qfj2.flow.rules;

import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.flow.EnrichmentRule;
import quickfix.Message;

/** Writes the id of the session the message arrived on, e.g. {@code FIX.4.2:DROPCOPY->VENUE}. */
public record SourceSessionRule(int tag) implements EnrichmentRule {

    public SourceSessionRule {
        if (tag <= 0) {
            throw new IllegalArgumentException("tag must be positive: " + tag);
        }
    }

    @Override
    public void apply(Message message, FixContext context) {
        message.setString(tag, context.sessionId().toString());
    }

    @Override
    public String describe() {
        return "source-session -> " + tag;
    }
}
