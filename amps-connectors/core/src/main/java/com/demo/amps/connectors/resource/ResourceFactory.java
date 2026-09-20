package com.demo.amps.connectors.resource;

import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.config.ResourceProperties;

/**
 * Builds the {@link AppResource} for one kind of {@code resources:} entry.
 *
 * <p>The extension point a resource module contributes, shaped like {@code SourceFactory} for
 * the same reason: core holds no list of kinds, so {@code :amps-connectors:resource-jdbc}
 * registers a factory as a bean, the auto-configuration asks each factory in turn which
 * entries it recognises, and an application carries only the modules it depends on. An entry
 * nobody claims is a build problem -- the block is spelled correctly, its module is just not
 * on the classpath -- and the message says which one to add.
 *
 * <p>{@link #supports} answers from the configuration alone (in practice: "is my block under
 * the entry non-null"), so the wrong kind is never constructed just to be asked.
 */
public interface ResourceFactory {

    /**
     * Whether this factory is the one that builds {@code resource}.
     *
     * @param resource the entry
     * @return {@code true} if {@link #create} can build it
     */
    boolean supports(ResourceProperties resource);

    /**
     * @param resource an entry this factory {@link #supports}
     * @param alerts where the resource raises what goes wrong -- a load that failed, a
     *     reload that kept the old copy
     * @return a fresh, unstarted resource named {@code resource.getName()}
     */
    AppResource create(ResourceProperties resource, Alerts alerts);
}
