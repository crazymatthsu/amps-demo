package com.demo.amps.connectors.control;

import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.resource.ResourceRegistry;
import com.demo.amps.connectors.runtime.ConnectorManager;
import java.util.Objects;

/**
 * What a {@link CommandHandler} is given to work with.
 *
 * <p>A record rather than four constructor arguments per handler, so that a handler is a
 * two-method class and adding something handlers can reach -- a new registry, a clock --
 * changes this type and no handler. The four are the application's whole surface as far as
 * a command is concerned: its name (what {@code to} is compared with), its resources (what
 * {@code reload} reloads), its connectors (what {@code status} reports on), and where to say
 * what happened.
 *
 * @param application the application's name, as the alerts carry it
 * @param resources the shared resources, by name
 * @param connectors the running connectors; may be {@code null} where there are none
 * @param alerts where a handler reports; never {@code null}
 */
public record CommandContext(
        String application,
        ResourceRegistry resources,
        ConnectorManager connectors,
        Alerts alerts) {

    public CommandContext {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(resources, "resources");
        alerts = alerts == null ? Alerts.none() : alerts;
    }
}
