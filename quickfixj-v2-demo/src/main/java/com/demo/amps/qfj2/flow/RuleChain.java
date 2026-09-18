package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.engine.FixContext;
import java.util.List;
import java.util.stream.Collectors;
import quickfix.Message;

/** The configured rules, applied in order. */
public final class RuleChain {

    private final List<EnrichmentRule> rules;

    public RuleChain(List<EnrichmentRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public void apply(Message message, FixContext context) {
        for (EnrichmentRule rule : rules) {
            rule.apply(message, context);
        }
    }

    public List<EnrichmentRule> rules() {
        return rules;
    }

    public String describe() {
        return rules.isEmpty()
                ? "(no rules)"
                : rules.stream().map(EnrichmentRule::describe).collect(Collectors.joining("; "));
    }
}
