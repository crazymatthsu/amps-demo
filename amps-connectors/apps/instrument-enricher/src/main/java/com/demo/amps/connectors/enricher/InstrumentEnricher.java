package com.demo.amps.connectors.enricher;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.decode.Fields;
import com.demo.amps.connectors.resource.jdbc.JdbcLookupTable;
import com.demo.amps.connectors.source.InboundRecord;
import com.demo.amps.connectors.transform.RecordTransform;
import com.demo.amps.connectors.transform.TransformContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The example {@link RecordTransform}: looks the record's symbol up in the instruments
 * table and writes what it finds into the record -- by default the SEDOL as tag {@code 48},
 * {@code 22=2} to say that is what {@code 48} holds, and the currency as tag {@code 15}.
 *
 * <p>This class is the whole of what a code transform costs, and the shape is worth
 * reading as a template. It holds a resource rather than a connection: the
 * {@link JdbcLookupTable} is found by name in the {@code ResourceRegistry} <em>once</em>, in
 * {@link EnricherConfiguration}, so a misspelled {@code enricher.resource} fails the
 * application at boot rather than every record at runtime. It is stateless apart from two
 * counters, because one instance serves every connector that names it and runs on each
 * source's own reader thread. And it never writes into the map it is given: every outcome
 * is a fresh copy taken through {@link Fields#copy}, which is the contract that lets the
 * chain hand each step the previous step's result without defensive copying of its own --
 * and, because {@code Fields.copy} copies a typed record's {@code FieldView} through the
 * view's own {@code copy()}, the contract that keeps a protobuf or Thrift order an object
 * rather than flattening it into a map on its way through this transform.
 *
 * <p>Three outcomes, and what each says about the record:
 *
 * <ul>
 *   <li><strong>Not this transform's business.</strong> A {@code DELETE} record (a Kafka
 *       tombstone carries no symbol, and the removal it addresses needs its key fields, not
 *       an enrichment) and a record with no symbol tag pass through unchanged, uncounted
 *       and unalerted.</li>
 *   <li><strong>A hit.</strong> Every {@code set} entry takes the named column of the row --
 *       matched to the label the driver reports, ignoring case, because PostgreSQL reports
 *       an unquoted column in lower case and H2 in upper case and the configuration should
 *       not have to know which -- and every {@code literals} entry takes its constant. A
 *       column the query did not return is warned about once and left unwritten; a NULL
 *       column is silently left unwritten, so the record never carries an empty tag.</li>
 *   <li><strong>A miss.</strong> Counted, raised as {@code UNKNOWN_SYMBOL} with the symbol
 *       and the order id ({@code 11}) as details, and then the {@code on-miss} policy: the
 *       unenriched copy ({@code PASS}) or {@code null} ({@code DROP}, which the connector
 *       counts as {@code dropped}). A table that is <em>unavailable</em> -- never loaded, or
 *       not yet -- follows the same policy but says so differently, as
 *       {@code RESOURCE_UNAVAILABLE}: an operator reading the alerts topic has to be able to
 *       tell "nobody knows this symbol" from "the database is down", and a thousand
 *       {@code UNKNOWN_SYMBOL}s would say the first when the truth is the second.</li>
 * </ul>
 *
 * <p>The alerts name the connector. One bean serves every connector that names it, so the
 * bean itself cannot know which pipeline a record came down -- but the registry asks it, per
 * connector, for a {@linkplain #bind(TransformContext) bound} copy, and that copy stamps the
 * connector on everything it raises. Repeat suppression then collapses a storm per connector
 * and code -- a feed of unknown symbols is one alert per window, not one per record,
 * whichever policy is in force -- and an operator reading the alerts topic can tell which
 * feed it was. Used bare, outside a connector, the same alerts go out with no connector,
 * and the alert manager still stamps the application.
 */
public final class InstrumentEnricher implements RecordTransform {

    private static final Logger log = LoggerFactory.getLogger(InstrumentEnricher.class);

    /** Raised, at WARN, for a symbol the table does not hold. Details: {@code symbol, clOrdId}. */
    public static final String UNKNOWN_SYMBOL = "UNKNOWN_SYMBOL";

    /**
     * Raised, at WARN, when a record needs the table and the table has nothing loaded.
     * Details: {@code resource, symbol, clOrdId}.
     */
    public static final String RESOURCE_UNAVAILABLE = "RESOURCE_UNAVAILABLE";

    /** ClOrdID: the order's own id, and the detail that lets a reader find the order. */
    private static final String CL_ORD_ID = "11";

    private final JdbcLookupTable table;
    private final Alerts alerts;
    private final EnricherProperties properties;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /** Columns already reported as missing from the query, so the log says it once. */
    private final Set<String> missingColumns = ConcurrentHashMap.newKeySet();

    /**
     * @param table the instruments, already looked up by name in the resource registry
     * @param alerts where a miss and an unavailable table are reported
     * @param properties what to look up, what to write, and what a miss does
     */
    public InstrumentEnricher(JdbcLookupTable table, Alerts alerts, EnricherProperties properties) {
        this.table = Objects.requireNonNull(table, "table");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Map<String, Object> apply(InboundRecord record, Map<String, Object> fields) {
        return apply(null, record, fields);
    }

    /**
     * The per-connector view: the same lookup, the same counters, alerts that name the
     * connector. Called by the registry once per connector that names this bean.
     */
    @Override
    public RecordTransform bind(TransformContext context) {
        String connector = context.connectorName().isBlank() ? null : context.connectorName();
        return (record, fields) -> apply(connector, record, fields);
    }

    private Map<String, Object> apply(String connector, InboundRecord record, Map<String, Object> fields) {
        if (record.action() == InboundRecord.Action.DELETE) {
            return copy(fields);
        }
        String symbol = Fields.text(Fields.get(fields, properties.getSymbolTag()));
        if (symbol == null || symbol.isBlank()) {
            return copy(fields);
        }
        if (!table.isAvailable()) {
            misses.incrementAndGet();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("resource", table.name());
            details.put("symbol", symbol);
            details.put("clOrdId", clOrdId(fields));
            raise(connector, Alert.of(Alert.Severity.WARN, RESOURCE_UNAVAILABLE,
                            "resource '" + table.name() + "' has nothing loaded; symbol " + symbol
                                    + (drops() ? " is dropped" : " passes through unenriched"))
                    .withDetails(details));
            return miss(fields);
        }
        Optional<Map<String, Object>> row = table.find(symbol);
        if (row.isEmpty()) {
            misses.incrementAndGet();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("symbol", symbol);
            details.put("clOrdId", clOrdId(fields));
            raise(connector, Alert.of(Alert.Severity.WARN, UNKNOWN_SYMBOL,
                            "symbol " + symbol + " is not in " + table.name()
                                    + (drops() ? "; the order is dropped"
                                            : "; the order passes through unenriched"))
                    .withDetails(details));
            return miss(fields);
        }
        hits.incrementAndGet();
        return enrich(fields, row.get());
    }

    /** A hit: the fields, plus every configured column of the row and every literal. */
    private Map<String, Object> enrich(Map<String, Object> fields, Map<String, Object> row) {
        Map<String, Object> enriched = copy(fields);
        for (Map.Entry<String, String> entry : properties.getSet().entrySet()) {
            String column = entry.getValue();
            if (column == null || column.isBlank()) {
                continue;     // a default entry switched off
            }
            Object value = column(row, column);
            if (value != null) {
                Fields.put(enriched, entry.getKey(), value);
            }
        }
        for (Map.Entry<String, String> entry : properties.getLiterals().entrySet()) {
            String literal = entry.getValue();
            if (literal == null || literal.isBlank()) {
                continue;
            }
            Fields.put(enriched, entry.getKey(), literal);
        }
        return enriched;
    }

    /**
     * A column of the row, by the label the driver reported it under.
     *
     * <p>Exact first; then ignoring case, because the same {@code SELECT sedol} comes back
     * labelled {@code sedol} from PostgreSQL and {@code SEDOL} from H2, and a configuration
     * that enriches everything against one and nothing against the other would be the
     * quiet kind of wrong. A label that is not there under any case is a query that does
     * not return what the configuration names -- said once in the log, and then the tag is
     * simply not written.
     */
    private Object column(Map<String, Object> row, String column) {
        if (row.containsKey(column)) {
            return row.get(column);
        }
        for (Map.Entry<String, Object> candidate : row.entrySet()) {
            if (candidate.getKey().equalsIgnoreCase(column)) {
                return candidate.getValue();
            }
        }
        if (missingColumns.add(column)) {
            log.warn("[{}] column '{}' is not in the query's result set (labels: {}); the tag "
                    + "mapped to it is left unset", table.name(), column, row.keySet());
        }
        return null;
    }

    /** Raise, naming the connector when this is a bound copy that knows one. */
    private void raise(String connector, Alert alert) {
        alerts.raise(connector == null ? alert : alert.withConnector(connector));
    }

    /** The on-miss policy applied to a record that was not enriched. */
    private Map<String, Object> miss(Map<String, Object> fields) {
        return drops() ? null : copy(fields);
    }

    private boolean drops() {
        return properties.getOnMiss() == EnricherProperties.OnMiss.DROP;
    }

    /** The one copy: a {@code LinkedHashMap} of a plain map, the view's own copy of a typed one. */
    private static Map<String, Object> copy(Map<String, Object> fields) {
        return Fields.copy(fields);
    }

    private static String clOrdId(Map<String, Object> fields) {
        return Fields.text(Fields.get(fields, CL_ORD_ID));
    }

    /** Records whose symbol was found and enriched. */
    public long hits() {
        return hits.get();
    }

    /** Records that left unenriched, or were dropped: unknown symbols and an unavailable table. */
    public long misses() {
        return misses.get();
    }

    @Override
    public String toString() {
        return "InstrumentEnricher[" + table.name() + " hits=" + hits.get()
                + " misses=" + misses.get() + " on-miss=" + properties.getOnMiss() + "]";
    }
}
