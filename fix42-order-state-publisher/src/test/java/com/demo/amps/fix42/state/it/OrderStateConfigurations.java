package com.demo.amps.fix42.state.it;

import com.demo.amps.fix42.state.config.OrderStateProperties;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds the module's own {@code application.yml} for the test suites, as
 * {@code fix42-publisher}'s {@code Fix42Configurations} does: a change to the
 * shipped configuration is exercised by the tests, rather than a copy that
 * drifts.
 */
public final class OrderStateConfigurations {

    private OrderStateConfigurations() {
    }

    /** The shipped configuration under {@code profile} (null for production's), with the URI overridden. */
    public static OrderStateProperties shipped(String uri, String profile) {
        return shipped(uri, profile, new String[0]);
    }

    /**
     * As {@link #shipped(String, String)}, with extra {@code key=value}
     * overrides under {@code fix42-order-state.}.
     */
    public static OrderStateProperties shipped(String uri, String profile, String... overrides) {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read application.yml from the classpath", e);
        }
        // The yml's documents in reverse so a later (profile) document wins,
        // as Spring orders them; a profile document applies only when asked for.
        for (int i = sources.size() - 1; i >= 0; i--) {
            PropertySource<?> source = sources.get(i);
            Object activation = source.getProperty("spring.config.activate.on-profile");
            if (activation == null || activation.toString().equals(profile)) {
                environment.getPropertySources().addLast(source);
            }
        }
        // Overrides in a source of their own, first -- never the JVM's system
        // properties, which would leak into every later test.
        java.util.Map<String, Object> explicit = new java.util.HashMap<>();
        explicit.put("fix42-order-state.amps.uri", uri);
        for (String override : overrides) {
            int eq = override.indexOf('=');
            explicit.put("fix42-order-state." + override.substring(0, eq), override.substring(eq + 1));
        }
        environment.getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("test-overrides", explicit));
        return Binder.get(environment)
                .bind("fix42-order-state", OrderStateProperties.class)
                .orElseThrow(() -> new IllegalStateException("could not bind fix42-order-state from application.yml"));
    }
}
