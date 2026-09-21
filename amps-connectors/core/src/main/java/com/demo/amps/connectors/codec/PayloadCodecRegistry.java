package com.demo.amps.connectors.codec;

import com.demo.amps.connectors.decode.RecordDecoder;
import com.demo.amps.connectors.encode.PayloadEncoder;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link PayloadCodec} beans an application registered, by {@link PayloadType}.
 *
 * <p>Consulted only for a <em>set</em> type. The text formats -- JSON, FIX, NVFIX and TEXT --
 * are the {@code 0/0} path and are built by {@code RecordDecoderFactory} and
 * {@code PayloadEncoderFactory} from the connector's {@code format} and {@code message-type},
 * exactly as before payloads were typed; this registry never sees them and refuses to hold a
 * codec that claims to.
 *
 * <p>Resolution is strict for the same reason {@code TransformRegistry}'s is: a record tagged
 * with a type nobody registered, or a target configured for one, is a missing dependency, and
 * publishing its {@code toString()} instead would look like a working connector. The message
 * lists what <em>is</em> registered, because the answer is usually in that list.
 */
public final class PayloadCodecRegistry {

    private static final PayloadCodecRegistry EMPTY = new PayloadCodecRegistry(List.of());

    private final Map<PayloadType, PayloadCodec> codecs;

    /**
     * @param codecs the application's codec beans; each must name a distinct, set type
     * @throws IllegalArgumentException if a codec names {@link PayloadType#UNSET} or no type,
     *     or two codecs name the same type -- a record could not know which one decodes it
     */
    public PayloadCodecRegistry(Collection<? extends PayloadCodec> codecs) {
        Map<PayloadType, PayloadCodec> byType = new LinkedHashMap<>();
        for (PayloadCodec codec : codecs) {
            PayloadType type = codec.type();
            if (type == null || !type.isSet()) {
                throw new IllegalArgumentException(codec.getClass().getName()
                        + " names payload type " + type + ", but 0/0 is the text default the "
                        + "connector's format and message-type decide, not a codec");
            }
            PayloadCodec duplicate = byType.putIfAbsent(type, codec);
            if (duplicate != null) {
                throw new IllegalArgumentException("payload type " + type + " is claimed by both "
                        + duplicate.getClass().getName() + " and " + codec.getClass().getName()
                        + "; a record could not know which one decodes it");
            }
        }
        this.codecs = Collections.unmodifiableMap(byType);
    }

    /** A registry with nothing in it: every set type is unknown. What a text-only pipeline uses. */
    public static PayloadCodecRegistry empty() {
        return EMPTY;
    }

    /**
     * The decoder for a set type.
     *
     * @param type a set payload type
     * @return the codec's decoder
     * @throws IllegalStateException naming the type and what is registered, if no codec
     *     decodes it
     */
    public RecordDecoder decoder(PayloadType type) {
        PayloadCodec codec = codecs.get(type);
        RecordDecoder decoder = codec == null ? null : codec.decoder();
        if (decoder == null) {
            throw new IllegalStateException("no codec decodes payload type " + type
                    + "; registered: " + codecs.keySet());
        }
        return decoder;
    }

    /**
     * The encoder for a set type.
     *
     * @param type a set payload type
     * @return the codec's encoder
     * @throws IllegalStateException naming the type and what is registered, if no codec
     *     encodes it
     */
    public PayloadEncoder encoder(PayloadType type) {
        PayloadCodec codec = codecs.get(type);
        PayloadEncoder encoder = codec == null ? null : codec.encoder();
        if (encoder == null) {
            throw new IllegalStateException("no codec encodes payload type " + type
                    + "; registered: " + codecs.keySet());
        }
        return encoder;
    }

    /** Whether a codec decodes this type. Used by the validator without resolving anything. */
    public boolean canDecode(PayloadType type) {
        PayloadCodec codec = codecs.get(type);
        return codec != null && codec.decoder() != null;
    }

    /** Whether a codec encodes this type. Used by the validator without resolving anything. */
    public boolean canEncode(PayloadType type) {
        PayloadCodec codec = codecs.get(type);
        return codec != null && codec.encoder() != null;
    }

    /** The registered types, in registration order, for error messages and the status line. */
    public Set<PayloadType> types() {
        return codecs.keySet();
    }

    @Override
    public String toString() {
        return "PayloadCodecRegistry" + codecs.keySet();
    }
}
