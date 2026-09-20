package com.demo.amps.connectors.enricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.control.CommandDispatcher;
import com.demo.amps.connectors.it.AmpsSow;
import com.demo.amps.connectors.it.ConnectorAppRunner;
import com.demo.amps.connectors.resource.ResourceRegistry;
import com.demo.amps.connectors.resource.jdbc.JdbcLookupTable;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The example application end to end against a real AMPS: the enrichment lands in the SOW,
 * the miss lands on the alerts topic, a {@code reload} command over the control topic makes
 * a new instrument known, and a {@code status} command is answered on the alerts topic.
 *
 * <p>The same application class the image runs, started in this JVM by the shared
 * {@link ConnectorAppRunner} with the instance file's blocks written as properties -- the
 * resource, the control channel, the alerts, and the {@code orders-enriched} connector with
 * its filter, its three transform steps and its server-keyed target -- and two substitutions:
 * the Kafka feed is the simulator (the Kafka block stays, for the validator), and the
 * PostgreSQL table is an in-memory H2 seeded from the module's own {@code sql/instruments.sql}.
 * Everything AMPS-side is real: three clients log on (the connector's publisher, the alerts
 * sink, the control subscription), and the assertions read the server back with a plain
 * client rather than trusting the application's counters.
 *
 * <p>Ordered, because each phase builds on the state the previous one left, which is also how
 * an operator experiences the application: it is running and enriching, a symbol is missing,
 * the table is fixed and reloaded, the fix is visible. The reload timer is off
 * ({@code reload-interval: 0}) so that only the command can be what made {@code K-5} known.
 *
 * <p>Skips rather than fails when {@code AMPS_IMAGE} is unset, like every other suite here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstrumentEnricherIT {

    private static final String APPLICATION = "instrument-enricher";
    private static final String CONNECTOR = "orders-enriched";
    private static final String ORDERS = "sow/connectors/orders";
    private static final String CONTROL = "connectors/control";
    private static final String ALERTS = "connectors/alerts";
    private static final String DATABASE = "jdbc:h2:mem:enricher-it;DB_CLOSE_DELAY=-1";
    private static final String TEMPLATE = "8=FIX.4.2|35=D|11=ORD-{{key}}|55={{key}}|54=1"
            + "|38={{seq}}|40=2|44=10.5|60={{ts}}|10=000";

    /** Generous: a batch waits out its idle timer, a subscription its logon, a replay its idle. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** How long a replay of the alerts journal waits for one more message before it is done. */
    private static final Duration REPLAY_IDLE = Duration.ofSeconds(2);

    private static final char SOH = ConnectorProperties.SOH;

    private AmpsTestServer server;
    private ConnectorAppRunner app;
    private Client json;
    private Client fix;

    @BeforeAll
    void startEverything() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.AMPS_CONNECTORS);
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        // The table first: the resource loads synchronously at start, and an empty table
        // would make every order a RESOURCE_UNAVAILABLE rather than the enrichment under test.
        InstrumentsDatabase.seeded(DATABASE);

        server = AmpsTestServer.start(AmpsFlow.AMPS_CONNECTORS);
        app = ConnectorAppRunner.against(server.port(), InstrumentEnricherApplication.class)
                .property("spring.application.name", APPLICATION)
                // ---- the resource: the instance file's entry over H2, timer off ----------
                .property("amps-connectors.resources[0].name", "instruments")
                .property("amps-connectors.resources[0].jdbc.url", DATABASE)
                .property("amps-connectors.resources[0].jdbc.query",
                        "SELECT symbol, sedol, isin, currency, ric FROM instruments")
                .property("amps-connectors.resources[0].jdbc.key-columns[0]", "symbol")
                .property("amps-connectors.resources[0].jdbc.reload-interval", "0")
                // ---- the control channel, over the same AMPS ---------------------------
                .property("amps-connectors.control.enabled", "true")
                .property("amps-connectors.control.target", APPLICATION)
                .property("amps-connectors.control.source.amps.topic", CONTROL)
                .property("amps-connectors.control.source.amps.mode", "SUBSCRIBE")
                // ---- the alerts, onto a journalled topic of the same AMPS --------------
                .property("amps-connectors.alerts.application", APPLICATION)
                .property("amps-connectors.alerts.amps.topic", ALERTS)
                .property("amps-connectors.alerts.suppress-repeats", "30s")
                // ---- the connector, as the instance file has it, simulated --------------
                .connector(CONNECTOR)
                .set("format", "FIX")
                .set("source.driver", "SIMULATED")
                .set("source.simulated.rate", 20)
                .set("source.simulated.keys", 6)     // K-0..K-4 are seeded; K-5 is not
                .set("source.simulated.template", TEMPLATE)
                .set("source.kafka.bootstrap-servers", "localhost:9092")
                .set("source.kafka.topic", "orders.fix")
                .set("source.kafka.group-id", "amps-connectors-orders-enriched")
                .set("filter.rules[0].field", "35")
                .set("filter.rules[0].in[0]", "D")
                .set("filter.rules[0].in[1]", "G")
                .set("filter.rules[0].in[2]", "F")
                .set("transforms[0].drop[0]", "10")
                .set("transforms[1].bean", "instrumentEnricher")
                .set("transforms[2].rules[0].name", "limit-without-price")
                .set("transforms[2].rules[0].when", "#f['40'] == '2' && !#f.containsKey('44')")
                .set("transforms[2].rules[0].then.alert.severity", "WARN")
                .set("transforms[2].rules[0].then.alert.code", "LIMIT_WITHOUT_PRICE")
                .set("transforms[2].rules[0].then.alert.message",
                        "limit order #{#f['11']} has no price")
                .set("transforms[2].rules[1].name", "large-notional")
                .set("transforms[2].rules[1].when", "#num(#f['38']) * #num(#f['44']) > 1000000")
                .set("transforms[2].rules[1].then.set.5001", "LARGE")
                .set("amps.topic", ORDERS)
                .set("amps.message-type", "fix")
                .set("amps.key.fields[0]", "11")
                .set("amps.key.mode", "SERVER")
                .set("amps.on-delete", "SOW_DELETE")
                .set("amps.batch.max-messages", 500)
                .set("amps.batch.flush-interval", "250ms")
                .start();

        json = AmpsSow.connect(server.port(), "json", "enricher-it-json");
        fix = AmpsSow.connect(server.port(), "fix", "enricher-it-fix");
    }

    @AfterAll
    void stopEverything() {
        if (app != null && app.context().isActive()) {
            app.close();
        }
        if (fix != null) {
            fix.close();
        }
        if (json != null) {
            json.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("the resource loaded, the control channel is listening, and the rules step compiled under the connector")
    void theApplicationIsUp() {
        JdbcLookupTable table = app.context().getBean(ResourceRegistry.class)
                .lookup("instruments", JdbcLookupTable.class);
        assertThat(table.isAvailable()).isTrue();
        assertThat(table.size()).isEqualTo(InstrumentsDatabase.SEEDED.size());

        CommandDispatcher control = app.context().getBean(CommandDispatcher.class);
        Awaitility.await("the control subscription logged on").atMost(PATIENCE)
                .until(control::isConnected);
        assertThat(control.target()).isEqualTo(APPLICATION);
        assertThat(control.commands()).contains("reload", "status");

        assertThat(app.connector(CONNECTOR).status())
                .contains("rules[limit-without-price=0,large-notional=0]");
    }

    @Test
    @Order(2)
    @DisplayName("every known symbol's order reaches the SOW as FIX carrying 48 (SEDOL), 22=2 and 15 (currency)")
    void knownSymbolsAreEnrichedInTheSow() {
        for (InstrumentsDatabase.Instrument instrument : InstrumentsDatabase.SEEDED) {
            String clOrdId = "ORD-" + instrument.symbol();
            Awaitility.await(clOrdId + " enriched").atMost(PATIENCE)
                    .untilAsserted(() -> assertThat(AmpsSow.records(fix, ORDERS, "/11 = '" + clOrdId + "'"))
                            .singleElement()
                            .satisfies(record -> assertThat(record.data())
                                    .contains(SOH + "55=" + instrument.symbol() + SOH)
                                    .contains(SOH + "48=" + instrument.sedol() + SOH)
                                    .contains(SOH + "22=2" + SOH)
                                    .contains(SOH + "15=" + instrument.currency() + SOH)
                                    .doesNotContain(SOH + "10=")));
        }
        assertThat(app.connector(CONNECTOR).rejected()).isZero();
        assertThat(app.connector(CONNECTOR).dropped()).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("the unknown symbol's order reaches the SOW unenriched, and UNKNOWN_SYMBOL reaches the alerts topic")
    void unknownSymbolPassesThroughAndIsAlerted() {
        Awaitility.await("ORD-K-5 published as it came").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(fix, ORDERS, "/11 = 'ORD-K-5'"))
                        .singleElement()
                        .satisfies(record -> assertThat(record.data())
                                .contains(SOH + "55=K-5" + SOH)
                                .doesNotContain(SOH + "48=")
                                .doesNotContain(SOH + "15=")));

        Awaitility.await("UNKNOWN_SYMBOL on " + ALERTS).atMost(PATIENCE)
                .untilAsserted(() -> assertThat(alerts()).anySatisfy(alert -> assertThat(alert)
                        .contains("\"code\":\"UNKNOWN_SYMBOL\"")
                        .contains("\"application\":\"" + APPLICATION + "\"")
                        .contains("\"severity\":\"WARN\"")
                        .contains("\"symbol\":\"K-5\"")));
    }

    @Test
    @Order(4)
    @DisplayName("a reload command on the control topic makes a symbol inserted since the load known, and its next order is enriched")
    void reloadCommandPicksUpANewInstrument() throws Exception {
        InstrumentsDatabase.execute(DATABASE, InstrumentsDatabase.insert("K-5", "B5YQ5W5", "CHF"));
        JdbcLookupTable table = app.context().getBean(ResourceRegistry.class)
                .lookup("instruments", JdbcLookupTable.class);
        assertThat(table.find("K-5")).as("a snapshot does not see the insert").isEmpty();

        json.publish(CONTROL, "{\"command\":\"reload\",\"target\":\"instruments\","
                + "\"to\":\"" + APPLICATION + "\",\"requestId\":\"it-1\"}");
        json.publishFlush(AmpsSow.TIMEOUT.toMillis());

        Awaitility.await("the table reloaded").atMost(PATIENCE)
                .until(() -> table.find("K-5").isPresent());
        assertThat(table.reloads()).isEqualTo(1);
        // The simulator keeps cycling, so the next ORD-K-5 replaces the unenriched record
        // under the same server-derived key.
        Awaitility.await("ORD-K-5 enriched after the reload").atMost(PATIENCE)
                .untilAsserted(() -> assertThat(AmpsSow.records(fix, ORDERS, "/11 = 'ORD-K-5'"))
                        .singleElement()
                        .satisfies(record -> assertThat(record.data())
                                .contains(SOH + "48=B5YQ5W5" + SOH)
                                .contains(SOH + "22=2" + SOH)
                                .contains(SOH + "15=CHF" + SOH)));
        CommandDispatcher control = app.context().getBean(CommandDispatcher.class);
        assertThat(control.succeeded()).isEqualTo(1);
        assertThat(control.failed()).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("a status command is answered with a STATUS alert carrying the connector and resource lines")
    void statusCommandIsAnsweredOnTheAlertsTopic() throws Exception {
        json.publish(CONTROL, "{\"command\":\"status\",\"to\":\"" + APPLICATION + "\","
                + "\"requestId\":\"it-2\"}");
        json.publishFlush(AmpsSow.TIMEOUT.toMillis());

        Awaitility.await("STATUS on " + ALERTS).atMost(PATIENCE)
                .untilAsserted(() -> assertThat(alerts()).anySatisfy(alert -> assertThat(alert)
                        .contains("\"code\":\"STATUS\"")
                        .contains("\"severity\":\"INFO\"")
                        .contains("requestId=it-2")
                        .contains(CONNECTOR)
                        .contains("instruments AVAILABLE rows=6")));
        // A command for somebody else is ignored, counted, and never answered.
        json.publish(CONTROL, "{\"command\":\"status\",\"to\":\"another-app\",\"requestId\":\"it-3\"}");
        json.publishFlush(AmpsSow.TIMEOUT.toMillis());
        CommandDispatcher control = app.context().getBean(CommandDispatcher.class);
        Awaitility.await("the misaddressed command was read").atMost(PATIENCE)
                .until(() -> control.ignored() >= 1);
        assertThat(control.succeeded()).isEqualTo(2);
        assertThat(control.failed()).isZero();
    }

    @Test
    @Order(6)
    @DisplayName("the application stops cleanly: the connector, the control subscription and the alerts sink all let go")
    void stopsCleanly() {
        long published = app.connector(CONNECTOR).published();
        assertThat(published).isPositive();

        app.close();

        assertThat(app.context().isActive()).isFalse();
        // The clients are gone from the server's point of view: the same names log on again.
        Awaitility.await("the application's client names are free").atMost(PATIENCE)
                .untilAsserted(() -> {
                    Client again = AmpsSow.connect(server.port(), "json",
                            "amps-connectors-" + APPLICATION + "-alerts");
                    again.close();
                });
    }

    /** Everything the alerts topic has carried so far, replayed from the epoch. */
    private List<String> alerts() throws Exception {
        return AmpsSow.replay(json, ALERTS, REPLAY_IDLE);
    }
}
