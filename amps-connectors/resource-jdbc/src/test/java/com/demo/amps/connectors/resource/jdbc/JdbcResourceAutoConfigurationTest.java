package com.demo.amps.connectors.resource.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.ConnectorsAutoConfiguration;
import com.demo.amps.connectors.resource.ResourceRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The factory reaches the registry through the auto-configuration alone: a dependency on the
 * module and a {@code resources:} entry is all an application writes.
 */
class JdbcResourceAutoConfigurationTest {

    private static final String URL = "jdbc:h2:mem:resource-autoconfig;DB_CLOSE_DELAY=-1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConnectorsAutoConfiguration.class, JdbcResourceAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class OwnFactory {

        @Bean
        JdbcResourceFactory jdbcResourceFactory() {
            return new JdbcResourceFactory() {
                @Override
                public String toString() {
                    return "the application's own";
                }
            };
        }
    }

    @Test
    @DisplayName("the module contributes a JdbcResourceFactory, unless the application declares one")
    void contributesTheFactoryUnlessOverridden() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(JdbcResourceFactory.class);
            assertThat(context.getBean(ResourceRegistry.class).names()).isEmpty();
        });
        runner.withUserConfiguration(OwnFactory.class).run(context -> {
            assertThat(context).hasSingleBean(JdbcResourceFactory.class);
            assertThat(context.getBean(JdbcResourceFactory.class))
                    .hasToString("the application's own");
        });
    }

    @Test
    @DisplayName("a resources[].jdbc entry becomes a started, named lookup table in the registry")
    void buildsAndStartsTheConfiguredTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS instruments (\"symbol\" VARCHAR(16), "
                    + "\"sedol\" VARCHAR(7), \"currency\" VARCHAR(3))");
            statement.execute("DELETE FROM instruments");
            statement.execute("INSERT INTO instruments VALUES ('AAPL', '2046251', 'USD')");
        }
        runner.withPropertyValues(
                        "amps-connectors.resources[0].name=instruments",
                        "amps-connectors.resources[0].jdbc.url=" + URL,
                        "amps-connectors.resources[0].jdbc.query=SELECT * FROM instruments",
                        "amps-connectors.resources[0].jdbc.key-columns[0]=symbol",
                        "amps-connectors.resources[0].jdbc.reload-interval=0")
                .run(context -> {
                    ResourceRegistry registry = context.getBean(ResourceRegistry.class);
                    assertThat(registry.isRunning()).isTrue();
                    assertThat(registry.names()).containsExactly("instruments");
                    JdbcLookupTable table = registry.lookup("instruments", JdbcLookupTable.class);
                    assertThat(table.isAvailable()).isTrue();
                    assertThat(table.find("AAPL").orElseThrow()).containsEntry("sedol", "2046251");
                    assertThat(registry.status()).contains("instruments AVAILABLE rows=1");
                });
    }
}
