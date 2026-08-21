package com.demo.amps.fix42.it;

import com.demo.amps.fix42.config.Fix42Properties;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads the module's own {@code application.yml} for the integration suites.
 *
 * <p>Binding the shipped file rather than restating the rules is what keeps
 * these tests honest: change a route and this exercises the change, instead of
 * asserting against a copy that quietly drifts out of date.
 */
final class Fix42Configurations {

    private Fix42Configurations() {
    }

    /** The shipped rulebook, with only the AMPS URI overridden. */
    static Fix42Properties shipped(String uri) {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read application.yml from the classpath", e);
        }
        sources.forEach(source -> environment.getPropertySources().addLast(source));
        environment.getSystemProperties().put("fix42.amps.uri", uri);

        return Binder.get(environment)
                .bind("fix42", Fix42Properties.class)
                .orElseThrow(() -> new IllegalStateException(
                        "could not bind fix42 properties from application.yml"));
    }
}
