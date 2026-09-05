package com.demo.amps.seqno.publish;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * What a scan of the transaction log found for one sender: the tag-8888
 * values it saw, and what is wrong with them if anything.
 *
 * <p>The scan is the design's verification step ([03], [04] in
 * {@code docs/}). Where the SOW lookup answers "what is the newest?" in one
 * read and can be fooled by a regressed record, the scan reads every message
 * of the sender in a window and so can report the two things the lookup
 * cannot: a <b>gap</b> (a missing sequence number, i.e. the prefix invariant
 * broke) and a <b>duplicate</b> (a sequence number that appears twice, i.e.
 * something was republished that AMPS already had).
 *
 * <p>Built through {@link Builder} as messages arrive; immutable once
 * {@link Builder#build()} is called.
 */
public final class JournalScanResult {

    private final long count;
    private final OptionalLong min;
    private final OptionalLong max;
    private final List<Long> gaps;
    private final List<Long> duplicates;

    private JournalScanResult(long count, OptionalLong min, OptionalLong max,
                              List<Long> gaps, List<Long> duplicates) {
        this.count = count;
        this.min = min;
        this.max = max;
        this.gaps = List.copyOf(gaps);
        this.duplicates = List.copyOf(duplicates);
    }

    /** Total messages seen, duplicates included. */
    public long count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /** The lowest 8888 seen; empty when nothing matched. */
    public OptionalLong min() {
        return min;
    }

    /** The highest 8888 seen -- the journal's answer for L; empty when nothing matched. */
    public OptionalLong max() {
        return max;
    }

    /** Sequence numbers missing between {@link #min} and {@link #max}. */
    public List<Long> gaps() {
        return gaps;
    }

    /** Sequence numbers that appeared more than once. */
    public List<Long> duplicates() {
        return duplicates;
    }

    /** True when the observed numbers are an unbroken run with no repeats. */
    public boolean isContiguous() {
        return gaps.isEmpty() && duplicates.isEmpty();
    }

    @Override
    public String toString() {
        return "JournalScanResult[count=" + count + ", min=" + min + ", max=" + max
                + ", gaps=" + gaps + ", duplicates=" + duplicates + "]";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates the sequence numbers a scan observes. */
    public static final class Builder {

        private final TreeSet<Long> seen = new TreeSet<>();
        private final List<Long> duplicates = new ArrayList<>();
        private long count;

        /** Records one observed 8888. */
        public Builder observe(long sequence) {
            count++;
            if (!seen.add(sequence)) {
                duplicates.add(sequence);
            }
            return this;
        }

        public JournalScanResult build() {
            if (seen.isEmpty()) {
                return new JournalScanResult(count, OptionalLong.empty(), OptionalLong.empty(),
                        List.of(), List.copyOf(duplicates));
            }
            long low = seen.first();
            long high = seen.last();
            List<Long> gaps = new ArrayList<>();
            for (long s = low; s <= high; s++) {
                if (!seen.contains(s)) {
                    gaps.add(s);
                }
            }
            return new JournalScanResult(count, OptionalLong.of(low), OptionalLong.of(high),
                    gaps, duplicates);
        }
    }
}
