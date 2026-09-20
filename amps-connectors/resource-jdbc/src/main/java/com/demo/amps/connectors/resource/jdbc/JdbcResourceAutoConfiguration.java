package com.demo.amps.connectors.resource.jdbc;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Contributes the JDBC resource to any application that depends on this module.
 *
 * <p>An application gets a reloadable lookup table by adding the dependency and an entry
 * under {@code amps-connectors.resources} with a {@code jdbc} block: the factory joins the
 * list the {@code ResourceRegistry} is built from, and the entry selects it. Unconditional,
 * like the JDBC source driver: the client this resource compiles against is {@code java.sql},
 * which is always there, and a missing <em>database</em> driver is a load failure with a
 * readable message that the resource reports and retries, not a reason to withhold the
 * factory.
 */
@AutoConfiguration
public class JdbcResourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JdbcResourceFactory jdbcResourceFactory() {
        return new JdbcResourceFactory();
    }
}
