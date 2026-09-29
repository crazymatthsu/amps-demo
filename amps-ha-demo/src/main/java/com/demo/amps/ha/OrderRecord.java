package com.demo.amps.ha;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;

/**
 * The message the demo publishes: a small JSON order, numbered.
 *
 * <p>{@code id} is the SOW key ({@code <Key>/id</Key>} in the instance
 * configs) and is unique per run and sequence, so a replayed publish that
 * somehow got past the server's duplicate detection would still overwrite its
 * own record rather than add one. {@code run} lets a consumer that outlives
 * several publisher runs keep a ledger per run; {@code seq} is what the ledger
 * counts.
 *
 * @param id        the SOW key, {@code <run>-<seq>}
 * @param run       which publisher run this belongs to
 * @param seq       1-based position within the run
 * @param publisher the publishing client's name
 * @param symbol    something to look at in the admin console
 * @param qty       likewise
 * @param ts        when it was created, ISO-8601
 */
public record OrderRecord(String id, String run, long seq, String publisher,
                          String symbol, int qty, String ts) {

    private static final Gson GSON = new Gson();
    private static final String[] SYMBOLS = {"AAPL", "MSFT", "IBM", "GOOG", "AMZN", "META", "NVDA", "TSLA"};

    /** The record for position {@code seq} of {@code run}, deterministic apart from the timestamp. */
    public static OrderRecord of(String run, String publisher, long seq) {
        return new OrderRecord(run + "-" + seq, run, seq, publisher,
                SYMBOLS[(int) (seq % SYMBOLS.length)], (int) (100 * (1 + seq % 10)),
                Instant.now().toString());
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static OrderRecord parse(String json) {
        return GSON.fromJson(json, OrderRecord.class);
    }

    /**
     * Just the two fields the consumer needs, without building the record.
     *
     * @return {@code [run, seq]} or {@code null} when the document is not one of ours
     */
    public static RunAndSeq runAndSeqOf(String json) {
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement run = object.get("run");
        JsonElement seq = object.get("seq");
        if (run == null || seq == null || !run.isJsonPrimitive() || !seq.isJsonPrimitive()) {
            return null;
        }
        return new RunAndSeq(run.getAsString(), seq.getAsLong());
    }

    /** The consumer's view of a record: which run, which position. */
    public record RunAndSeq(String run, long seq) {
    }
}
