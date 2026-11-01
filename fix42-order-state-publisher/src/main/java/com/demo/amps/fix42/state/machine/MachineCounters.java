package com.demo.amps.fix42.state.machine;

import java.util.Map;
import java.util.TreeMap;

/**
 * The machine's counters and the per-35 and per-150 histograms.
 *
 * <p>Written by the machine's thread at {@code commit} only, so a plan that
 * is never committed (a publish that threw) counts nothing; read from
 * anywhere for a summary, where a value a message behind is fine.
 */
public final class MachineCounters {

    private final long[] values = new long[Counter.values().length];
    private final Map<String, Long> byMsgType = new TreeMap<>();
    private final Map<String, Long> byExecType = new TreeMap<>();

    public long get(Counter counter) {
        return values[counter.ordinal()];
    }

    void add(int[] deltas) {
        for (int i = 0; i < deltas.length; i++) {
            values[i] += deltas[i];
        }
    }

    void increment(Counter counter) {
        values[counter.ordinal()]++;
    }

    void observe(String msgType, String execType) {
        byMsgType.merge(msgType.isEmpty() ? "?" : msgType, 1L, Long::sum);
        if (!execType.isEmpty()) {
            byExecType.merge(execType, 1L, Long::sum);
        }
    }

    /** Messages seen per tag 35 value. */
    public Map<String, Long> byMsgType() {
        return Map.copyOf(byMsgType);
    }

    /** Reports seen per tag 150 value: a new value appearing is the signal to read docs/03 §5 again. */
    public Map<String, Long> byExecType() {
        return Map.copyOf(byExecType);
    }

    /** Every non-zero counter, by its docs/04 §7 name, for the summary log line. */
    public Map<String, Long> nonZero() {
        Map<String, Long> out = new TreeMap<>();
        for (Counter counter : Counter.values()) {
            if (values[counter.ordinal()] != 0) {
                out.put(counter.label(), values[counter.ordinal()]);
            }
        }
        return out;
    }
}
