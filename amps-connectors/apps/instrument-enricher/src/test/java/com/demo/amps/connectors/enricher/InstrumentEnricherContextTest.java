package com.demo.amps.connectors.enricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.AlertManager;
import com.demo.amps.connectors.alert.RecordingAlertSink;
import com.demo.amps.connectors.amps.AmpsPublisherFactory;
import com.demo.amps.connectors.amps.RecordingAmpsPublisher;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.ConnectorsProperties;
import com.demo.amps.connectors.config.SourceProperties;
import com.demo.amps.connectors.control.CommandDispatcher;
import com.demo.amps.connectors.resource.ResourceRegistry;
import com.demo.amps.connectors.resource.jdbc.JdbcLookupTable;
import com.demo.amps.connectors.runtime.Connector;
import com.demo.amps.connectors.runtime.ConnectorManager;
import com.demo.amps.connectors.transform.RecordTransform;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The deployable application, booted: the baked defaults, {@code config/local/common/} and
 * {@code config/local/streams/instrument-enricher/} layered exactly as the container mounts
 * them, with three things swapped for doubles -- the PostgreSQL URL for a seeded in-memory
 * H2, the Kafka feed for the simulator, and the AMPS clients for recording ones.
 *
 * <p>This is the test that proves the instance file <em>means</em> what it says, which
 * {@code ConfigTreeTest} (binding only) cannot: the resource loads, the {@code bean:} step
 * resolves to {@link EnricherConfiguration}'s bean, the rules compile under the connector,
 * a simulated order comes out the far end carrying {@code 48=}, {@code 22=2} and
 * {@code 15=} as SOH-separated FIX, and the one symbol the table does not hold is alerted
 * as {@code UNKNOWN_SYMBOL} with the application's name on it.
 *
 * <p>The configuration directories are addressed by absolute path, resolved from the
 * module's location (Gradle runs a {@code Test} task from the module directory; an IDE may
 * not), and handed to Spring as a system property because
 * {@code spring.config.additional-location} has to be in the environment before the
 * context is built -- earlier than any {@code @DynamicPropertySource} runs. The swaps are
 * the placeholders the instance file offers for exactly this ({@code JDBC_URL},
 * {@code source-driver}) rather than overrides of {@code resources[0]} or
 * {@code connectors[0]}: a list is bound from one property source, so an element
 * overridden here would be a resource with a URL and nothing else. The control channel is
 * switched off: it would dial the AMPS the instance file names, and nothing here needs it.
 */
@SpringBootTest(
        // Named explicitly, so the doubles have to be named too: a nested @TestConfiguration
        // is only found on its own when the test declares no classes at all.
        classes = {InstrumentEnricherApplication.class, InstrumentEnricherContextTest.Doubles.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // The seeded database in place of the instance file's PostgreSQL.
                "JDBC_URL=" + InstrumentEnricherContextTest.DATABASE,
                // The feed: every connector's ${source-driver:REAL} becomes the simulator,
                // generating the instance file's own template (six keys: K-0..K-4 are in the
                // table, K-5 is not); the Kafka block stays for the validator.
                "source-driver=SIMULATED",
                "amps-connectors.control.enabled=false",
                "amps-connectors.status-interval=1h"
        })
class InstrumentEnricherContextTest {

    static final String DATABASE = "jdbc:h2:mem:enricher-context;DB_CLOSE_DELAY=-1";

    /** The user the instance file connects as; an in-memory H2 is created by its first user. */
    private static final String USERNAME = "refdata_ro";

    private static final String CONNECTOR = "orders-enriched";
    private static final String ADDITIONAL_LOCATION = "spring.config.additional-location";
    private static final Duration PATIENCE = Duration.ofSeconds(15);
    private static final char SOH = ConnectorProperties.SOH;

    static {
        Path local = InstrumentsDatabase.moduleDir().resolve("../../config/local").normalize();
        System.setProperty(ADDITIONAL_LOCATION,
                "file:" + local.resolve("common") + "/,"
                        + "file:" + local.resolve("streams").resolve("instrument-enricher") + "/");
        try {
            InstrumentsDatabase.seeded(DATABASE, USERNAME, "");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot seed " + DATABASE, e);
        }
    }

    @AfterAll
    static void forgetTheConfigLocations() {
        System.clearProperty(ADDITIONAL_LOCATION);
    }

    /** The doubles: recording AMPS clients, and the alerts topic as a list. */
    @TestConfiguration
    static class Doubles {

