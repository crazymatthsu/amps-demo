package com.demo.amps.qfj2.seqno;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;

/**
 * The checkpoint's JSON shape on the {@code sow/quickfixj/seqno} topic.
 *
 * <pre>
 * {"sessionId":"FIX.4.2:DROPCOPY->VENUE","nextSenderMsgSeqNum":12,
 *  "nextTargetMsgSeqNum":40,"creationTime":"2026-09-17T08:00:00Z",
 *  "updatedAt":"2026-09-17T08:14:03.117Z","source":"dropcopy-primary","revision":57}
 * </pre>
 *
 * <p>Written field by field rather than by reflection: the field names are
 * the topic's contract (the SOW key is {@code /sessionId}), and Gson's
 * reflective path cannot see inside {@link Instant} on a modern JDK anyway.
 * HTML escaping is off so the {@code ->} in a session id is stored as typed.
 */
public final class SeqnoJson {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private SeqnoJson() {
    }

    public static String encode(SeqnoSnapshot snapshot) {
        JsonObject object = new JsonObject();
        object.addProperty("sessionId", snapshot.sessionId());
        object.addProperty("nextSenderMsgSeqNum", snapshot.nextSenderMsgSeqNum());
        object.addProperty("nextTargetMsgSeqNum", snapshot.nextTargetMsgSeqNum());
        object.addProperty("creationTime", snapshot.creationTime().toString());
        object.addProperty("updatedAt", snapshot.updatedAt().toString());
        object.addProperty("source", snapshot.source());
        object.addProperty("revision", snapshot.revision());
        return GSON.toJson(object);
    }

    public static SeqnoSnapshot decode(String json) {
        JsonObject object;
        try {
            object = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a sequence-number checkpoint: " + json, e);
        }
        return new SeqnoSnapshot(
                required(object, "sessionId").getAsString(),
                required(object, "nextSenderMsgSeqNum").getAsInt(),
                required(object, "nextTargetMsgSeqNum").getAsInt(),
                Instant.parse(required(object, "creationTime").getAsString()),
                Instant.parse(required(object, "updatedAt").getAsString()),
                object.has("source") && !object.get("source").isJsonNull()
                        ? object.get("source").getAsString() : "",
                object.has("revision") && !object.get("revision").isJsonNull()
                        ? object.get("revision").getAsLong() : 0L);
    }

    private static JsonElement required(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("checkpoint record lacks '" + name + "': " + object);
        }
        return element;
    }
}
