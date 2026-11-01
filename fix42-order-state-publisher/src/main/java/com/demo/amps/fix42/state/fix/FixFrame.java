package com.demo.amps.fix42.state.fix;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A reusable, allocation-free view over one FIX payload held in a byte array.
 *
 * <p>This is the input half of the allocation-free publish path, and the
 * parser behind {@link FixMessage#parse}. {@link #wrap} makes one pass over
 * the bytes and records, per field, the tag number and where its value lies;
 * nothing is copied and no object is created. Lookups scan that index
 * linearly -- a FIX message here has a few dozen fields, where a scan beats a
 * hash table and allocates nothing. Values are compared and copied in place;
 * a String is materialized only by the methods that say so ({@link #value},
 * {@link #valueAt}, {@link #printable}), for logs and tests.
 *
 * <p>Semantics match the map-based {@link FixMessage}: when a tag repeats,
 * lookups see the last occurrence, while index-based iteration and
 * {@link #buffer()} keep the payload exactly as received. FIX repeating
 * groups are not interpreted (this publisher carries none), and an
 * unterminated final field runs to the end of the payload.
 *
 * <p>The frame references the wrapped array rather than copying it, so it is
 * valid only while the caller leaves that array unchanged. One frame per
 * thread; not thread-safe.
 */
public final class FixFrame {

    private static final int INITIAL_FIELDS = 64;

    private byte[] buffer = new byte[0];
    private int offset;
    private int length;
    private int[] tags = new int[INITIAL_FIELDS];
    private int[] valueOffsets = new int[INITIAL_FIELDS];
    private int[] valueLengths = new int[INITIAL_FIELDS];
    private int count;

    /**
     * Indexes {@code length} bytes of {@code payload} starting at {@code offset}.
     *
     * @return this frame
     * @throws IllegalArgumentException when a field has no tag, a tag is not a
     *         number, or a tag has no {@code =}; a parser that silently
     *         dropped such a field would hide corrupt order flow
     */
    public FixFrame wrap(byte[] payload, int offset, int length) {
        this.buffer = payload;
        this.offset = offset;
        this.length = length;
        this.count = 0;
        int end = offset + length;
        int i = offset;
        while (i < end) {
            int tag = 0;
            int digits = 0;
            while (i < end && buffer[i] != '=') {
                int digit = buffer[i] - '0';
                if (digit < 0 || digit > 9 || digits == 9) {
                    throw malformed(i);
                }
                tag = tag * 10 + digit;
                digits++;
                i++;
            }
            if (i == end || digits == 0) {
                throw malformed(i);
            }
            i++;
            int valueStart = i;
            while (i < end && buffer[i] != FixMessage.SOH) {
                i++;
            }
            add(tag, valueStart, i - valueStart);
            i++;
        }
        return this;
    }

    /** Indexes everything {@code writer} holds. */
    public FixFrame wrap(FixWriter writer) {
        return wrap(writer.buffer(), 0, writer.size());
    }

    // ---- the payload as received ------------------------------------------

    /** The wrapped array; the payload is {@code [offset(), offset() + length())}. */
    public byte[] buffer() {
        return buffer;
    }

    public int offset() {
        return offset;
    }

    public int length() {
        return length;
    }

    // ---- by index, in wire order -------------------------------------------

    public int count() {
        return count;
    }

    public int tagAt(int index) {
        checkIndex(index);
        return tags[index];
    }

    public int valueOffsetAt(int index) {
        checkIndex(index);
        return valueOffsets[index];
    }

    public int valueLengthAt(int index) {
        checkIndex(index);
        return valueLengths[index];
    }

    /** The value at {@code index} as a String; allocates. */
    public String valueAt(int index) {
        checkIndex(index);
        return new String(buffer, valueOffsets[index], valueLengths[index],
                StandardCharsets.ISO_8859_1);
    }

    /** Whether the value at {@code index} equals {@code literal} character for character. */
    public boolean valueAtEquals(int index, String literal) {
        checkIndex(index);
        int valueLength = valueLengths[index];
        if (valueLength != literal.length()) {
            return false;
        }
        int valueOffset = valueOffsets[index];
        for (int k = 0; k < valueLength; k++) {
            if ((buffer[valueOffset + k] & 0xFF) != literal.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    /** Appends the field at {@code index} to {@code out} as received. */
    public FixWriter copyFieldAt(int index, FixWriter out) {
        checkIndex(index);
        return out.field(tags[index], buffer, valueOffsets[index], valueLengths[index]);
    }

    // ---- by tag -------------------------------------------------------------

    /** The index of the last occurrence of {@code tag}, or {@code -1}. */
    public int indexOf(int tag) {
        for (int i = count - 1; i >= 0; i--) {
            if (tags[i] == tag) {
                return i;
            }
        }
        return -1;
    }

    public boolean has(int tag) {
        return indexOf(tag) >= 0;
    }

    /** The value of {@code tag}, or {@code ""} when absent; allocates. */
    public String value(int tag) {
        int index = indexOf(tag);
        return index < 0 ? "" : valueAt(index);
    }

    /** Whether {@code tag} is present with exactly the value {@code literal}. */
    public boolean valueEquals(int tag, String literal) {
        int index = indexOf(tag);
        return index >= 0 && valueAtEquals(index, literal);
    }

    /**
     * Appends {@code tag=value<SOH>} to {@code out}.
     *
     * @return whether the tag was present; nothing is written when it is not
     */
    public boolean copyField(int tag, FixWriter out) {
        return copyFieldAs(tag, tag, out);
    }

    /** Appends the value of {@code tag} to {@code out} under {@code asTag}; see {@link #copyField}. */
    public boolean copyFieldAs(int tag, int asTag, FixWriter out) {
        int index = indexOf(tag);
        if (index < 0) {
            return false;
        }
        out.field(asTag, buffer, valueOffsets[index], valueLengths[index]);
        return true;
    }

    // ---- text ---------------------------------------------------------------

    /** The payload as text, separators included; allocates. */
    public String asString() {
        return new String(buffer, offset, length, StandardCharsets.ISO_8859_1);
    }

    /** The payload with separators rendered as {@code |}, for logs and assertions. */
    public String printable() {
        return asString().replace((char) FixMessage.SOH, '|');
    }

    @Override
    public String toString() {
        return printable();
    }

    private void add(int tag, int valueOffset, int valueLength) {
        if (count == tags.length) {
            int capacity = tags.length * 2;
            tags = Arrays.copyOf(tags, capacity);
            valueOffsets = Arrays.copyOf(valueOffsets, capacity);
            valueLengths = Arrays.copyOf(valueLengths, capacity);
        }
        tags[count] = tag;
        valueOffsets[count] = valueOffset;
        valueLengths[count] = valueLength;
        count++;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("field " + index + " of " + count);
        }
    }

    private IllegalArgumentException malformed(int at) {
        return new IllegalArgumentException("malformed FIX field at byte " + (at - offset)
                + " of: " + printable());
    }
}