        private final Map<String, RecordingAmpsPublisher> publishers = new ConcurrentHashMap<>();
        private final RecordingAlertSink alerts = new RecordingAlertSink();

        /** One recording publisher per connector, exactly as the real factory does it. */
        @Bean
        AmpsPublisherFactory recordingPublishers() {
            return connector -> publishers.computeIfAbsent(
                    connector.getName(), name -> new RecordingAmpsPublisher());
        }

        /**
         * The application's alert manager over the recording sink ALONE. The instance file
         * names an AMPS alerts topic, which would otherwise be dialled by the real sink;
         * declaring the manager here leaves that sink unstarted and puts everything the
         * application raises where the test can read it.
         */
        @Bean
        AlertManager alertManager(ConnectorsProperties properties, Environment environment, Clock clock) {
            return new AlertManager(properties.getAlerts(),
                    properties.getAlerts().applicationName(environment), List.of(alerts), clock);
        }

        RecordingAmpsPublisher publisher(String connector) {
            return publishers.get(connector);
        }

        RecordingAlertSink alerts() {
            return alerts;
        }
    }

    @Autowired
    private Doubles doubles;

    @Autowired
    private ConnectorsProperties properties;

    @Autowired
    private EnricherProperties enricher;

    @Autowired
    private ConnectorManager manager;

    @Autowired
    private ResourceRegistry resources;

    @Autowired
    private CommandDispatcher control;

    @Autowired
    private Map<String, RecordTransform> transforms;

    // ---- the layers ------------------------------------------------------------------

    @Test
    @DisplayName("the instance file, the common file and the baked defaults all reached the context")
    void theThreeLayersAreBound() {
        // The instance file: its connector, its resource, its alert application name.
        assertThat(properties.getConnectors()).extracting(ConnectorProperties::getName)
                .containsExactly(CONNECTOR);
        assertThat(properties.getResources()).singleElement()
                .satisfies(resource -> {
                    assertThat(resource.getName()).isEqualTo("instruments");
                    assertThat(resource.getJdbc().getQuery()).startsWith("SELECT symbol, sedol");
                    assertThat(resource.getJdbc().getKeyColumns()).containsExactly("symbol");
                    assertThat(resource.getJdbc().getUrl()).as("JDBC_URL took the placeholder")
                            .isEqualTo(DATABASE);
                    assertThat(resource.getJdbc().getUsername()).isEqualTo(USERNAME);
                    assertThat(resource.getJdbc().getPassword()).as("${JDBC_PASSWORD:}").isEmpty();
                });
        assertThat(properties.getAlerts().getApplication()).isEqualTo("instrument-enricher");
        assertThat(properties.getAlerts().getAmps().getTopic()).isEqualTo("connectors/alerts");
        assertThat(properties.getControl().getSource().getAmps().getTopic())
                .isEqualTo("connectors/control");
        assertThat(properties.getControl().isEnabled()).as("switched off for the test").isFalse();
        assertThat(control.status()).isEqualTo("control: disabled");
        // The common file: the shared server block.
        assertThat(properties.getAmps().getClientNamePrefix()).isEqualTo("amps-connectors");
        // The baked file: the enricher's own defaults, and the simulator switch.
        assertThat(enricher.getResource()).isEqualTo("instruments");
        assertThat(enricher.getSymbolTag()).isEqualTo("55");
        assertThat(enricher.getSet()).containsExactly(entry("48", "sedol"), entry("15", "currency"));
        assertThat(enricher.getLiterals()).containsExactly(entry("22", "2"));
        assertThat(enricher.getOnMiss()).isEqualTo(EnricherProperties.OnMiss.PASS);
        SourceProperties source = properties.getConnectors().get(0).getSource();
        assertThat(source.getDriver()).isEqualTo(SourceProperties.Driver.SIMULATED);
        assertThat(source.getKafka()).as("the transport block stays under the simulator")
                .isNotNull();
        assertThat(source.getSimulated().getKeys()).isEqualTo(6);
        assertThat(source.getSimulated().getTemplate()).startsWith("8=FIX.4.2|35=D|11=ORD-");
    }

