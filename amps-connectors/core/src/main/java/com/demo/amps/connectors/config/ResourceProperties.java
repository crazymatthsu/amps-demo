package com.demo.amps.connectors.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One configuration-defined application resource: a named, shared, lifecycle-managed object
 * that transforms look things up in.
 *
 * <pre>{@code
 * amps-connectors:
 *   resources:
 *     - name: instruments
 *       jdbc:
 *         url: "jdbc:postgresql://${JDBC_HOST:localhost}:5432/refdata"
 *         query: SELECT symbol, sedol, currency FROM instruments
 *         key-columns: [symbol]
 * }</pre>
 *
 * <p>The kind blocks are siblings and exactly one of them is configured (the validator
 * enforces it): the block that is present is what picks the {@code ResourceFactory} that
 * builds the resource, the same way a connector's {@code source:} block picks its driver.
 * Only {@code jdbc} exists today; a KDB client or a gRPC stub with no generic configuration
 * is an {@code AppResource} <em>bean</em> in the application instead, and needs no entry
 * here at all -- every such bean registers itself under its own name.
 *
 * <p>The name is the handle everything else uses: {@code ResourceRegistry.lookup(name, type)}
 * from a transform, {@code "target": "<name>"} in a reload command, and the first word of the
 * resource's status line. It is unique across the beans and this list.
 */
public class ResourceProperties {

    /** The resource's name; what transforms and commands address it by. Unique. */
    @NotBlank
    private String name;

    /** Set {@code false} to keep a resource configured but neither built nor started. */
    private boolean enabled = true;

    /** A reloadable lookup table read from a database; non-null selects the JDBC resource. */
    @Valid
    private JdbcResourceProperties jdbc;

    /**
     * The kinds this entry configures.
     *
     * <p>The validator's whole job on an entry: none is a resource nothing can build, several
     * is an entry that names two different things under one name.
     *
     * @return the configured kind names, e.g. {@code ["jdbc"]}
     */
    public Set<String> configuredKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        if (jdbc != null) {
            kinds.add("jdbc");
        }
        return kinds;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public JdbcResourceProperties getJdbc() {
        return jdbc;
    }

    public void setJdbc(JdbcResourceProperties jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String toString() {
        return "resource '" + name + "' (" + String.join("/", configuredKinds()) + ")";
    }
}
