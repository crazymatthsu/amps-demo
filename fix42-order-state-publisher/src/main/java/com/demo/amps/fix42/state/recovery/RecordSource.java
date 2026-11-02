package com.demo.amps.fix42.state.recovery;

import com.demo.amps.fix42.state.fix.FixMessage;
import java.util.function.Consumer;

/**
 * Where the {@link SowCacheLoader} reads the records back from at startup
 * (docs/04 §2): over AMPS, one {@code sow} query on the blotter
 * ({@code amps.SowRecordSource}); for another sink, wherever it keeps the
 * records it was handed.
 *
 * <p>The records are the checkpoint: every field of a cache entry is on its
 * record, so a sink whose records cannot be read back needs a side store that
 * can, or runs with {@code recovery: NONE} and {@code allow-incomplete-cache}
 * (docs/07 §3).
 *
 * <p>A failure is thrown, and refuses the start: a partly loaded cache is
 * worse than none.
 */
public interface RecordSource {

    /** What the records are read from, for logs and errors: the blotter topic over AMPS. */
    String name();

    /** Whether there is no record at all: what {@code recovery: NONE} checks before starting empty. */
    boolean isEmpty() throws Exception;

    /**
     * Hands every record to {@code action}, in any order, each the merged
     * record as the sink holds it -- the full publish with every delta since
     * applied over it.
     */
    void forEach(Consumer<FixMessage> action) throws Exception;
}