    @Test
    @DisplayName("the resource loaded from the seed, and the bean: step resolved to the enricher holding it")
    void theResourceAndTheBeanAreWired() {
        JdbcLookupTable table = resources.lookup("instruments", JdbcLookupTable.class);
        assertThat(table.isAvailable()).isTrue();
        assertThat(table.size()).isEqualTo(InstrumentsDatabase.SEEDED.size());
        assertThat(resources.status()).contains("instruments AVAILABLE rows=5");

        assertThat(transforms).containsOnlyKeys("instrumentEnricher");
        assertThat(transforms.get("instrumentEnricher")).isInstanceOf(InstrumentEnricher.class);
        // The rules step compiled under the connector's name: its counters are on the line.
        Connector connector = connector();
        assertThat(connector.isStarted()).isTrue();
        assertThat(connector.status()).contains("rules[limit-without-price=0,large-notional=0]");
    }

    // ---- the records ----------------------------------------------------------------

    @Test
    @DisplayName("a simulated order for a known symbol is published as SOH FIX with 48, 22=2 and 15, and without its checksum")
    void knownSymbolIsPublishedEnriched() {
        RecordingAmpsPublisher.Call published = awaitPublished("ORD-K-0");

        assertThat(published.topic()).isEqualTo("sow/connectors/orders");
        assertThat(published.sowKeyOrFilter()).as("SERVER mode sends no SowKey").isNull();
        assertThat(published.text())
                .contains(SOH + "11=ORD-K-0" + SOH)
                .contains(SOH + "55=K-0" + SOH)
                .contains(SOH + "48=B0YQ5W0" + SOH)
                .contains(SOH + "22=2" + SOH)
                .contains(SOH + "15=GBP" + SOH)
                .doesNotContain("10=000")
                .doesNotContain("|");
        assertThat(((InstrumentEnricher) transforms.get("instrumentEnricher")).hits())
                .isPositive();
    }

    @Test
    @DisplayName("every known symbol comes out with its own SEDOL and currency")
    void everyKnownSymbolIsEnriched() {
        for (InstrumentsDatabase.Instrument instrument : InstrumentsDatabase.SEEDED) {
            RecordingAmpsPublisher.Call published = awaitPublished("ORD-" + instrument.symbol());
            assertThat(published.text()).as(instrument.symbol())
                    .contains(SOH + "48=" + instrument.sedol() + SOH)
                    .contains(SOH + "15=" + instrument.currency() + SOH);
        }
    }

    @Test
    @DisplayName("the symbol the table does not hold is published unenriched and raised as UNKNOWN_SYMBOL under the application's name")
    void unknownSymbolPassesThroughAndAlerts() {
        RecordingAmpsPublisher.Call published = awaitPublished("ORD-K-5");
        assertThat(published.text())
                .contains(SOH + "55=K-5" + SOH)
                .doesNotContain(SOH + "48=")
                .doesNotContain(SOH + "22=")
                .doesNotContain(SOH + "15=");

        Alert alert = doubles.alerts().awaitCode(InstrumentEnricher.UNKNOWN_SYMBOL);
        assertThat(alert.severity()).isEqualTo(Alert.Severity.WARN);
        assertThat(alert.application()).isEqualTo("instrument-enricher");
        assertThat(alert.timestamp()).isNotNull();
        assertThat(alert.details()).containsEntry("symbol", "K-5");
        assertThat(String.valueOf(alert.details().get("clOrdId"))).isEqualTo("ORD-K-5");
        assertThat(((InstrumentEnricher) transforms.get("instrumentEnricher")).misses())
                .isPositive();
        // Passed through, so counted as published rather than dropped or rejected.
        assertThat(connector().rejected()).isZero();
        assertThat(connector().dropped()).isZero();
    }

    private Connector connector() {
        return manager.connectors().stream()
                .filter(connector -> CONNECTOR.equals(connector.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no connector named " + CONNECTOR));
    }

    /** The first recorded publish of the order with this ClOrdID; waits for the batch to go. */
    private RecordingAmpsPublisher.Call awaitPublished(String clOrdId) {
        String marker = SOH + "11=" + clOrdId + SOH;
        Awaitility.await("a publish of " + clOrdId).atMost(PATIENCE)
                .until(() -> find(marker).isPresent());
        return find(marker).orElseThrow();
    }

    private Optional<RecordingAmpsPublisher.Call> find(String marker) {
        RecordingAmpsPublisher publisher = doubles.publisher(CONNECTOR);
        if (publisher == null) {
            return Optional.empty();
        }
        return publisher.calls("publish").stream()
                .filter(call -> call.data() != null && call.text().contains(marker))
                .findFirst();
    }
}
