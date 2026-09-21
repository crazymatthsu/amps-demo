package com.demo.amps.connectors.hazelcast;

import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.demo.amps.connectors.source.InboundRecord;
import com.google.gson.Gson;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a value the cluster delivered becomes on its way to the pipeline -- one rule for a
 * topic message and a map entry alike, because the value is the same kind of thing in both.
 *
 * <p>Three kinds of value, two of which are text already:
 *
 * <table border="1">
 *   <caption>Values as payloads</caption>
 *   <tr><th>the value</th><th>the payload</th><th>the type</th></tr>
 *   <tr><td>{@code String}</td><td>itself -- JSON, FIX, a line, whatever the feed writes</td>
 *       <td>{@link PayloadType#UNSET}: the connector's {@code format} decodes it</td></tr>
 *   <tr><td>{@link HazelcastJsonValue}</td><td>its JSON, which Hazelcast already knows it is</td>
 *       <td>{@link PayloadType#UNSET}</td></tr>
 *   <tr><td>{@link IdentifiedDataSerializable}, with {@code typed-values: OBJECT}</td>
 *       <td>the object itself, untouched</td>
 *       <td>its own {@code factoryId/classId}, for the codec registered under them</td></tr>
 *   <tr><td>anything else -- a {@code Map}, a {@code List}, a POJO, or an
 *       {@code IdentifiedDataSerializable} under {@code typed-values: JSON}</td>
 *       <td>its JSON rendering, by Gson</td><td>{@link PayloadType#UNSET}</td></tr>
 * </table>
 *
 * <p>The rendering is the fallback, and it is the default, because a source cannot see the
 * codec registry: a connector that hands an object through under a pair nobody registered
 * would have every record rejected by the pipeline, whereas JSON of the same object is
 * something {@code format: JSON} can always read. {@code OBJECT} is the operator saying the
 * codec is there. Either way the ids ride along as the {@code factoryId} and {@code classId}
 * attributes, so a rule can tell one class of value from another without decoding it.
 *
 * <p>An {@code IdentifiedDataSerializable} whose ids cannot spell a payload type -- a class id
 * of zero, an id Hazelcast keeps negative for its own classes -- is rendered too, and said
 * once: {@code 0/n} names nothing, and refusing the record would turn a naming quirk into a
 * silent hole in the feed.
 */
final class HazelcastValues {

    private static final Logger log = LoggerFactory.getLogger(HazelcastValues.class);

    /** Thread-safe and stateless; one instance rather than one per value. */
    private static final Gson GSON = new Gson();

    private final String connectorName;
    private final HazelcastSourceProperties.TypedValues mode;

    /** One WARN per source for ids no payload type can spell; the second says nothing new. */
    private final AtomicBoolean warnedAboutIds = new AtomicBoolean(false);

    HazelcastValues(ConnectorProperties connector) {
        this.connectorName = connector.getName();
        this.mode = connector.getSource().getHazelcast().getTypedValues();
    }

    /**
     * An upsert of this value under this key: the object under its type when the mode and
     * the value allow it, else its text under {@link PayloadType#UNSET}. Attributes, seqno
     * and the acknowledger are the subscription's to add.
     *
     * @param value the value from a message, an event or the snapshot
     * @param key the record's key, or {@code null} for a topic message
     * @return the record, before its transport metadata
     */
    InboundRecord upsert(Object value, String key) {
        if (mode == HazelcastSourceProperties.TypedValues.OBJECT
                && value instanceof IdentifiedDataSerializable typed) {
            PayloadType type = typeOf(typed);
            if (type != null) {
                return InboundRecord.of(typed, key).withType(type);
            }
        }
        return InboundRecord.of(text(value), key);
    }

    /** Whether a value is text already, and so passes through without being rendered. */
    static boolean isText(Object value) {
        return value instanceof String || value instanceof HazelcastJsonValue;
    }

    /**
     * A value as text.
     *
     * @param value the value from a message, an event or the snapshot
     * @return a {@code String} as it is, a {@link HazelcastJsonValue}'s JSON, anything else as
     *     Gson renders it; {@code ""} for {@code null}, which only a delete should carry
     */
    static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof HazelcastJsonValue json) {
            return json.getValue();
        }
        return GSON.toJson(value);
    }

    /**
     * Add the ids of an {@link IdentifiedDataSerializable} value to a record's attributes;
     * nothing for any other value.
     *
     * @param value the value from a message, an event or the snapshot
     * @param attributes the record's attributes, written into
     */
    static void describe(Object value, Map<String, String> attributes) {
        if (value instanceof IdentifiedDataSerializable typed) {
            attributes.put(HazelcastRecordSource.ATTRIBUTE_FACTORY_ID,
                    Integer.toString(typed.getFactoryId()));
            attributes.put(HazelcastRecordSource.ATTRIBUTE_CLASS_ID,
                    Integer.toString(typed.getClassId()));
        }
    }

    /** The payload type a value's ids spell, or {@code null} -- said once -- when they cannot. */
    private PayloadType typeOf(IdentifiedDataSerializable typed) {
        try {
            PayloadType type = PayloadType.of(typed.getFactoryId(), typed.getClassId());
            return type.isSet() ? type : null;
        } catch (IllegalArgumentException e) {
            if (warnedAboutIds.compareAndSet(false, true)) {
                log.warn("[{}] {} has ids {}/{}, which no payload type can spell; rendering it "
                                + "as JSON instead ({})", connectorName,
                        typed.getClass().getName(), typed.getFactoryId(), typed.getClassId(),
                        e.getMessage());
            }
            return null;
        }
    }
}
