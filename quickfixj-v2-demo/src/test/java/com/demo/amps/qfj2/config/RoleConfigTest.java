package com.demo.amps.qfj2.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.demo.amps.qfj2.flow.DestinationType;
import com.demo.amps.qfj2.seqno.RecoveryPolicy;
import com.demo.amps.qfj2.support.TestPaths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

/**
 * The shipped configuration files, bound and validated for real: the
 * defaults in the jar with each role's file layered over them, exactly as
 * {@code bootRun -Prole=...} and the compose services do it.
 */
class RoleConfigTest {

    private static QfjProperties bind(String role) {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            List<PropertySource<?>> roleSources = loader.load("role",
                    new FileSystemResource(TestPaths.moduleDir().resolve("config/" + role + "/" + role + ".yml")));
            List<PropertySource<?>> defaults = loader.load("defaults", new ClassPathResource("application.yml"));
            // The role file wins: added first, so it is searched first.
            roleSources.forEach(source -> environment.getPropertySources().addLast(source));
            defaults.forEach(source -> environment.getPropertySources().addLast(source));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the configuration for role " + role, e);
        }
        return Binder.get(environment).bind("qfj", QfjProperties.class)
                .orElseThrow(() -> new IllegalStateException("could not bind qfj.* for role " + role));
    }

    @Test
    @DisplayName("the dropcopy role: initiator settings, four rules, two AMPS destinations, replication on")
    void dropcopyRoleBindsAndValidates() {
        QfjProperties properties = bind("dropcopy");
        assertThatCode(properties::validate).doesNotThrowAnyException();

        assertThat(properties.role()).isEqualTo("dropcopy");
        assertThat(properties.engine().settings()).isEqualTo("config/dropcopy/quickfixj.cfg");
        assertThat(properties.engine().autoStart()).isTrue();
        assertThat(properties.amps().uri()).isEqualTo("tcp://127.0.0.1:9007/amps/fix");
        assertThat(properties.amps().clientName()).isEqualTo("qfj2-dropcopy-fix");
        assertThat(properties.seqno().enabled()).isTrue();
        assertThat(properties.seqno().uri()).isEqualTo("tcp://127.0.0.1:9007/amps/json");
        assertThat(properties.seqno().recovery()).isEqualTo(RecoveryPolicy.AMPS_WINS);
        assertThat(properties.seqno().requireAmps()).isTrue();
        assertThat(properties.seqno().source()).isEqualTo("dropcopy-primary");
        assertThat(properties.flow().channel()).isEqualTo(QfjProperties.ChannelMode.DIRECT);
        assertThat(properties.flow().rules()).extracting(rule -> rule.type())
                .containsExactly("set-tag", "copy-tag", "source-session", "received-time");
        assertThat(properties.flow().rules().get(0).value()).isEqualTo("VENUE-DROPCOPY");
        assertThat(properties.flow().destinations()).hasSize(2);
        assertThat(properties.flow().destinations().get(0).name()).isEqualTo("execs-blotter");
        assertThat(properties.flow().destinations().get(0).type()).isEqualTo(DestinationType.AMPS);
        assertThat(properties.flow().destinations().get(0).topic()).isEqualTo("sow/dropcopy/fix42/execs");
        assertThat(properties.flow().destinations().get(0).msgTypes()).containsExactly("8");
        assertThat(properties.flow().destinations().get(1).topic()).isEqualTo("dropcopy/fix42/audit");
        assertThat(properties.flow().destinations().get(1).msgTypes()).isEmpty();
        assertThat(properties.mock().enabled()).isFalse();
    }

    @Test
    @DisplayName("the venue role: acceptor settings, the mock feed on, one audit destination")
    void venueRoleBindsAndValidates() {
        QfjProperties properties = bind("venue");
        assertThatCode(properties::validate).doesNotThrowAnyException();

        assertThat(properties.role()).isEqualTo("venue");
        assertThat(properties.engine().settings()).isEqualTo("config/venue/quickfixj.cfg");
        assertThat(properties.mock().enabled()).isTrue();
        assertThat(properties.mock().intervalMs()).isEqualTo(2000);
        assertThat(properties.mock().symbols()).contains("AAPL", "TSLA");
        assertThat(properties.seqno().source()).isEqualTo("venue-primary");
        assertThat(properties.flow().rules()).isEmpty();
        assertThat(properties.flow().destinations()).hasSize(1);
        assertThat(properties.flow().destinations().get(0).topic()).isEqualTo("dropcopy/fix42/audit");
    }
}
