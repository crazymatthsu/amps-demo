package com.demo.amps.fix42.fix;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A reusable byte buffer that encodes FIX fields as {@code tag=value<SOH>}.
 *
 * <p>This is the output half of the allocation-free publish path. A caller
 * keeps one writer per thread, fills it with a payload, hands
 * {@link #buffer()} and {@link #size()} to the AMPS client's byte-array
 * {@code publish} overloads, and calls {@link #clear()} before the next
 * message. The AMPS client copies the bytes into its own send buffer before
 * {@code publish} returns, so the writer may be reused immediately.
 *
 * <p>Values are encoded as Latin-1: FIX on the wire is single-byte text and
 * every value this publisher handles is ASCII. A {@link String} value is
 * written char by char with no intermediate array, which is the one thing the
 * AMPS {@code FIXBuilder} cannot do -- it accepts only {@code byte[]} values,
 * so a String costs one array per field there. Characters outside Latin-1
 * become {@code '?'}, exactly as {@code String.getBytes(ISO_8859_1)} renders
 * them.
 *
 * <p>The writer also remembers which tags it has written, so a planner can ask
 * whether a payload carries its topic's key before sending it. The tag list
 * is a plain array scanned linearly: a FIX message here has a few dozen
 * fields, where a scan beats any hash lookup.
 *
 * <p>Not thread-safe; a writer belongs to one thread.
 */
public final class FixWriter {

    private static final int MIN_CAPACITY = 16;

    private byte[] buffer;
    private int size;
    private int[] tags;
    private int fieldCount;

    public FixWriter(int initialCapacity) {
        this.buffer = new byte[Math.max(initialCapacity, MIN_CAPACITY)];
        this.tags = new int[32];
    }

    /** Forgets every field written so far; the backing buffer is kept. */
    public void clear() {
        size = 0;
        fieldCount = 0;
    }

    /** The backing array; valid bytes are {@code [0, size())}. Grows when a message needs it. */
    public byte[] buffer() {
        return buffer;
    }

    /** The number of valid bytes in {@link #buffer()}. */
    public int size() {
        return size;
    }

    /** The number of fields written since the last {@link #clear()}. */
    public int fieldCount() {
        return fieldCount;
    }

    /** Whether {@code tag} has been written since the last {@link #clear()}. */
    public boolean contains(int tag) {
        for (int i = 0; i < fieldCount; i++) {
            if (tags[i] == tag) {
                return true;
            }
        }
        return false;
    }

    /** Appends {@code tag=value<SOH>}, copying {@code length} bytes of {@code value} from {@code offset}. */
    public FixWriter field(int tag, byte[] value, int offset, int length) {
        int tagDigits = digits(tag);
        ensureCapacity(tagDigits + 1 + length + 1);
        writeTag(tag, tagDigits);
        System.arraycopy(value, offset, buffer, size, length);
        size += length;
        buffer[size++] = FixMessage.SOH;
        remember(tag);
        return this;
    }

    /** Appends {@code tag=value<SOH>}, encoding {@code value} as Latin-1 without an intermediate array. */
    public FixWriter field(int tag, String value) {
        int tagDigits = digits(tag);
        int length = value.length();
        ensureCapacity(tagDigits + 1 + length + 1);
        writeTag(tag, tagDigits);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            buffer[size++] = c <= 0xFF ? (byte) c : (byte) '?';
        }
        buffer[size++] = FixMessage.SOH;
        remember(tag);
        return this;
    }

    /**
     * Appends a field that was encoded once up front with {@link #encodeField},
     * for literals a route stamps on every message.
     *
     * @param tag          the tag the encoded bytes carry, so {@link #contains} stays accurate
     * @param encodedField the complete {@code tag=value<SOH>} bytes
     */
    public FixWriter raw(int tag, byte[] encodedField) {
        ensureCapacity(encodedField.length);
        System.arraycopy(encodedField, 0, buffer, size, encodedField.length);
        size += encodedField.length;
        remember(tag);
        return this;
    }

    /** {@code tag=value<SOH>} as bytes, for {@link #raw}. */
    public static byte[] encodeField(int tag, String value) {
        FixWriter once = new FixWriter(value.length() + 12);
        once.field(tag, value);
        return Arrays.copyOf(once.buffer, once.size);
    }

    /** The payload as text, separators included. */
    public String asString() {
        return new String(buffer, 0, size, StandardCharsets.ISO_8859_1);
    }

    /** The payload with separators rendered as {@code |}, for logs and assertions. */
    public String printable() {
        return asString().replace((char) FixMessage.SOH, '|');
    }

    @Override
    public String toString() {
        return printable();
    }

    private void writeTag(int tag, int tagDigits) {
        int end = size + tagDigits;
        int rest = tag;
        for (int i = end - 1; i >= size; i--) {
            buffer[i] = (byte) ('0' + rest % 10);
            rest /= 10;
        }
        size = end;
        buffer[size++] = '=';
    }

    private static int digits(int tag) {
        if (tag < 0) {
            throw new IllegalArgumentException("negative FIX tag " + tag);
        }
        int digits = 1;
        for (int rest = tag; rest >= 10; rest /= 10) {
            digits++;
        }
        return digits;
    }

    private void remember(int tag) {
        if (fieldCount == tags.length) {
            tags = Arrays.copyOf(tags, tags.length * 2);
        }
        tags[fieldCount++] = tag;
    }

    private void ensureCapacity(int extra) {
        int needed = size + extra;
        if (needed > buffer.length) {
            int capacity = buffer.length;
            while (capacity < needed) {
                capacity *= 2;
            }
            buffer = Arrays.copyOf(buffer, capacity);
        }
    }
}
