package com.demo.amps.qfj2.flow.rules;

import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.flow.EnrichmentRule;
import quickfix.FieldNotFound;
import quickfix.Message;

/**
 * Copies one tag's value into another body tag. The source is looked for in
 * the body first, then the header -- so {@code from: 49} keeps the original
 * SenderCompID as a body field, which survives the header being rewritten
 * when the message is forwarded to another session. A missing source leaves
 * the message untouched.
 */
public record CopyTagRule(int from, int to) implements EnrichmentRule {

    public CopyTagRule {
        if (from <= 0 || to <= 0) {
            throw new IllegalArgumentException("tags must be positive: from " + from + " to " + to);
        }
    }

    @Override
    public void apply(Message message, FixContext context) {
        String value;
        try {
            if (message.isSetField(from)) {
                value = message.getString(from);
            } else if (message.getHeader().isSetField(from)) {
                value = message.getHeader().getString(from);
            } else {
                return;
            }
        } catch (FieldNotFound e) {
            return;
        }
        message.setString(to, value);
    }

    @Override
    public String describe() {
        return "copy-tag " + from + " -> " + to;
    }
}
