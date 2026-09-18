package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.engine.FixContext;
import quickfix.Message;

/**
 * One step of the enrichment chain: mutates the message in place.
 *
 * <p>The shipped rules are configured by type in {@code qfj.flow.rules};
 * any Spring bean implementing this interface is appended after them, in
 * {@code @Order} order, which is how a deployment adds a rule that needs code.
 */
@FunctionalInterface
public interface EnrichmentRule {

    void apply(Message message, FixContext context);

    /** One line for the startup log. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
