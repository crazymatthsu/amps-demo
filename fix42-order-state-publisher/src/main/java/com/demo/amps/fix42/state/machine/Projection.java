package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.fix.FixWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A route's blotter projection (docs/07 §5), compiled once: which of a
 * message's own tags the order-state record carries beside the machine's
 * fields, under which tag numbers, and which literal values it stamps. The
 * same three tables as {@code fix42-publisher}'s {@code projection} block,
 * with the same precedence: a literal beats a copy, a copy beats the verbatim
 * field, and each tag is written once.
 *
 * <ul>
 *   <li>{@code tags} -- copied verbatim. When non-empty they <b>replace</b>
 *       the machine's default pass-through for the route's messages: the
 *       record gets exactly these, beside the machine's own fields. Empty
 *       keeps the default pass-through.</li>
 *   <li>{@code copyTags} -- source tag to destination tag: the source's value
 *       written under the destination's number, which is how a 35=G's
 *       proposed 59 can reach the record as, say, 9059 without overwriting the
 *       acked 59.</li>
 *   <li>{@code setTags} -- destination tag to a literal value.</li>
 * </ul>
 *
 * <p>A projection never writes a tag the machine maintains -- 35, 11, 41,
 * 37, 38, 44, 60, 9000, 39, 150, 17, 14, 151, 6, 32, 31, 30 -- nor anything in
 * the publisher's internal ranges (12000-12999, 13000-13999, 15000-15999):
 * the constructor refuses them, because a projected 39 would bring back the
 * last-writer-wins problem the module exists to remove. The writer applies a
 * projection only to a message the machine applied, never to one it ignored,
 * and writes an {@code order-term-tags} tag only from a message whose terms
 * the machine adopts ({@link RecordWriter}).
 */
public final class Projection {

    private final int[] tags;
    private final int[] copyFrom;
    private final int[] copyTo;
    private final int[] setTags;
    /** Each literal as {@code tag=value<SOH>}, encoded once. */
    private final byte[][] setTagBytes;

    /**
     * @throws IllegalArgumentException for a destination tag the machine
     *         writes or one in the internal ranges, a non-positive tag, or an
     *         empty literal (a delta can overwrite a field, never remove it)
     */
    public Projection(List<Integer> tags, Map<Integer, Integer> copyTags, Map<Integer, String> setTags) {
        tags = tags == null ? List.of() : tags;
        copyTags = copyTags == null ? Map.of() : copyTags;
        setTags = setTags == null ? Map.of() : setTags;
        List<String> problems = problems(tags, copyTags, setTags);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        this.tags = toArray(new LinkedHashSet<>(tags));
        this.copyFrom = toArray(copyTags.keySet());
        this.copyTo = toArray(copyTags.values());
        this.setTags = toArray(setTags.keySet());
        this.setTagBytes = new byte[this.setTags.length][];
        int i = 0;
        for (Map.Entry<Integer, String> literal : setTags.entrySet()) {
            setTagBytes[i++] = FixWriter.encodeField(literal.getKey(), literal.getValue());
        }
    }

    /**
     * What is wrong with these tables, one line per problem; empty when
     * nothing is. The configuration check reports these at startup.
     */
    public static List<String> problems(Collection<Integer> tags, Map<Integer, Integer> copyTags,
                                        Map<Integer, String> setTags) {
        List<String> problems = new ArrayList<>();
        Set<Integer> owned = new LinkedHashSet<>();
        Set<Integer> invalid = new LinkedHashSet<>();
        List<Integer> written = new ArrayList<>(tags);
        written.addAll(copyTags.values());
        written.addAll(setTags.keySet());
        for (Integer tag : written) {
            if (tag == null || tag <= 0) {
                invalid.add(tag);
            } else if (RecordWriter.writtenByMachine(tag)) {
                owned.add(tag);
            }
        }
        for (Integer source : copyTags.keySet()) {
            if (source == null || source <= 0) {
                invalid.add(source);
            }
        }
        if (!invalid.isEmpty()) {
            problems.add("tags must be positive numbers, not " + invalid);
        }
        if (!owned.isEmpty()) {
            problems.add("it writes " + owned + ", which the state machine writes from the order's state: "
                    + "remove them (the machine publishes them whenever they change), or copy the message's "
                    + "value under another tag with copy-tags");
        }
        setTags.forEach((tag, value) -> {
            if (value == null || value.isEmpty()) {
                problems.add("set-tags " + tag + " is empty: a delta can overwrite a field but never remove one");
            }
        });
        return problems;
    }

    /** Whether the projection names verbatim tags, and so replaces the machine's default pass-through. */
    public boolean replacesPassThrough() {
        return tags.length > 0;
    }

    /** Every tag the projection can write: verbatim tags, copy destinations, literals. */
    public Set<Integer> producedTags() {
        Set<Integer> produced = new LinkedHashSet<>();
        for (int tag : tags) {
            produced.add(tag);
        }
        for (int tag : copyTo) {
            produced.add(tag);
        }
        for (int tag : setTags) {
            produced.add(tag);
        }
        return produced;
    }

    int[] tags() {
        return tags;
    }

    int[] copyTo() {
        return copyTo;
    }

    int[] setTags() {
        return setTags;
    }

    /** Whether a copy or a literal writes {@code tag}: the default pass-through then leaves it alone. */
    boolean rewrites(int tag) {
        return indexOf(copyTo, tag) >= 0 || indexOf(setTags, tag) >= 0;
    }

    /** The encoded literal for {@code tag}, or null when no literal writes it. */
    byte[] literal(int tag) {
        int index = indexOf(setTags, tag);
        return index < 0 ? null : setTagBytes[index];
    }

    /** The source of the {@code i}-th copy. */
    int copyFrom(int i) {
        return copyFrom[i];
    }

    private static int indexOf(int[] array, int tag) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == tag) {
                return i;
            }
        }
        return -1;
    }

    private static int[] toArray(Collection<Integer> values) {
        int[] array = new int[values.size()];
        int i = 0;
        for (Integer value : values) {
            array[i++] = value;
        }
        return array;
    }
}
