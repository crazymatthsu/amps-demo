package com.demo.amps.qfj2.flow.rules;

import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.flow.EnrichmentRule;
import java.util.Objects;
import quickfix.Message;

/** Writes a constant into a body tag, overwriting whatever was there. */
public record SetTagRule(int tag, String value) implements EnrichmentRule {

    public SetTagRule {
        if (tag <= 0) {
            throw new IllegalArgumentException("tag must be positive: " + tag);
        }
        Objects.requireNonNull(value, "value");
    }

    @Override
    public void apply(Message message, FixContext context) {
        message.setString(tag, value);
    }

    @Override
    public String describe() {
        return "set-tag " + tag + "=" + value;
    }
}
