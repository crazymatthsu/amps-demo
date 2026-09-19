package com.demo.amps.connectors.decode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decoder for an opaque line: the whole payload becomes the single field {@code text}.
 *
 * <p>The format for a feed with no structure to speak of -- a log tail, a CSV line, a socket
 * that frames on newlines -- where the point of the connector is to get the line onto a topic
 * and let a filter or a transform make sense of it later.
 *
 * <p>There is one consequence worth knowing: a {@code TEXT} connector can never pass its
 * payload through untouched, because {@code text} is a field this decoder invented and the
 * only honest thing to publish is the JSON object carrying it.
 */
public final class TextRecordDecoder implements RecordDecoder {

    /** The single field every text payload decodes to. */
    public static final String FIELD = "text";

    @Override
    public Map<String, Object> decode(String payload) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD, payload == null ? "" : payload);
        return fields;
    }
}
