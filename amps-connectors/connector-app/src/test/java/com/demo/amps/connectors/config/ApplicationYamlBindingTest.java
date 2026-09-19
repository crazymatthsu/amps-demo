package com.demo.amps.connectors.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.hazelcast.HazelcastSourceFactory;
import com.demo.amps.connectors.jdbc.JdbcSourceFactory;
import com.demo.amps.connectors.kafka.KafkaSourceFactory;
import com.demo.amps.connectors.runtime.PublishRequest;
import com.demo.amps.connectors.runtime.RecordPipeline;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SimulatedSource;
import com.demo.amps.connectors.source.SourceFactory;
import com.demo.amps.connectors.source.SourceRecord;
import com.demo.amps.connectors.source.SourceResolver;
import com.demo.amps.connectors.tcp.TcpSourceFactory;
import com.demo.amps.connectors.transform.TransformRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The shipped configuration -- the baked defaults, and the {@code demo} profile's four example
 * connectors on top of them -- binds, validates, and means what the documentation says it
 * means.
 *
 * <p>Bound through a {@link Binder} rather than by booting the application, for two reasons:
 * the demo profile's sources are simulated but its AMPS server is not, so starting the context
 * would dial a broker that is not there; and layering the two files by hand is exactly what a
 * deployment does, which makes the placeholder resolution ({@code ${source-driver:REAL}}
 * landing on an enum rather than staying a literal) part of what is being asserted.
 *
 * <p>The deployable per-application files under {@code amps-connectors/config/} get the same
 * treatment in {@link ConfigTreeTest}.
 */
class ApplicationYamlBindingTest {

    /** Every driver module the generic runner ships with, in the order the resolver sees them. */
    private static final List<SourceFactory> FACTORIES = List.of(
            new TcpSourceFactory(), new KafkaSourceFactory(),
            new JdbcSourceFactory(), new HazelcastSourceFactory());

    private final ConnectorsProperties properties = bind("application-demo.yml", "application.yml");

    // ---- binding, layered the way a profile layers ---------------------------------

