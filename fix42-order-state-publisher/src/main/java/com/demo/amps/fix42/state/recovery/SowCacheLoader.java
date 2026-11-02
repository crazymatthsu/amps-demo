package com.demo.amps.fix42.state.recovery;

import com.demo.amps.fix42.state.config.Recovery;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.machine.OrderState;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.RecordReader;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds the cache at startup, before the first feed message (docs/04 §2).
 *
 * <p>The SOW record is the checkpoint: every field of a cache entry is on it,
 * so one pass over the records rebuilds the entries, the ClOrdID map from
 * 12021 and the OrderID map from 37. The pass reads a {@link RecordSource}:
 * over AMPS, one {@code sow} over the blotter ({@code amps.SowRecordSource}).
 * What is not recovered is the ExecID ring behind the last ExecID, so
 * duplicate detection is depth one for the first reports after a restart; the
 * stale and late guards still hold.
 *
 * <ul>
 *   <li>{@code recovery: SOW} loads every record; with
 *       {@code load-open-orders-only} only the open ones keep a body, and the
 *       terminal ones leave a tombstone (every 37 → 13011), so "not loaded"
 *       and "evicted" behave alike and the cache is marked incomplete;</li>
 *   <li>{@code recovery: NONE} starts empty, and is refused against a
 *       non-empty blotter unless {@code allow-incomplete-cache} (R4): with an
 *       empty cache every D for an existing order would be a new chain;</li>
 *   <li>a record without 13011, or of a schema newer than this publisher's,
 *       refuses the start (R17).</li>
 * </ul>
 *
 * <p>Run after the sink is connected, so the old instance's bytes cannot still
 * be in flight behind the snapshot (docs/04 §5).
 */
public final class SowCacheLoader {

    private static final Logger log = LoggerFactory.getLogger(SowCacheLoader.class);

    /** What a load did. */
    public record Report(Recovery recovery, int loaded, int tombstones, int defaulted, int unrecovered,
                         boolean complete) {
    }

    private final RecoveryOptions options;

    public SowCacheLoader(RecoveryOptions options) {
        this.options = options;
    }

    public Report load(RecordSource source, OrderStateMachine machine) throws Exception {
        String topic = source.name();
        if (options.recovery() == Recovery.NONE) {
            boolean empty = source.isEmpty();
            if (!empty && !options.allowIncompleteCache()) {
                throw new IllegalStateException("recovery: NONE against a non-empty " + topic + " is refused: "
                        + "every D for an existing order would become a new chain. Use recovery: SOW, or set "
                        + "allow-incomplete-cache: true to look each order up on its first message (docs/04 §2)");
            }
            if (!empty) {
                machine.markCacheIncomplete();
            }
            log.info("recovery NONE: starting with an empty cache ({} blotter)", empty ? "an empty" : "a populated");
            return new Report(Recovery.NONE, 0, 0, 0, 0, empty);
        }

        Pass pass = new Pass(machine, options);
        source.forEach(pass);
        boolean complete = !options.loadOpenOrdersOnly() || pass.tombstones == 0;
        if (!complete) {
            machine.markCacheIncomplete();
        }
        if (pass.unrecovered > 0) {
            log.warn("{} record(s) of {} did not parse and were loaded UNRECOVERED with an empty pending set",
                    pass.unrecovered, topic);
        }
        log.info("recovery SOW: {} entries loaded, {} tombstones, {} with defaults, {} unrecovered, from {}",
                pass.loaded, pass.tombstones, pass.defaulted, pass.unrecovered, topic);
        return new Report(Recovery.SOW, pass.loaded, pass.tombstones, pass.defaulted, pass.unrecovered, complete);
    }

    /** One record at a time into the machine, counted for the {@link Report}. */
    private static final class Pass implements Consumer<FixMessage> {

        private final OrderStateMachine machine;
        private final RecoveryOptions options;
        int loaded;
        int tombstones;
        int defaulted;
        int unrecovered;

        Pass(OrderStateMachine machine, RecoveryOptions options) {
            this.machine = machine;
            this.options = options;
        }

        @Override
        public void accept(FixMessage record) {
            RecordReader.Result read = RecordReader.read(record, options.schemaVersion());
            machine.countLoaded(read);
            defaulted += read.defaulted() ? 1 : 0;
            unrecovered += read.unrecovered() ? 1 : 0;
            OrderState state = read.state();
            if (options.loadOpenOrdersOnly() && state.terminal()) {
                machine.tombstone(state.orderKey(), state.orderIds());
                tombstones++;
            } else {
                machine.load(state);
                loaded++;
            }
        }
    }
}
