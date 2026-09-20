package com.demo.amps.connectors.enricher;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The example application's own settings: which resource holds the instruments, which FIX
 * tag carries the symbol, what a hit writes, and what a miss does.
 *
 * <pre>{@code
 * enricher:
 *   resource: instruments                # the AppResource, a JdbcLookupTable, by name
 *   symbol-tag: "55"                     # the field looked up (Symbol)
 *   set: { "48": sedol, "15": currency } # FIX tag <- column of the row that was found
 *   literals: { "22": "2" }              # FIX tag <- constant (SecurityIDSource 2 = SEDOL)
 *   on-miss: PASS                        # PASS | DROP
 * }</pre>
 *
 * <p>Its own prefix rather than a corner of {@code amps-connectors:} because that tree is
 * the framework's and this is the application's: what an enricher writes is a decision the
 * framework has no opinion on, and a second application under {@code apps/} would have a
 * block of its own with settings of its own. The defaults are the FIX way of saying "this
 * order's instrument is the SEDOL {@code 48}, and {@code 22=2} is how you know it is a
 * SEDOL", with the currency beside it; the map keys are tag <em>numbers</em> because the
 * record is published as {@code fix}, and the encoder refuses a field that is not a tag.
 *
 * <p>{@link #getSet() set} and {@link #getLiterals() literals} are merged with these
 * defaults, the way Spring Boot binds any map property over an existing one: an entry in
 * the configuration adds to or overrides a default entry rather than replacing the whole
 * map. To remove a default, set its value blank ({@code "15": ""}); the enricher skips a
 * blank column or literal.
 *
 * <p>{@link OnMiss#PASS} is the default because an order whose symbol nobody knows is still
 * an order: it reaches the topic unenriched, counted and alerted ({@code UNKNOWN_SYMBOL}),
 * and a reader that needs the SEDOL can tell it is missing. {@link OnMiss#DROP} is for a
 * topic whose contract is "every record is enriched", where a half record would be worse
 * than none.
 */
@ConfigurationProperties("enricher")
@Validated
public class EnricherProperties {

    /** What happens to a record whose symbol is not in the table (or the table is down). */
    public enum OnMiss {
        /** Publish it as it came, counted and alerted. */
        PASS,
        /** Discard it, counted (as {@code dropped}) and alerted. */
        DROP
    }

    /** The name of the {@code JdbcLookupTable} resource, as {@code resources[].name} spells it. */
    @NotBlank
    private String resource = "instruments";

    /** The field whose value is the lookup key; a FIX tag number for a FIX feed. */
    @NotBlank
    private String symbolTag = "55";

    /** FIX tag to write, mapped to the column of the found row whose value it takes. */
    @NotNull
    private Map<String, String> set = defaultSet();

    /** FIX tag to write, mapped to the constant it takes on every hit. */
    @NotNull
    private Map<String, String> literals = defaultLiterals();

    /** What a miss does. */
    @NotNull
    private OnMiss onMiss = OnMiss.PASS;

    private static Map<String, String> defaultSet() {
        Map<String, String> set = new LinkedHashMap<>();
        set.put("48", "sedol");       // SecurityID
        set.put("15", "currency");    // Currency
        return set;
    }

    private static Map<String, String> defaultLiterals() {
        Map<String, String> literals = new LinkedHashMap<>();
        literals.put("22", "2");      // SecurityIDSource: 2 = SEDOL
        return literals;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getSymbolTag() {
        return symbolTag;
    }

    public void setSymbolTag(String symbolTag) {
        this.symbolTag = symbolTag;
    }

    public Map<String, String> getSet() {
        return set;
    }

    public void setSet(Map<String, String> set) {
        this.set = set == null ? new LinkedHashMap<>() : new LinkedHashMap<>(set);
    }

    public Map<String, String> getLiterals() {
        return literals;
    }

    public void setLiterals(Map<String, String> literals) {
        this.literals = literals == null ? new LinkedHashMap<>() : new LinkedHashMap<>(literals);
    }

    public OnMiss getOnMiss() {
        return onMiss;
    }

    public void setOnMiss(OnMiss onMiss) {
        this.onMiss = onMiss;
    }
}
