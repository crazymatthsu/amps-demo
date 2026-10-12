package com.demo.amps.ha;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * What a consumer has received, judged against a publisher that numbered its
 * messages {@code 1..N}.
 *
 * <p>This is the whole verdict of the demo, so it is deliberately dumb: a bit
 * per sequence number, and three counters. "No message loss" is
 * {@link #gaps(long)} being empty once {@link #unique()} reaches {@code N};
 * "no duplicate reached the application" is {@link #duplicates()} staying at
 * zero; "order was preserved across the failover" is {@link #outOfOrder()}
 * staying at zero.
 *
 * <p>Thread-safe, because the consumer records on the client's receive thread
 * while the test or the demo reads the counters from another.
 */
public final class SequenceLedger {

    /** What recording a sequence number told us. */
    public enum Outcome { NEW, DUPLICATE }

    /** An inclusive range of sequence numbers that never arrived. */
    public record Gap(long from, long to) {
        public long size() {
            return to - from + 1;
        }

        @Override
        public String toString() {
            return from == to ? Long.toString(from) : from + "-" + to;
        }
    }

    private final BitSet seen = new BitSet();
    private long unique;
    private long duplicates;
    private long outOfOrder;
    private long highest;

    /**
     * Records one received sequence number.
     *
     * @throws IllegalArgumentException for a number outside {@code 1..Integer.MAX_VALUE}:
     *     the ledger is a bitmap, and a publisher that numbers from zero or
     *     wraps is a bug worth failing on rather than miscounting
     */
    public synchronized Outcome record(long seq) {
        if (seq < 1 || seq > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("sequence number out of range: " + seq);
        }
        int index = (int) seq;
        if (seen.get(index)) {
            duplicates++;
            return Outcome.DUPLICATE;
        }
        seen.set(index);
        unique++;
        if (seq < highest) {
            // A first sighting BELOW the highest so far: it arrived after a
            // later message did. AMPS preserves a publisher's order through a
            // replay, so this should stay at zero even across a failover.
            outOfOrder++;
        }
        highest = Math.max(highest, seq);
        return Outcome.NEW;
    }

    /** Distinct sequence numbers received. */
    public synchronized long unique() {
        return unique;
    }

    /** Sequence numbers received a second (or later) time. */
    public synchronized long duplicates() {
        return duplicates;
    }

    /** First sightings that arrived after a higher sequence number had. */
    public synchronized long outOfOrder() {
        return outOfOrder;
    }

    /** The highest sequence number received so far, {@code 0} before the first. */
    public synchronized long highest() {
        return highest;
    }

    /** Whether every number in {@code 1..expected} has been received. */
    public synchronized boolean hasAll(long expected) {
        return expected >= 0 && seen.nextClearBit(1) > expected;
    }

    /** The ranges in {@code 1..expected} that have not been received, in order. */
    public synchronized List<Gap> gaps(long expected) {
        List<Gap> gaps = new ArrayList<>();
        int from = seen.nextClearBit(1);
        while (from <= expected) {
            int to = seen.nextSetBit(from);
            long end = (to < 0 || to > expected) ? expected : to - 1;
            gaps.add(new Gap(from, end));
            if (to < 0 || to > expected) {
                break;
            }
            from = seen.nextClearBit(to);
        }
        return gaps;
    }

    /** One line for a log or an assertion message. */
    public synchronized String report(long expected) {
        List<Gap> gaps = gaps(expected);
        long missing = gaps.stream().mapToLong(Gap::size).sum();
        return "received " + unique + " of " + expected + " (highest " + highest + "), "
                + duplicates + " duplicates, " + outOfOrder + " out of order, "
                + (gaps.isEmpty() ? "no gaps" : missing + " missing in " + gaps.size()
                        + " gap(s): " + summarize(gaps));
    }

    private static String summarize(List<Gap> gaps) {
        if (gaps.size() <= 8) {
            return gaps.toString();
        }
        return gaps.subList(0, 8) + " ... and " + (gaps.size() - 8) + " more";
    }

    @Override
    public synchronized String toString() {
        return "SequenceLedger[unique=" + unique + ", highest=" + highest
                + ", duplicates=" + duplicates + ", outOfOrder=" + outOfOrder + "]";
    }
}
