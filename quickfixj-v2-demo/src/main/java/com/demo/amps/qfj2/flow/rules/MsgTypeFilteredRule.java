package com.demo.amps.qfj2.flow.rules;

import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.engine.FixMessages;
import com.demo.amps.qfj2.flow.EnrichmentRule;
import java.util.Set;
import quickfix.Message;

/** Applies the wrapped rule only to the listed message types. */
public record MsgTypeFilteredRule(Set<String> msgTypes, EnrichmentRule rule) implements EnrichmentRule {

    @Override
    public void apply(Message message, FixContext context) {
        if (msgTypes.contains(FixMessages.msgType(message))) {
            rule.apply(message, context);
        }
    }

    @Override
    public String describe() {
        return rule.describe() + " for 35 in " + msgTypes;
    }
}
