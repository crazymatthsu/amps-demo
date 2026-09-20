package com.demo.amps.connectors.enricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.JdbcResourceProperties;
import com.demo.amps.connectors.config.ResourceProperties;
import com.demo.amps.connectors.encode.FixEncoder;
import com.demo.amps.connectors.resource.jdbc.JdbcLookupTable;
import com.demo.amps.connectors.source.SourceRecord;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The enricher over a real {@link JdbcLookupTable}, loaded from the module's own
 * {@code sql/instruments.sql} into an in-memory H2 -- no mock table, so what is asserted
 * is what the row looks like when a driver returns it: the labels in the driver's case,
 * the values as {@code JdbcValues} converts them.
 *
 * <p>What is worth asserting is every decision the transform makes: what a hit writes and
 * in which order (the encoder writes the map in order, and every key has to be a tag), what
 * a miss does under each policy and what it says about itself, which records are none of
 * its business, and that none of it ever writes into the map it was given.
 */
class InstrumentEnricherTest {

    private static final String RESOURCE = "instruments";
    private static final String QUERY = "SELECT symbol, sedol, isin, currency, ric FROM instruments";

    private final List<Alert> alerts = new CopyOnWriteArrayList<>();
    private JdbcLookupTable table;

    @BeforeEach
    void loadTheTable() throws Exception {
        table = table(InstrumentsDatabase.seeded());
        table.start();
        assertThat(table.isAvailable()).as("the seed loaded").isTrue();
        assertThat(table.size()).isEqualTo(InstrumentsDatabase.SEEDED.size());
    }

    @AfterEach
    void stopTheTable() {
        if (table != null) {
            table.stop();
        }
    }

    // ---- fixtures ----------------------------------------------------------------------

    /** Timer off: the tests reload nothing, and a thread per test is noise. */
    private static JdbcLookupTable table(String url) {
        ResourceProperties resource = new ResourceProperties();
        resource.setName(RESOURCE);
        JdbcResourceProperties jdbc = new JdbcResourceProperties();
        jdbc.setUrl(url);
        jdbc.setQuery(QUERY);
        jdbc.setKeyColumns(List.of("symbol"));
        jdbc.setReloadInterval(Duration.ZERO);
        resource.setJdbc(jdbc);
        return new JdbcLookupTable(resource, Alerts.none());
    }

    private InstrumentEnricher enricher(EnricherProperties.OnMiss onMiss) {
        EnricherProperties properties = new EnricherProperties();
        properties.setOnMiss(onMiss);
        return new InstrumentEnricher(table, alerts::add, properties);
    }

    private InstrumentEnricher enricher() {
        return enricher(EnricherProperties.OnMiss.PASS);
    }

