package com.demo.amps.qfj2;

import com.demo.amps.qfj2.config.QfjProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * QuickFIX/J 2.x drop-copy engine on Spring Boot.
 *
 * <p>One jar, two roles, decided by which {@code config/<role>/<role>.yml}
 * is layered over the defaults:
 *
 * <pre>
 *   ./gradlew :quickfixj-v2-demo:bootRun -Prole=venue       # acceptor, invents execution reports
 *   ./gradlew :quickfixj-v2-demo:bootRun -Prole=dropcopy    # initiator -> rules -> AMPS
 * </pre>
 *
 * <p>and a third mode, the {@code seqno-admin} profile, which runs the
 * sequence-number tool instead of the engine and exits.
 *
 * <p>What it demonstrates: a QuickFIX/J {@code MessageStore} whose sequence
 * numbers are written to the usual file <em>and</em> replicated to AMPS by a
 * write-behind thread, so an instance starting on an empty disk recovers
 * them from AMPS and resumes the session where the last instance left it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class QuickfixjDropCopyApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(QuickfixjDropCopyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        // The engine runs until stopped (spring.main.keep-alive holds the JVM);
        // the admin tool has done its work by the time run() returns.
        if (context.getEnvironment().matchesProfiles("seqno-admin")) {
            System.exit(SpringApplication.exit(context));
        }
    }

    /** Fails the context start on an unusable configuration rather than the first message. */
    @Bean
    public InitializingBean validateQfjProperties(QfjProperties properties) {
        return properties::validate;
    }
}
