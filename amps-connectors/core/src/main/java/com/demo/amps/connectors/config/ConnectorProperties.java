package com.demo.amps.connectors.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * One upstream feed bridged onto one AMPS topic.
 *
 * <p>An {@code amps-connectors} application runs one or more of these, each with its own
 * source connection, its own pipeline and its own AMPS client. The five blocks read in the
 * order the record travels: {@link #getSource() source} produces it, {@link #getFilter()
 * filter} decides whether to keep it, {@link #getTransforms() transforms} reshape it, and
 * {@link #getAmps() amps} says where and how it is published.
 */
public class ConnectorProperties {

    /** SOH, the FIX/NVFIX field separator every dictionary assumes. */
    public static final char SOH = (char) 0x01;

    /** Connector name; used in logs, in the AMPS client name, and must be unique. */
    @NotBlank
    private String name;

    /** Set {@code false} to keep a connector configured but not started. */
    private boolean enabled = true;

    /** Wire format of the source payload -- what the decoder reads. */
    @NotNull
    private SourceFormat format = SourceFormat.JSON;

    /**
     * Field separator for {@link SourceFormat#FIX} and {@link SourceFormat#NVFIX} payloads.
     *
     * <p>SOH by default, which is what a FIX session actually puts on the wire. A feed that
     * writes {@code |} instead (a log replay, a file drop, a test fixture) says so here; the
     * YAML escape for the code point is the way to name a control character in a config file,
     * because a literal one does not survive an editor, a diff or a copy-paste.
     */
    private char fieldSeparator = SOH;

    /** Where the records come from. */
    @Valid
    @NotNull
    private SourceProperties source = new SourceProperties();

    /** Optional: which records are worth publishing. Absent means all of them. */
    @Valid
    private FilterProperties filter;

    /** Reshaping steps, applied in order between the filter and the key. Usually empty. */
    @Valid
    @NotNull
    private List<TransformStep> transforms = new ArrayList<>();

    /** Where the records go: the topic, the message type, the key and the batching. */
    @Valid
    @NotNull
    private AmpsTargetProperties amps = new AmpsTargetProperties();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SourceFormat getFormat() {
        return format;
    }

    public void setFormat(SourceFormat format) {
        this.format = format;
    }

    public char getFieldSeparator() {
        return fieldSeparator;
    }

    public void setFieldSeparator(char fieldSeparator) {
        this.fieldSeparator = fieldSeparator;
    }

    public SourceProperties getSource() {
        return source;
    }

    public void setSource(SourceProperties source) {
        this.source = source;
    }

    public FilterProperties getFilter() {
        return filter;
    }

    public void setFilter(FilterProperties filter) {
        this.filter = filter;
    }

    public List<TransformStep> getTransforms() {
        return transforms;
    }

    public void setTransforms(List<TransformStep> transforms) {
        this.transforms = transforms == null ? new ArrayList<>() : transforms;
    }

    public AmpsTargetProperties getAmps() {
        return amps;
    }

    public void setAmps(AmpsTargetProperties amps) {
        this.amps = amps;
    }

    @Override
    public String toString() {
        return "connector '" + name + "' (" + format + " " + source.describe() + " -> "
                + amps.getMessageType() + " " + amps.getTopic() + ")";
    }
}
