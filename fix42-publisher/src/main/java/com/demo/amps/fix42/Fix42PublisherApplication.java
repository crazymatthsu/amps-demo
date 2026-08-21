package com.demo.amps.fix42;

import com.demo.amps.fix42.config.Fix42Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * FIX 4.2 delta publisher.
 *
 * <p>Publishes a scripted FIX 4.2 order flow -- parent orders, child slices,
 * amends, cancels, fills and rejects -- into the AMPS SOW topics declared by
 * the {@code fix42-chaining} server flow, sending whole messages only where a
 * record has to be created and field-level deltas everywhere else.
 *
 * <pre>
 *   ./server/scripts/amps.sh start          # with AMPS_FLOW=fix42-chaining
 *   ./gradlew :fix42-publisher:bootRun
 * </pre>
 *
 * <p>What it is demonstrating: with the chaining key generator resolving
 * 11/41 server-side, the publisher needs no chain state of its own. It reads
 * one message at a time, selects the configured tags, and lets AMPS decide
 * which record they belong to.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Fix42PublisherApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Fix42PublisherApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);

        // This publishes a finite flow and stops, so it has to say so
        // explicitly. The AMPS client runs non-daemon threads of its own, and
        // returning from main leaves them holding the JVM open -- the run looks
        // finished, logs "done", and never exits. SpringApplication.exit closes
        // the context first, which disconnects the client through the Client
        // bean's destroyMethod, so the shutdown is orderly rather than a
        // half-published process being killed.
        System.exit(SpringApplication.exit(application.run(args)));
    }

    /**
     * Fails the context start when the rulebook is unusable, rather than
     * letting the first message discover it.
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.beans.factory.InitializingBean validateFix42Properties(
            Fix42Properties properties) {
        return properties::validate;
    }
}
