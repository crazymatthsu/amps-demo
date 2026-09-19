package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.config.AmpsTargetProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.KeyProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.decode.RecordDecoder;
import com.demo.amps.connectors.decode.RecordDecoderFactory;
import com.demo.amps.connectors.encode.PayloadEncoder;
import com.demo.amps.connectors.encode.PayloadEncoderFactory;
import com.demo.amps.connectors.filter.RecordFilter;
import com.demo.amps.connectors.source.SourceRecord;
import com.demo.amps.connectors.transform.TransformChain;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One connector's record processing, as a plain function: a {@link SourceRecord} in, a
 * {@link PublishRequest} or {@code null} out.
 *
 * <p>Decode, filter, transform, key, encode -- all of it, with no Spring, no threads and no
 * AMPS, which is what makes the interesting behaviour testable without any of those. The
 * pipeline runs on the source's reader thread (and a TCP connector in {@code LISTEN} mode has
 * one per client), so it holds nothing mutable but its counters.
 *
 * <p>Five counters, because "the topic has fewer records than the feed" has five different
 * causes and they need telling apart:
 *
 * <ul>
 *   <li>{@link #rejected()} -- the record could not be decoded, keyed or encoded. A failure</li>
 *   <li>{@link #filtered()} -- the filter said no. Working as configured</li>
 *   <li>{@link #dropped()} -- a transform said no. Also working as configured</li>
 *   <li>{@link #ignoredDeletes()} -- a removal on a connector with nothing to remove from</li>
 *   <li>{@link #published()} -- requests produced, which is what should reach AMPS</li>
 * </ul>
 *
 * <p>Two decisions worth knowing about deletes. A {@code DELETE} whose payload is empty
 * <em>skips the filter</em>: a Kafka tombstone carries no fields, so any rule at all would
 * refuse it and the SOW record it was meant to remove would live forever. And a delete still
 * goes through the transforms, because its key fields come out of the same namespace an
 * upsert's do -- a connector that derives a key field has to derive it for removals too.
 */
public final class RecordPipeline {

    private static final Logger log = LoggerFactory.getLogger(RecordPipeline.class);

    private final String name;
    private final String topic;
    private final RecordDecoder decoder;
    private final RecordFilter filter;
    private final TransformChain transforms;
    private final KeyExtractor keys;
    private final PayloadEncoder encoder;
    private final AmpsTargetProperties target;
    private final boolean passthrough;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong filtered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong ignoredDeletes = new AtomicLong();

    /**
     * Compile the connector's configuration into a pipeline.
     *
     * <p>Everything that can fail on a bad configuration -- a regular expression, a SpEL
     * expression, an unknown transform bean, an unusable message type -- fails here, at
     * connector start, rather than on the first record.
     *
     * @param connector the connector configuration
     * @param registry the application's transform beans, for {@code bean:} steps
     */
    public RecordPipeline(ConnectorProperties connector, TransformRegistry registry) {
        this.name = connector.getName();
        this.target = connector.getAmps();
        this.topic = target.getTopic();
        this.decoder = RecordDecoderFactory.create(connector);
        this.filter = connector.getFilter() == null
                ? null
                : new RecordFilter(connector.getFilter());
        this.transforms = TransformChain.of(connector.getTransforms(), registry);
        this.keys = target.getKey() == null ? null : new KeyExtractor(target.getKey());
        // The connector's one field separator serves both ends: a feed read as `|`-delimited
        // FIX is published as `|`-delimited FIX, which is what keeps passthrough honest --
        // encoding to a separator the original did not use would make the two paths produce
        // different bytes for the same record.
        this.encoder = PayloadEncoderFactory.create(
                target.getMessageType(), connector.getFieldSeparator());
        this.passthrough = passthrough(connector);
    }

    /** Whether the original payload bytes are published unchanged. */
    private static boolean passthrough(ConnectorProperties connector) {
        AmpsTargetProperties target = connector.getAmps();
        return switch (target.getPassthrough()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> connector.getTransforms().isEmpty()
                    && matches(connector.getFormat(), target.getMessageType());
        };
    }

    /** Whether the source format and the message type are the same wire format. */
    private static boolean matches(SourceFormat format, String messageType) {
        String type = messageType == null ? "" : messageType.toLowerCase(Locale.ROOT);
        return switch (format) {
            case JSON -> "json".equals(type);
            case FIX -> "fix".equals(type);
            case NVFIX -> "nvfix".equals(type);
            // TEXT decodes to a field this framework invented, so the original line is never
            // what the topic should carry.
            case TEXT -> false;
        };
    }

    /**
     * Turn one source record into the AMPS command it deserves.
     *
     * @param record the record as the source delivered it
     * @return the request to batch, or {@code null} when the record is not published --
     *     rejected, filtered, dropped or an ignored removal, each of them counted
     */
    public PublishRequest apply(SourceRecord record) {
        received.incrementAndGet();
        try {
            return record.action() == SourceRecord.Action.DELETE
                    ? delete(record)
                    : upsert(record);
        } catch (RuntimeException e) {
            long count = rejected.incrementAndGet();
            if (count <= 10 || count % 1_000 == 0) {
                log.warn("[{}] rejected record #{}: {}", name, count, e.getMessage());
            }
            return null;
        }
    }

    private PublishRequest upsert(SourceRecord record) {
        Map<String, Object> fields = decoder.decode(record.data());
        if (filter != null && !filter.accepts(fields)) {
            filtered.incrementAndGet();
            return null;
        }
        fields = transforms.apply(record, fields);
        if (fields == null) {
            dropped.incrementAndGet();
            return null;
        }
        String sowKey = keys == null ? null : keys.key(record, fields);
        String data = passthrough ? record.data() : encoder.encode(fields);
        Command command = target.getCommand() == AmpsTargetProperties.Command.DELTA_PUBLISH
                ? Command.DELTA_PUBLISH
                : Command.PUBLISH;
        published.incrementAndGet();
        return PublishRequest.publish(topic, command, data, sowKey, record);
    }

    private PublishRequest delete(SourceRecord record) {
        if (target.getOnDelete() == AmpsTargetProperties.OnDelete.IGNORE) {
            // A journal topic has nothing to remove from. Counted so a feed that turns out to
            // be mostly removals is visible rather than merely quiet.
            ignoredDeletes.incrementAndGet();
            return null;
        }
        Map<String, Object> fields = decodeQuietly(record);
        // A tombstone carries no fields, and a filter over fields it does not have would
        // refuse every one of them -- leaving the records it was meant to remove in the SOW
        // forever. A delete that DOES carry a payload is filtered like any other record.
        if (!fields.isEmpty() && filter != null && !filter.accepts(fields)) {
            filtered.incrementAndGet();
            return null;
        }
        Map<String, Object> transformed = transforms.apply(record, fields);
        if (transformed == null) {
            dropped.incrementAndGet();
            return null;
        }
        if (keys != null && keys.mode() == KeyProperties.Mode.SERVER) {
            String deleteFilter = keys.deleteFilter(transformed);
            if (deleteFilter == null) {
                // The payload does not carry the key fields, so there is no way to say which
                // record to remove. Counted and dropped rather than deleting something else.
                dropped.incrementAndGet();
                log.debug("[{}] a removal carried none of the key fields, so nothing was deleted",
                        name);
                return null;
            }
            published.incrementAndGet();
            return PublishRequest.deleteByFilter(topic, deleteFilter, record);
        }
        String sowKey = sowKeyForDelete(record, transformed);
        if (sowKey == null) {
            dropped.incrementAndGet();
            log.debug("[{}] a removal carried no usable key, so nothing was deleted", name);
            return null;
        }
        published.incrementAndGet();
        return PublishRequest.deleteByKey(topic, sowKey, record);
    }

    /** A delete's SowKey: the configured fields if it carries them, else the source's own. */
    private String sowKeyForDelete(SourceRecord record, Map<String, Object> fields) {
        if (keys != null) {
            try {
                return keys.key(record, fields);
            } catch (IllegalArgumentException e) {
                // A removal is allowed to be thinner than an upsert: a tombstone with the
                // source's key and no body is the normal shape, so fall back to it.
                return record.key();
            }
        }
        return record.key();
    }

    /**
     * A removal may carry no body at all -- the source identifies it by its own key -- so
     * decode what is there and never fail on what is not.
     */
    private Map<String, Object> decodeQuietly(SourceRecord record) {
        if (record.data() == null || record.data().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return decoder.decode(record.data());
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The connector's name, for log lines. */
    public String name() {
        return name;
    }

    /** Records handed over by the source. */
    public long received() {
        return received.get();
    }

    /** Requests produced, i.e. records the pipeline decided should reach AMPS. */
    public long published() {
        return published.get();
    }

    /** Records that could not be decoded, keyed or encoded. */
    public long rejected() {
        return rejected.get();
    }

    /** Records the filter said no to. */
    public long filtered() {
        return filtered.get();
    }

    /** Records a transform said no to, and removals with no usable key. */
    public long dropped() {
        return dropped.get();
    }

    /** Removals on a connector configured {@code on-delete: IGNORE}. */
    public long ignoredDeletes() {
        return ignoredDeletes.get();
    }
}
