package com.demo.amps.connectors.transform;

import com.demo.amps.connectors.alert.Alerts;
import java.util.Objects;

/**
 * What a transform step needs beyond the field map, handed to step compilation.
 *
 * <p>The built-in steps are pure functions of the fields, and a {@link RecordTransform} bean
 * carries its own dependencies. A {@code rules} step is neither: it raises alerts, and an
 * alert without the connector's name is an alert nobody can act on. Rather than a second SPI
 * for "transforms that need things", the things are passed at compile time -- the connector
 * whose pipeline this is, the registry to resolve {@code bean:} actions against, and where to
 * raise -- and every step that does not care ignores them.
 *
 * @param connectorName the connector the steps belong to; {@code ""} where there is none
 * @param registry the application's transform beans, for {@code bean:} steps and actions
 * @param alerts where a step that has something to say says it
 */
public record TransformContext(String connectorName, TransformRegistry registry, Alerts alerts) {

    public TransformContext {
        Objects.requireNonNull(registry, "registry");
        connectorName = connectorName == null ? "" : connectorName;
        alerts = alerts == null ? Alerts.none() : alerts;
    }

    /**
     * A context with no connector and nowhere to raise -- for tests, tools and the
     * registry's own {@code resolve(steps)}, where the steps are compiled for their own sake.
     *
     * @param registry the application's transform beans
     * @return the context
     */
    public static TransformContext of(TransformRegistry registry) {
        return new TransformContext("", registry, Alerts.none());
    }
}
