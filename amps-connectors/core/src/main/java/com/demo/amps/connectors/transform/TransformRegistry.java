package com.demo.amps.connectors.transform;

import com.demo.amps.connectors.config.TransformStep;
import com.demo.amps.connectors.transform.rules.RuleSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link RecordTransform} beans an application registered, by bean name, and the place a
 * connector's {@code transforms:} list is turned into the steps that run.
 *
 * <p>A class rather than an injected {@code Map<String, RecordTransform>} so that the normal
 * case -- an application with no custom transforms at all -- is an empty registry rather than
 * a missing-bean failure at startup.
 *
 * <p>Resolution of a {@code bean:} step is deliberately strict: a connector naming a transform
 * that is not registered is a typo or a missing dependency, and running without the enrichment
 * would publish a topic that looks fine and is wrong. The message lists what <em>is</em>
 * registered, because the answer is almost always visible in that list.
 */
public class TransformRegistry {

    private final Map<String, RecordTransform> transforms;

    /**
     * @param transforms the application's transform beans, by bean name
     */
    public TransformRegistry(Map<String, RecordTransform> transforms) {
        this.transforms = new LinkedHashMap<>(transforms);
    }

    /** The registered names, for error messages and for the validator. */
    public Set<String> names() {
        return transforms.keySet();
    }

    /** Whether a name is registered. Used by the validator without resolving anything. */
    public boolean contains(String name) {
        return transforms.containsKey(name);
    }

    /**
     * The bean of that name -- what a {@code bean:} step and a rule's {@code bean} action
     * both resolve through.
     *
     * @param name the bean name
     * @return the transform
     * @throws IllegalStateException naming the bean and what is registered, if it is not
     */
    public RecordTransform require(String name) {
        RecordTransform bean = transforms.get(name);
        if (bean == null) {
            throw new IllegalStateException("no RecordTransform bean named '"
                    + name + "'; registered: " + transforms.keySet());
        }
        return bean;
    }

    /**
     * Turn a connector's steps into the transforms to fold over each record, in order, with
     * no connector behind them: what a test, a tool, or the older overload wants.
     *
     * @param steps the connector's {@code transforms:} list
     * @return one transform per step
     * @throws IllegalStateException naming the first unknown bean and what is registered
     * @throws IllegalArgumentException if a step does not name exactly one kind, or a rule is
     *     malformed
     */
    public List<RecordTransform> resolve(List<TransformStep> steps) {
        return resolve(steps, TransformContext.of(this));
    }

    /**
     * Turn a connector's steps into the transforms to fold over each record, in order.
     *
     * <p>A {@code bean:} step resolves to the bean of that name; a {@code rules:} step is
     * compiled by {@link RuleSet#compile} with the context, because its alerts need the
     * connector's name; every other kind is compiled by {@link TransformChain#compile}. All
     * of them end up as the same kind of thing, and the chain does not need to know which
     * was which.
     *
     * @param steps the connector's {@code transforms:} list
     * @param context the connector the steps belong to, and where its rules raise
     * @return one transform per step
     * @throws IllegalStateException naming the first unknown bean and what is registered
     * @throws IllegalArgumentException if a step does not name exactly one kind, or a rule is
     *     malformed
     */
    public List<RecordTransform> resolve(List<TransformStep> steps, TransformContext context) {
        List<RecordTransform> resolved = new ArrayList<>(steps.size());
        for (TransformStep step : steps) {
            resolved.add(switch (TransformChain.kindOf(step)) {
                case "bean" -> require(step.getBean());
                case "rules" -> RuleSet.compile(step.getRules(), context);
                default -> TransformChain.compile(step);
            });
        }
        return List.copyOf(resolved);
    }
}
