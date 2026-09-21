package com.demo.amps.connectors.runtime;

import com.demo.amps.connectors.codec.PayloadCodecRegistry;
import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.config.AmpsTargetProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.KeyProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.decode.RecordDecoder;
import com.demo.amps.connectors.decode.RecordDecoderFactory;
import com.demo.amps.connectors.encode.PayloadEncoder;
import com.demo.amps.connectors.encode.PayloadEncoderFactory;
import com.demo.amps.connectors.filter.RecordFilter;
import com.demo.amps.connectors.source.InboundRecord;
import com.demo.amps.connectors.transform.TransformChain;
import com.demo.amps.connectors.transform.TransformContext;
import com.demo.amps.connectors.transform.TransformRegistry;
import com.demo.amps.connectors.transform.rules.RuleSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One connector's record processing, as a plain function: an {@link InboundRecord} in, a
 * {@link MessageContext} -- the record paired with the {@link OutboundRecord} it became --
 * or {@code null} out.
 *
 * <p>Decode, filter, transform, key, encode -- all of it, with no Spring, no threads and no
 * AMPS, which is what makes the interesting behaviour testable without any of those. The
 * pipeline runs on the source's reader thread (and a TCP connector in {@code LISTEN} mode has
 * one per client), so it holds nothing mutable but its counters and a cache of decoders.
 *
 * <p>Which decoder runs is the record's business, not the connector's alone: a record whose
 * {@link InboundRecord#type() type} is set is decoded by the codec registered for it, and
 * one whose type is {@link PayloadType#UNSET} by the connector's {@code format}, so one
 * connector can read a Kafka topic of typed bytes and a text feed with the same
 * configuration. The out side is fixed at construction -- a set {@code amps.payload-type}
 * names the codec that writes the payload, else {@code amps.message-type} names the text
 * encoder -- so a target nobody can write fails the connector's start, not its first record.
 *
 * <p>Five counters, because "the topic has fewer records than the feed" has five different
 * causes and they need telling apart:
 *
 * <ul>
 *   <li>{@link #rejected()} -- the record could not be decoded, keyed or encoded. A failure</li>
 *   <li>{@link #filtered()} -- the filter said no. Working as configured</li>
 *   <li>{@link #dropped()} -- a transform said no. Also working as configured</li>
 *   <li>{@link #ignoredDeletes()} -- a removal on a connector with nothing to remove from</li>
 *   <li>{@link #published()} -- outbound records produced, which is what should reach AMPS</li>
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
    private final SourceFormat format;
    private final RecordDecoder formatDecoder;
    private final PayloadCodecRegistry codecs;
    private final Map<PayloadType, RecordDecoder> decoders = new ConcurrentHashMap<>();
    private final RecordFilter filter;
    private final TransformChain transforms;
    private final KeyExtractor keys;
    private final PayloadType outType;
    private final PayloadEncoder encoder;
    private final AmpsTargetProperties target;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong filtered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong ignoredDeletes = new AtomicLong();

    /**
     * Compile the connector's configuration into a pipeline with no connector behind its
     * transforms and no codecs: {@code bean:} steps resolve, {@code rules:} steps raise
     * nowhere, and every record is text. What a test of the pipeline itself wants.
     *
     * @param connector the connector configuration
     * @param registry the application's transform beans, for {@code bean:} steps
     */
    public RecordPipeline(ConnectorProperties connector, TransformRegistry registry) {
        this(connector, TransformContext.of(registry), PayloadCodecRegistry.empty());
    }

    /**
     * Compile the connector's configuration into a pipeline with no codecs: every record is
     * text, decoded by {@code format} and encoded by {@code message-type}.
     *
     * @param connector the connector configuration
     * @param context the connector's name, the application's transform beans, and where the
     *     {@code rules:} steps raise their alerts
     */
    public RecordPipeline(ConnectorProperties connector, TransformContext context) {
        this(connector, context, PayloadCodecRegistry.empty());
    }

    /**
     * Compile the connector's configuration into a pipeline.
     *
     * <p>Everything that can fail on a bad configuration -- a regular expression, a SpEL
     * expression, an unknown transform bean, a malformed rule, an unusable message type, a
     * payload type no codec writes -- fails here, at connector start, rather than on the
     * first record.
     *
     * @param connector the connector configuration
     * @param context the connector's name, the application's transform beans, and where the
     *     {@code rules:} steps raise their alerts
     * @param codecs the application's codecs, for typed records and a typed target
     * @throws IllegalStateException if {@code amps.payload-type} names a type no registered
     *     codec encodes
     */
    public RecordPipeline(
            ConnectorProperties connector, TransformContext context, PayloadCodecRegistry codecs) {
        this.name = connector.getName();
        this.target = connector.getAmps();
        this.topic = target.getTopic();
        this.format = connector.getFormat();
        this.formatDecoder = RecordDecoderFactory.create(connector);
        this.codecs = Objects.requireNonNull(codecs, "codecs");
        this.filter = connector.getFilter() == null
                ? null
                : new RecordFilter(connector.getFilter());
        this.transforms = TransformChain.of(connector.getTransforms(), context);
        this.keys = target.getKey() == null ? null : new KeyExtractor(target.getKey());
        this.outType = target.getPayloadType().toPayloadType();
        if (outType.isSet()) {
            if (!codecs.canEncode(outType)) {
                throw new IllegalStateException("[" + name + "] amps.payload-type " + outType
                        + " names a type no registered codec encodes; registered: "
                        + codecs.types());
            }
            this.encoder = codecs.encoder(outType);
        } else {
            // The connector's one field separator serves both ends: a feed read as
            // `|`-delimited FIX is published as `|`-delimited FIX, which is what keeps
            // passthrough honest -- encoding to a separator the original did not use would
            // make the two paths produce different bytes for the same record.
            this.encoder = PayloadEncoderFactory.create(
                    target.getMessageType(), connector.getFieldSeparator());
        }
    }

    /**
     * Whether this record's original payload is published unchanged.
     *
     * <p>{@code ALWAYS} and {@code NEVER} are what they say. {@code AUTO} passes through when
     * the payload already is what the target wants -- the record's type is the target's, and
     * for text that means the source format is the message type -- and no transform stands
     * between them. TEXT never matches: it decodes to a field this framework invented, so the
     * original line is never what the topic should carry.
     *
     * <p>Whatever the setting says, only text and bytes can pass through: they ARE a wire
     * form. A typed object (a Hazelcast value handed through under its ids) is not, and
     * publishing it untouched would put {@code String.valueOf(object)} on the topic; it goes
     * through the codec's encoder instead, which for a same-type target builds the wire form
     * straight from the object without a field map in between.
     */
    private boolean passthrough(InboundRecord record) {
        if (!(record.data() instanceof String || record.data() instanceof byte[])) {
            return false;
        }
        return switch (target.getPassthrough()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> transforms.isEmpty() && sameWireFormat(record.type());
        };
    }

    /** Whether a record of this type is already in the target's wire format. */
    private boolean sameWireFormat(PayloadType inType) {
        if (inType.isSet() || outType.isSet()) {
            return inType.equals(outType);
        }
        return matches(format, target.getMessageType());
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

    /** The decoder for a record: the codec its type names, or the connector's format. */
    private RecordDecoder decoderFor(PayloadType type) {
        if (!type.isSet()) {
            return formatDecoder;
        }
        // Cached per type: the registry lookup is cheap, but a record stream of one type
        // should not pay for it a million times. An unregistered type throws out of here,
        // and the record is counted as rejected with the registry's message.
        return decoders.computeIfAbsent(type, codecs::decoder);
    }

    /**
     * Turn one inbound record into the AMPS command it deserves.
     *
     * @param record the record as the source delivered it
     * @return the record and its outbound half, or {@code null} when the record is not
     *     published -- rejected, filtered, dropped or an ignored removal, each of them counted
     */
    public MessageContext apply(InboundRecord record) {
        received.incrementAndGet();
        try {
            OutboundRecord out = record.action() == InboundRecord.Action.DELETE
                    ? delete(record)
                    : upsert(record);
            return out == null ? null : MessageContext.of(record, out);
        } catch (RuntimeException e) {
            long count = rejected.incrementAndGet();
            if (count <= 10 || count % 1_000 == 0) {
                log.warn("[{}] rejected record #{}: {}", name, count, e.getMessage());
            }
            return null;
        }
    }

    private OutboundRecord upsert(InboundRecord record) {
        Map<String, Object> fields = decoderFor(record.type()).decode(record.data());
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
        Object data = passthrough(record) ? record.data() : encoder.encode(fields);
        Command command = target.getCommand() == AmpsTargetProperties.Command.DELTA_PUBLISH
                ? Command.DELTA_PUBLISH
                : Command.PUBLISH;
        published.incrementAndGet();
        return OutboundRecord.publish(topic, command, data, outType, sowKey);
    }

    private OutboundRecord delete(InboundRecord record) {
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
            return OutboundRecord.deleteByFilter(topic, outType, deleteFilter);
        }
        String sowKey = sowKeyForDelete(record, transformed);
        if (sowKey == null) {
            dropped.incrementAndGet();
            log.debug("[{}] a removal carried no usable key, so nothing was deleted", name);
            return null;
        }
        published.incrementAndGet();
        return OutboundRecord.deleteByKey(topic, outType, sowKey);
    }

    /** A delete's SowKey: the configured fields if it carries them, else the source's own. */
    private String sowKeyForDelete(InboundRecord record, Map<String, Object> fields) {
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
    private Map<String, Object> decodeQuietly(InboundRecord record) {
        if (!record.hasData()) {
            return new LinkedHashMap<>();
        }
        try {
            return decoderFor(record.type()).decode(record.data());
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The connector's name, for log lines. */
    public String name() {
        return name;
    }

    /** What the out side writes: the target's {@code payload-type}, or {@link PayloadType#UNSET} for text. */
    public PayloadType outType() {
        return outType;
    }

    /** The {@code rules:} steps, in order, for their per-rule counters. */
    public List<RuleSet> ruleSets() {
        return transforms.ruleSets();
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
