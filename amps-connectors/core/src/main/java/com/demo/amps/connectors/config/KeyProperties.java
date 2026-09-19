package com.demo.amps.connectors.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * How a record's SOW key is determined -- and, crucially, <em>by whom</em>.
 *
 * <p>{@link Mode} is the setting that has to agree with the server's topic definition, because
 * AMPS will not let both ends decide:
 *
 * <ul>
 *   <li>{@link Mode#SERVER} -- the topic is declared with a {@code <Key>} element and AMPS
 *       derives the key from the payload itself. The connector sends no SowKey; what it must
 *       do instead is make sure every field in {@link #getFields()} survives the transforms,
 *       because a payload missing one of them is a record AMPS cannot key, and it is rejected
 *       rather than published under a wrong key. That is also why {@code fields} is required
 *       in this mode: without it the connector has nothing to check.</li>
 *   <li>{@link Mode#PUBLISHER} -- the topic is declared <em>without</em> a {@code <Key>} and the
 *       connector sends the key as the SowKey header. {@code fields} joined by
 *       {@link #getSeparator()} is the key; leave it empty and the source's own key is used
 *       ({@code SourceRecord.key()}), which is what a Kafka message key or a JDBC key-column
 *       set is for.</li>
 * </ul>
 *
 * <p>The mode also decides what a delete looks like: PUBLISHER deletes by key
 * ({@code sowDeleteByKeys}), SERVER has to express the same thing as a filter built from the
 * key fields found in the payload.
 *
 * <p><strong>The trap {@link Mode#PUBLISHER} exists to avoid.</strong> AMPS does not reject a
 * publish that carries no SowKey onto a SOW topic declared without a {@code <Key>}: measured
 * against 5.3.5, the record is filed under a sentinel key ({@code 18446744073709551615}) and
 * every subsequent keyless publish <em>overwrites that same record</em>. A whole feed can
 * therefore collapse onto one SOW row while every log line and every counter says the
 * connector is healthy. Nothing downstream can detect it, so it is refused at both ends: the
 * validator will not accept PUBLISHER mode unless {@code fields} or a key-bearing source
 * (kafka, or jdbc with key-columns) can supply a key, and at runtime a record whose key cannot
 * be determined is counted as rejected and never published.
 */
public class KeyProperties {

    /** Which end of the connection computes the SOW key. */
    public enum Mode {
        /** The topic's {@code <Key>} derives it from the payload. */
        SERVER,
        /** The connector sends it as the SowKey header. */
        PUBLISHER
    }

    /** The fields whose values make up the key, in this order. */
    @NotNull
    private List<String> fields = new ArrayList<>();

    /** What joins the field values into one key string. */
    @NotBlank
    private String separator = "|";

    @NotNull
    private Mode mode = Mode.SERVER;

    public List<String> getFields() {
        return fields;
    }

    public void setFields(List<String> fields) {
        this.fields = fields == null ? new ArrayList<>() : fields;
    }

    public String getSeparator() {
        return separator;
    }

    public void setSeparator(String separator) {
        this.separator = separator;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }
}
