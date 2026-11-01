package com.demo.amps.fix42.state.spi;

import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.FixWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Extra record fields a hook adds to the outgoing blotter publish.
 *
 * <p>Hooks write in <b>12100-12999</b> only. The machine owns 12000-12099,
 * 13000-13999 and 15000-15999 and the standard tags it maintains, and a
 * hook that rewrote 39 after the fact would reintroduce the last-writer-wins
 * problem the module exists to remove; this sink refuses anything outside
 * the range. A field written here is delta-published with the rest and
 * survives a restart because it is on the record, though the machine never
 * reads it back.
 */
public final class RecordFields {

    private final List<Integer> tags = new ArrayList<>(2);
    private final List<String> values = new ArrayList<>(2);

    /**
     * @throws IllegalArgumentException for a tag outside 12100-12999 or an empty value
     */
    public RecordFields put(int tag, String value) {
        if (tag < FixTags.Record.HOOK_TAG_MIN || tag > FixTags.Record.HOOK_TAG_MAX) {
            throw new IllegalArgumentException("a hook may write tags 12100-12999 only, not " + tag);
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("tag " + tag + ": a delta cannot remove a field, so a value is required");
        }
        int existing = tags.indexOf(tag);
        if (existing >= 0) {
            values.set(existing, value);
        } else {
            tags.add(tag);
            values.add(value);
        }
        return this;
    }

    public boolean isEmpty() {
        return tags.isEmpty();
    }

    public String get(int tag) {
        int index = tags.indexOf(tag);
        return index < 0 ? null : values.get(index);
    }

    /** Appends every field to {@code out}. */
    public void writeTo(FixWriter out) {
        for (int i = 0; i < tags.size(); i++) {
            out.field(tags.get(i), values.get(i));
        }
    }

    public void clear() {
        tags.clear();
        values.clear();
    }
}
