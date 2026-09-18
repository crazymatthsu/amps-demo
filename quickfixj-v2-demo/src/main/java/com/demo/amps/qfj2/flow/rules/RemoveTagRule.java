package com.demo.amps.qfj2.flow.rules;

import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.flow.EnrichmentRule;
import quickfix.Message;

/** Removes a body tag, if present. */
public record RemoveTagRule(int tag) implements EnrichmentRule {

    public RemoveTagRule {
        if (tag <= 0) {
            throw new IllegalArgumentException("tag must be positive: " + tag);
        }
    }

    @Override
    public void apply(Message message, FixContext context) {
        message.removeField(tag);
    }

    @Override
    public String describe() {
        return "remove-tag " + tag;
    }
}