    /** Binds the named classpath YAML files, FIRST one winning, as Spring layers a profile. */
    private static ConnectorsProperties bind(String... classpathYaml) {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        for (String name : classpathYaml) {
            try {
                for (PropertySource<?> source : loader.load(name, new ClassPathResource(name))) {
                    sources.addLast(source);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + name, e);
            }
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("amps-connectors", Bindable.of(ConnectorsProperties.class)).get();
    }

    private ConnectorProperties connector(String name) {
        List<ConnectorProperties> matches = properties.getConnectors().stream()
                .filter(c -> name.equals(c.getName())).toList();
        assertThat(matches).as("connector %s", name).hasSize(1);
        return matches.get(0);
    }

    // ---- the baked layer -----------------------------------------------------------

    @Test
    @DisplayName("the jar bakes no connectors and no environment")
    void theBakedFileDefinesSafeDefaultsOnly() {
        // The deployment model in one assertion: the image is generic, and it is the mounted
        // configuration that makes an instance a particular application. A baked connector
        // would also bleed through under any shorter mounted list, because two lists merge
        // by index.
        ConnectorsProperties baked = bind("application.yml");
        assertThat(baked.getConnectors()).isEmpty();
        assertThat(baked.isEnabled()).isTrue();
        assertThat(baked.getStatusInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(ConnectorValidator.validate(baked)).isEmpty();
    }

    @Test
    @DisplayName("the shared AMPS block defaults to a local server with an in-heap publish store")
    void bindsTheAmpsServerBlock() {
        AmpsServerProperties amps = properties.getAmps();
        assertThat(amps.getHost()).isEqualTo("localhost");     // ${AMPS_HOST:localhost}
        assertThat(amps.getPort()).isEqualTo(9007);            // ${AMPS_PORT:9007}
        assertThat(amps.getTransport()).isEqualTo("tcp");
        assertThat(amps.getClientNamePrefix()).isEqualTo("amps-connectors");
        assertThat(amps.getPublishStore()).isEqualTo(AmpsServerProperties.PublishStore.MEMORY);
        assertThat(amps.getFlushTimeout()).isEqualTo(Duration.ofSeconds(10));
        // The message type is part of the URI, not of the publish call, which is why a FIX
        // connector and a JSON connector cannot share one connection.
        assertThat(amps.uri("json")).isEqualTo("tcp://localhost:9007/amps/json");
        assertThat(amps.uri("fix")).isEqualTo("tcp://localhost:9007/amps/fix");
        assertThat(amps.clientName("orders-kafka")).isEqualTo("amps-connectors-orders-kafka");
    }

    // ---- the demo profile ----------------------------------------------------------

    @Test
    void bindsEveryExampleConnector() {
        assertThat(properties.getConnectors()).extracting(ConnectorProperties::getName)
                .containsExactly("ticks-tcp", "orders-kafka", "positions-jdbc",
                        "events-hazelcast");
    }

    @Test
    void theShippedConfigurationPassesValidation() {
        assertThat(ConnectorValidator.validate(properties)).isEmpty();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @Test
    @DisplayName("one example per transport, each keeping the block it stands in for")
    void everyExampleNamesExactlyOneTransport() {
        // A simulated connector keeps its transport block on purpose: it says what the
        // connector stands in for, and the validator's transport rules go on applying -- so
        // the demo cannot validate a configuration the real deployment would reject.
        assertThat(properties.getConnectors())
                .allSatisfy(c -> assertThat(c.getSource().configuredBlocks()).hasSize(1))
                .flatExtracting(c -> c.getSource().configuredBlocks())
                .containsExactlyInAnyOrder("tcp", "kafka", "jdbc", "hazelcast");
    }

    @Test
    @DisplayName("the demo profile swaps every source for the in-process generator")
    void theDemoProfileResolvesToTheSimulator() {
        assertThat(properties.getConnectors())
                .extracting(c -> c.getSource().getDriver())
                .containsOnly(SourceProperties.Driver.SIMULATED);
        SourceResolver resolver = new SourceResolver(FACTORIES);
        assertThat(properties.getConnectors())
                .allSatisfy(c -> assertThat(resolver.resolve(c))
                        .isInstanceOf(SimulatedSource.class));
    }

    @Test
    @DisplayName("the runner carries a driver module for every example's transport")
    void theRunnerCarriesEveryDriver() {
        // One image serves the whole fleet, so the drivers have to arrive with it: this is
        // what catches a source module dropped from connector-app's build file, which would
        // otherwise surface as a startup failure on the day someone deploys that block.
        assertThat(properties.getConnectors()).allSatisfy(connector ->
                assertThat(FACTORIES).as("a driver supporting %s", connector.getName())
                        .anyMatch(factory -> factory.supports(connector)));
    }

    @Test
    @DisplayName("the TCP example listens, and publishes onto a journal topic with no key")
    void bindsTheTcpConnector() {
        ConnectorProperties connector = connector("ticks-tcp");
        assertThat(connector.getFormat()).isEqualTo(SourceFormat.JSON);
        TcpSourceProperties tcp = connector.getSource().getTcp();
        assertThat(tcp.getMode()).isEqualTo(TcpSourceProperties.Mode.LISTEN);
        assertThat(tcp.getPort()).isEqualTo(5001);
        assertThat(tcp.getDelimiter()).isEqualTo("\n");
        assertThat(connector.getAmps().getTopic()).isEqualTo("connectors/ticks");
        // No key and no removal: a journal topic has no SOW record to address.
        assertThat(connector.getAmps().getKey()).isNull();
        assertThat(connector.getAmps().getOnDelete())
                .isEqualTo(AmpsTargetProperties.OnDelete.IGNORE);
        assertThat(connector.getAmps().getBatch().getMaxMessages()).isEqualTo(2000);
        assertThat(connector.getAmps().getBatch().getFlushInterval())
                .isEqualTo(Duration.ofMillis(250));
    }

    @Test
    @DisplayName("the Kafka example is FIX, filtered, and keyed BY THE SERVER on tag 11")
    void bindsTheKafkaConnector() {
        ConnectorProperties connector = connector("orders-kafka");
        assertThat(connector.getFormat()).isEqualTo(SourceFormat.FIX);
        // Not set in the YAML: a FIX feed puts SOH on the wire, and the template's pipes are
        // the simulator's spelling of it, not the connector's.
        assertThat(connector.getFieldSeparator()).isEqualTo(ConnectorProperties.SOH);
        assertThat(connector.getSource().getSimulated().getTemplate()).startsWith("35=D|11=ORD-");
        KafkaSourceProperties kafka = connector.getSource().getKafka();
        assertThat(kafka.getTopic()).isEqualTo("orders.fix");
        assertThat(kafka.getGroupId()).isNotBlank();
        assertThat(kafka.getFrom()).isEqualTo(KafkaSourceProperties.From.EARLIEST);
        assertThat(connector.getFilter().getMatch()).isEqualTo(FilterProperties.Match.ALL);
        assertThat(connector.getFilter().getRules()).singleElement()
                .satisfies(rule -> {
                    assertThat(rule.getField()).isEqualTo("35");
                    assertThat(rule.getIn()).containsExactly("D", "G", "F");
                    assertThat(rule.configuredOperators()).containsExactly("in");
                });
        AmpsTargetProperties target = connector.getAmps();
        assertThat(target.getTopic()).isEqualTo("sow/connectors/orders");
        assertThat(target.getMessageType()).isEqualTo("fix");
        assertThat(target.getKey().getMode()).isEqualTo(KeyProperties.Mode.SERVER);
        assertThat(target.getKey().getFields()).containsExactly("11");
    }

    @Test
    @DisplayName("the JDBC example is keyed BY THE PUBLISHER on account+symbol, with a derive")
    void bindsTheJdbcConnector() {
        ConnectorProperties connector = connector("positions-jdbc");
        // The validator requires it, because the source synthesises the payload as JSON.
        assertThat(connector.getFormat()).isEqualTo(SourceFormat.JSON);
        JdbcSourceProperties jdbc = connector.getSource().getJdbc();
        assertThat(jdbc.getMode()).isEqualTo(JdbcSourceProperties.Mode.SNAPSHOT);
        assertThat(jdbc.getKeyColumns()).containsExactly("account", "symbol");
        assertThat(jdbc.getPassword()).isEmpty();      // ${JDBC_PASSWORD:} with nothing set
        assertThat(connector.getTransforms()).singleElement().satisfies(step -> {
            assertThat(step.configuredKinds()).containsExactly("derive");
            assertThat(step.getDerive()).containsOnlyKeys("notional");
        });
        AmpsTargetProperties target = connector.getAmps();
        assertThat(target.getTopic()).isEqualTo("sow/connectors/positions");
        assertThat(target.getKey().getMode()).isEqualTo(KeyProperties.Mode.PUBLISHER);
        assertThat(target.getKey().getFields()).containsExactly("account", "symbol");
        assertThat(target.getKey().getSeparator()).isEqualTo("|");
        assertThat(target.getOnDelete()).isEqualTo(AmpsTargetProperties.OnDelete.SOW_DELETE);
        assertThat(target.getBatch().getMaxMessages()).isEqualTo(5000);
    }

    @Test
    @DisplayName("the Hazelcast example replays its ringbuffer and keeps four fields")
    void bindsTheHazelcastConnector() {
        ConnectorProperties connector = connector("events-hazelcast");
        HazelcastSourceProperties hazelcast = connector.getSource().getHazelcast();
        assertThat(hazelcast.getMembers()).containsExactly("localhost:5701");
        assertThat(hazelcast.getTopic()).isEqualTo("connector.events");
        assertThat(hazelcast.isReliable()).isTrue();
        assertThat(hazelcast.getReliableFrom())
                .isEqualTo(HazelcastSourceProperties.ReliableFrom.OLDEST);
        assertThat(connector.getTransforms()).singleElement().satisfies(step -> {
            assertThat(step.configuredKinds()).containsExactly("keep");
            // The key field has to survive the projection, or nothing could be keyed.
            assertThat(step.getKeep()).contains("id");
        });
        assertThat(connector.getAmps().getTopic()).isEqualTo("sow/connectors/events");
        assertThat(connector.getAmps().getKey().getMode()).isEqualTo(KeyProperties.Mode.SERVER);
        assertThat(connector.getAmps().getKey().getFields()).containsExactly("id");
    }

    @Test
    @DisplayName("each example's simulated records survive that example's own pipeline")
    void theSimulatedTemplatesSurviveTheirOwnPipelines() throws Exception {
        // A demo profile whose templates the connectors themselves reject would look like a
        // working application and publish nothing -- the records would be counted as
        // `rejected` in a status line nobody is reading. This runs one generated record of
        // each example through that example's real pipeline: decode, filter, transforms, key
        // and encode, everything except the socket to AMPS.
        TransformRegistry noBeans = new TransformRegistry(Map.of());
        SourceResolver resolver = new SourceResolver(FACTORIES);
        for (ConnectorProperties connector : properties.getConnectors()) {
            RecordPipeline pipeline = new RecordPipeline(connector, noBeans);
            BlockingQueue<SourceRecord> generated = new LinkedBlockingQueue<>();
            try (RecordSource source = resolver.resolve(connector)) {
                source.start(generated::add);
                SourceRecord record = generated.poll(5, TimeUnit.SECONDS);
                assertThat(record).as("%s generated a record", connector.getName()).isNotNull();

                PublishRequest request = pipeline.apply(record);
                assertThat(request).as("%s: the pipeline kept its own record", connector.getName())
                        .isNotNull();
                assertThat(request.topic()).isEqualTo(connector.getAmps().getTopic());
                assertThat(request.data()).isNotBlank();
                // SERVER mode sends no SowKey and PUBLISHER mode must send one: between them
                // that is the whole of what the key block promises.
                KeyProperties key = connector.getAmps().getKey();
                if (key != null && key.getMode() == KeyProperties.Mode.PUBLISHER) {
                    assertThat(request.sowKey()).as("%s: a SowKey", connector.getName())
                            .isNotBlank();
                } else if (key != null) {
                    assertThat(request.sowKey()).as("%s: SERVER mode sends none",
                            connector.getName()).isNull();
                }
            }
        }
        // ...and the two that do more than pass bytes through actually did it: the FIX
        // template's pipes became SOH on the way out of the simulator, and the derive
        // produced a field the feed never carried.
        assertThat(publishedBy("orders-kafka").data())
                .contains(String.valueOf(ConnectorProperties.SOH)).doesNotContain("|");
        assertThat(publishedBy("positions-jdbc").data()).contains("\"notional\"");
        assertThat(publishedBy("events-hazelcast").data())
                .contains("\"id\"").doesNotContain("\"detail\"");
    }

    /** One generated record of a connector, as its pipeline would publish it. */
    private PublishRequest publishedBy(String name) throws Exception {
        ConnectorProperties connector = connector(name);
        BlockingQueue<SourceRecord> generated = new LinkedBlockingQueue<>();
        try (RecordSource source = new SourceResolver(FACTORIES).resolve(connector)) {
            source.start(generated::add);
            SourceRecord record = generated.poll(5, TimeUnit.SECONDS);
            assertThat(record).as("%s generated a record", name).isNotNull();
            PublishRequest request =
                    new RecordPipeline(connector, new TransformRegistry(Map.of())).apply(record);
            assertThat(request).as("%s published something", name).isNotNull();
            return request;
        }
    }

    @Test
    @DisplayName("the two key modes are both represented, because they are different contracts")
    void bothKeyModesAreDemonstrated() {
        // SERVER means "the topic has a <Key>; check the payload carries the fields".
        // PUBLISHER means "the topic has none; send a SowKey or reject the record". Getting
        // these the wrong way round is the failure this framework is most careful about, so
        // the shipped examples show both.
        assertThat(properties.getConnectors()).filteredOn(c -> c.getAmps().getKey() != null)
                .extracting(c -> c.getAmps().getKey().getMode())
                .containsExactlyInAnyOrder(KeyProperties.Mode.SERVER, KeyProperties.Mode.PUBLISHER,
                        KeyProperties.Mode.SERVER);
    }
}
