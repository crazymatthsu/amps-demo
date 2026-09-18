package com.demo.amps.qfj2.flow;

import java.util.List;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One entry of {@code qfj.flow.rules}: a type and the parameters that type
 * needs. Which parameters apply is decided by {@link RuleFactory}; the rest
 * stay null.
 *
 * @param type     set-tag, copy-tag, remove-tag, source-session, received-time
 * @param tag      the tag written or removed (set-tag, remove-tag, source-session, received-time)
 * @param from     the tag read (copy-tag)
 * @param to       the tag written (copy-tag)
 * @param value    the value written (set-tag)
 * @param msgTypes if given, the rule applies only to these 35 values
 */
public record RuleSpec(
        String type,
        Integer tag,
        Integer from,
        Integer to,
        String value,
        @DefaultValue List<String> msgTypes) {
}
