package com.demo.amps.connectors.transform;

import com.demo.amps.connectors.config.TransformStep;
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
     * Turn a connector's steps into the transforms to fold over each record, in order.
     *
     * <p>A {@code bean:} step resolves to the bean of that name; every other kind is compiled
     * by {@link TransformChain#compile}, so the built-ins and the custom ones end up as the
     * same kind of thing and the chain does not need to know which was which.
     *
     * @param steps the connector's {@code transforms:} list
     * @return one transform per step
     * @throws IllegalStateException naming the first unknown bean and what is registered
     * @throws IllegalArgumentException if a step does not name exactly one kind
     */
    public List<RecordTransform> resolve(List<TransformStep> steps) {
        List<RecordTransform> resolved = new ArrayList<>(steps.size());
        for (TransformStep step : steps) {
            if (step.getBean() != null) {
                RecordTransform bean = transforms.get(step.getBean());
                if (bean == null) {
                    throw new IllegalStateException("no RecordTransform bean named '"
                            + step.getBean() + "'; registered: " + transforms.keySet());
                }
                resolved.add(bean);
            } else {
                resolved.add(TransformChain.compile(step));
            }
        }
        return List.copyOf(resolved);
    }
}
