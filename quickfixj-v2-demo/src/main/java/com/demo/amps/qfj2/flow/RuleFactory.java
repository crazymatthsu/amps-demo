package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.flow.rules.CopyTagRule;
import com.demo.amps.qfj2.flow.rules.MsgTypeFilteredRule;
import com.demo.amps.qfj2.flow.rules.ReceivedTimeRule;
import com.demo.amps.qfj2.flow.rules.RemoveTagRule;
import com.demo.amps.qfj2.flow.rules.SetTagRule;
import com.demo.amps.qfj2.flow.rules.SourceSessionRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns {@link RuleSpec}s into rules, refusing a spec that would fail later:
 * an unknown type, or a type missing the parameter it needs. Called at
 * startup validation as well as at wiring, so a typo stops the boot.
 */
public final class RuleFactory {

    public static final Set<String> TYPES =
            Set.of("set-tag", "copy-tag", "remove-tag", "source-session", "received-time");

    private RuleFactory() {
    }

    public static List<EnrichmentRule> createAll(List<RuleSpec> specs) {
        List<EnrichmentRule> rules = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            try {
                rules.add(create(specs.get(i)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("qfj.flow.rules[" + i + "]: " + e.getMessage(), e);
            }
        }
        return rules;
    }

    public static EnrichmentRule create(RuleSpec spec) {
        if (spec.type() == null || spec.type().isBlank()) {
            throw new IllegalArgumentException("a rule needs a type; one of " + TYPES);
        }
        String type = spec.type().trim().toLowerCase(Locale.ROOT).replace('_', '-');
        EnrichmentRule rule = switch (type) {
            case "set-tag" -> new SetTagRule(tag(spec, "tag", spec.tag()), required(spec, "value", spec.value()));
            case "copy-tag" -> new CopyTagRule(tag(spec, "from", spec.from()), tag(spec, "to", spec.to()));
            case "remove-tag" -> new RemoveTagRule(tag(spec, "tag", spec.tag()));
            case "source-session" -> new SourceSessionRule(tag(spec, "tag", spec.tag()));
            case "received-time" -> new ReceivedTimeRule(tag(spec, "tag", spec.tag()));
            default -> throw new IllegalArgumentException("unknown rule type '" + spec.type() + "'; one of " + TYPES);
        };
        if (spec.msgTypes() == null || spec.msgTypes().isEmpty()) {
            return rule;
        }
        return new MsgTypeFilteredRule(Set.copyOf(spec.msgTypes()), rule);
    }

    private static int tag(RuleSpec spec, String name, Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("rule '" + spec.type() + "' needs '" + name + "' (a tag number)");
        }
        if (value <= 0) {
            throw new IllegalArgumentException("rule '" + spec.type() + "': '" + name + "' must be a positive tag "
                    + "number, got " + value);
        }
        return value;
    }

    private static String required(RuleSpec spec, String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException("rule '" + spec.type() + "' needs '" + name + "'");
        }
        return value;
    }
}