    /** A new order for {@code symbol}, as the FIX decoder would hand it over: tags in wire order. */
    private static Map<String, Object> order(String clOrdId, String symbol) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("8", "FIX.4.2");
        fields.put("35", "D");
        fields.put("11", clOrdId);
        fields.put("55", symbol);
        fields.put("54", "1");
        fields.put("38", "100");
        fields.put("40", "2");
        fields.put("44", "10.5");
        return fields;
    }

    private static SourceRecord upsert() {
        return SourceRecord.of("ignored by the transform");
    }

    private List<Alert> alerts(String code) {
        return alerts.stream().filter(alert -> alert.code().equals(code)).toList();
    }

    // ---- hits ---------------------------------------------------------------------------

    @Test
    @DisplayName("a known symbol gets 48 (SEDOL), 15 (currency) and 22=2 appended, in that order, in a new map")
    void hitWritesSedolCurrencyAndIdSource() {
        InstrumentEnricher enricher = enricher();
        Map<String, Object> input = order("ORD-1", "K-0");

        Map<String, Object> enriched = enricher.apply(upsert(), input);

        assertThat(enriched).isNotSameAs(input);
        // The original fields first, untouched and in wire order; then the set columns in
        // configuration order (48 before 15), then the literals. The map order is the
        // encoder's output order, so it is part of what the transform promises.
        assertThat(enriched).containsExactly(
                entry("8", "FIX.4.2"), entry("35", "D"), entry("11", "ORD-1"), entry("55", "K-0"),
                entry("54", "1"), entry("38", "100"), entry("40", "2"), entry("44", "10.5"),
                entry("48", "B0YQ5W0"), entry("15", "GBP"), entry("22", "2"));
        assertThat(enricher.hits()).isEqualTo(1);
        assertThat(enricher.misses()).isZero();
        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("every key the enricher writes is a tag number, so the FIX encoder accepts the result")
    void enrichedRecordEncodesAsFix() {
        Map<String, Object> enriched = enricher().apply(upsert(), order("ORD-1", "K-3"));

        String fix = new FixEncoder(ConnectorProperties.SOH).encode(enriched);

        assertThat(fix).endsWith("48=B3YQ5W315=JPY22=2");
    }

    @Test
    @DisplayName("every seeded symbol resolves to its own row, matched by the driver's label whatever its case")
    void everySeededSymbolIsFound() {
        InstrumentEnricher enricher = enricher();
        // H2 reports the unquoted columns of the seed file in upper case (SEDOL), the
        // configuration names them in lower case (sedol), and the record still gets both.
        assertThat(table.find("K-0").orElseThrow().keySet()).contains("SEDOL", "CURRENCY");
        for (InstrumentsDatabase.Instrument instrument : InstrumentsDatabase.SEEDED) {
            Map<String, Object> enriched =
                    enricher.apply(upsert(), order("ORD-" + instrument.symbol(), instrument.symbol()));
            assertThat(enriched).as(instrument.symbol())
                    .containsEntry("48", instrument.sedol())
                    .containsEntry("15", instrument.currency())
                    .containsEntry("22", "2");
        }
        assertThat(enricher.hits()).isEqualTo(InstrumentsDatabase.SEEDED.size());
    }

    @Test
    @DisplayName("an existing tag is overwritten in place rather than appended a second time")
    void existingTagIsOverwrittenInPlace() {
        Map<String, Object> input = order("ORD-1", "K-1");
        input.put("15", "XXX");    // the feed's own (wrong) currency, last on the wire

        Map<String, Object> enriched = enricher().apply(upsert(), input);

        // 15 keeps the feed's position and takes the table's value; only 48 and 22 are new.
        assertThat(enriched).containsEntry("15", "USD");
        assertThat(List.copyOf(enriched.keySet()))
                .containsExactly("8", "35", "11", "55", "54", "38", "40", "44", "15", "48", "22");
    }

    @Test
    @DisplayName("a column the query does not return, or a blank mapping, leaves its tag unwritten")
    void unknownOrBlankColumnLeavesTheTagUnset() {
        EnricherProperties properties = new EnricherProperties();
        Map<String, String> set = new LinkedHashMap<>();
        set.put("48", "sedol");
        set.put("15", "");               // the way a default mapping is switched off
        set.put("5002", "exchange");     // not a column of the query
        set.put("5003", "isin");         // is one
        properties.setSet(set);
        InstrumentEnricher enricher = new InstrumentEnricher(table, alerts::add, properties);

        Map<String, Object> enriched = enricher.apply(upsert(), order("ORD-1", "K-2"));

        assertThat(enriched).containsEntry("48", "B2YQ5W2").containsEntry("5003", "DE00B2YQ5W22")
                .containsEntry("22", "2")
                .doesNotContainKeys("15", "5002");
        assertThat(enricher.hits()).isEqualTo(1);
        assertThat(alerts).isEmpty();
    }

    // ---- misses -------------------------------------------------------------------------

    @Test
    @DisplayName("PASS: an unknown symbol passes through as a copy, counted, with UNKNOWN_SYMBOL naming the symbol and the order")
    void missPassesThroughAndAlerts() {
        InstrumentEnricher enricher = enricher(EnricherProperties.OnMiss.PASS);
        Map<String, Object> input = order("ORD-7", "K-5");

        Map<String, Object> result = enricher.apply(upsert(), input);

        assertThat(result).isNotSameAs(input).containsExactlyEntriesOf(input);
        assertThat(enricher.misses()).isEqualTo(1);
        assertThat(enricher.hits()).isZero();
        assertThat(alerts).hasSize(1);
        Alert alert = alerts.get(0);
        assertThat(alert.code()).isEqualTo(InstrumentEnricher.UNKNOWN_SYMBOL);
        assertThat(alert.severity()).isEqualTo(Alert.Severity.WARN);
        assertThat(alert.message()).contains("K-5").contains("instruments");
        assertThat(alert.details()).containsExactly(entry("symbol", "K-5"), entry("clOrdId", "ORD-7"));
        // No connector: a bean step runs in whichever connector names it, and the manager
        // stamps the application. Nothing here should have guessed either.
        assertThat(alert.connector()).isNull();
        assertThat(alert.application()).isNull();
        assertThat(alert.timestamp()).isNull();
    }

    @Test
    @DisplayName("DROP: an unknown symbol returns null, counted and alerted the same way")
    void missDropsWhenConfigured() {
        InstrumentEnricher enricher = enricher(EnricherProperties.OnMiss.DROP);

        assertThat(enricher.apply(upsert(), order("ORD-8", "K-9"))).isNull();

        assertThat(enricher.misses()).isEqualTo(1);
        assertThat(alerts(InstrumentEnricher.UNKNOWN_SYMBOL)).singleElement()
                .satisfies(alert -> {
                    assertThat(alert.message()).contains("dropped");
                    assertThat(alert.details()).containsEntry("symbol", "K-9")
                            .containsEntry("clOrdId", "ORD-8");
                });
    }

    @Test
    @DisplayName("an order with no ClOrdID still alerts, with a null clOrdId detail")
    void missWithoutClOrdIdStillAlerts() {
        Map<String, Object> input = order("ORD-9", "K-5");
        input.remove("11");

        enricher().apply(upsert(), input);

        assertThat(alerts).singleElement().satisfies(alert -> assertThat(alert.details())
                .containsEntry("symbol", "K-5")
                .containsKey("clOrdId")
                .containsEntry("clOrdId", null));
    }

    // ---- not this transform's business ---------------------------------------------------

    @Test
    @DisplayName("a DELETE record passes through unchanged, even with a known symbol")
    void deleteIsLeftAlone() {
        InstrumentEnricher enricher = enricher(EnricherProperties.OnMiss.DROP);
        Map<String, Object> input = order("ORD-1", "K-0");

        Map<String, Object> result =
                enricher.apply(SourceRecord.delete("", "ORD-1"), input);

        assertThat(result).isNotSameAs(input).containsExactlyEntriesOf(input);
        assertThat(enricher.hits()).isZero();
        assertThat(enricher.misses()).isZero();
        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("a record with no symbol tag, or a blank one, passes through unchanged and uncounted")
    void noSymbolIsLeftAlone() {
        InstrumentEnricher enricher = enricher(EnricherProperties.OnMiss.DROP);
        Map<String, Object> without = order("ORD-1", "K-0");
        without.remove("55");
        Map<String, Object> blank = order("ORD-2", " ");

        assertThat(enricher.apply(upsert(), without)).containsExactlyEntriesOf(without);
        assertThat(enricher.apply(upsert(), blank)).containsExactlyEntriesOf(blank);

        assertThat(enricher.hits()).isZero();
        assertThat(enricher.misses()).isZero();
        assertThat(alerts).isEmpty();
    }

    // ---- an unavailable table ------------------------------------------------------------

    @Test
    @DisplayName("a table with nothing loaded raises RESOURCE_UNAVAILABLE, not UNKNOWN_SYMBOL, and applies the policy")
    void unavailableTableFollowsThePolicy() {
        // Never started: exactly the state between the registry building the table and
        // its first successful load, or after a load that failed.
        JdbcLookupTable unloaded = table("jdbc:h2:mem:never-loaded;DB_CLOSE_DELAY=-1");
        assertThat(unloaded.isAvailable()).isFalse();
        EnricherProperties pass = new EnricherProperties();
        EnricherProperties drop = new EnricherProperties();
        drop.setOnMiss(EnricherProperties.OnMiss.DROP);
        InstrumentEnricher passing = new InstrumentEnricher(unloaded, alerts::add, pass);
        InstrumentEnricher dropping = new InstrumentEnricher(unloaded, alerts::add, drop);
        Map<String, Object> input = order("ORD-1", "K-0");

        assertThat(passing.apply(upsert(), input)).isNotSameAs(input).containsExactlyEntriesOf(input);
        assertThat(dropping.apply(upsert(), input)).isNull();

        assertThat(passing.misses()).isEqualTo(1);
        assertThat(dropping.misses()).isEqualTo(1);
        assertThat(alerts(InstrumentEnricher.UNKNOWN_SYMBOL)).isEmpty();
        assertThat(alerts(InstrumentEnricher.RESOURCE_UNAVAILABLE)).hasSize(2)
                .allSatisfy(alert -> {
                    assertThat(alert.severity()).isEqualTo(Alert.Severity.WARN);
                    assertThat(alert.details()).containsExactly(entry("resource", RESOURCE),
                            entry("symbol", "K-0"), entry("clOrdId", "ORD-1"));
                });
        assertThat(alerts(InstrumentEnricher.RESOURCE_UNAVAILABLE))
                .extracting(Alert::message)
                .satisfiesExactly(
                        first -> assertThat(first).contains("unenriched"),
                        second -> assertThat(second).contains("dropped"));
    }

    // ---- the contract ----------------------------------------------------------------------

    @Test
    @DisplayName("the input map is never written to: a hit, a miss, a delete and a record without a symbol all leave it alone")
    void inputIsNeverMutated() {
        InstrumentEnricher enricher = enricher(EnricherProperties.OnMiss.PASS);
        // Unmodifiable: any write into the input would throw rather than pass quietly.
        Map<String, Object> hit = Collections.unmodifiableMap(order("ORD-1", "K-0"));
        Map<String, Object> miss = Collections.unmodifiableMap(order("ORD-2", "K-5"));
        Map<String, Object> noSymbol = new LinkedHashMap<>(order("ORD-3", "K-0"));
        noSymbol.remove("55");
        Map<String, Object> frozen = Collections.unmodifiableMap(noSymbol);

        assertThat(enricher.apply(upsert(), hit)).containsEntry("48", "B0YQ5W0");
        assertThat(enricher.apply(upsert(), miss)).containsExactlyEntriesOf(miss);
        assertThat(enricher.apply(SourceRecord.delete("", "ORD-1"), hit))
                .containsExactlyEntriesOf(hit);
        assertThat(enricher.apply(upsert(), frozen)).containsExactlyEntriesOf(frozen);

        assertThat(hit).doesNotContainKeys("48", "15", "22");
        assertThat(enricher.hits()).isEqualTo(1);
        assertThat(enricher.misses()).isEqualTo(1);
    }

    @Test
    @DisplayName("hits and misses count across records; toString reports them with the policy")
    void countersAccumulate() {
        InstrumentEnricher enricher = enricher(EnricherProperties.OnMiss.DROP);
        for (int i = 0; i < 3; i++) {
            enricher.apply(upsert(), order("ORD-" + i, "K-" + i));
        }
        enricher.apply(upsert(), order("ORD-X", "K-5"));
        enricher.apply(upsert(), order("ORD-Y", "K-6"));

        assertThat(enricher.hits()).isEqualTo(3);
        assertThat(enricher.misses()).isEqualTo(2);
        assertThat(enricher.toString())
                .isEqualTo("InstrumentEnricher[instruments hits=3 misses=2 on-miss=DROP]");
    }

    @Test
    @DisplayName("the constructor refuses a missing table, alerts or properties")
    void constructorRefusesNulls() {
        EnricherProperties properties = new EnricherProperties();
        assertThatThrownBy(() -> new InstrumentEnricher(null, alerts::add, properties))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("table");
        assertThatThrownBy(() -> new InstrumentEnricher(table, null, properties))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("alerts");
        assertThatThrownBy(() -> new InstrumentEnricher(table, alerts::add, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("properties");
    }
}
