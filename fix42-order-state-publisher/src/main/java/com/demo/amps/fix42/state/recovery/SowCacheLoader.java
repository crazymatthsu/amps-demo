package com.demo.amps.fix42.state.recovery;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.fix42.state.config.OrderStateProperties;
import com.demo.amps.fix42.state.config.Recovery;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.machine.OrderState;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.RecordReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds the cache at startup, before the first feed message (docs/04 §2).
 *
 * <p>The SOW record is the checkpoint: every field of a cache entry is on it,
 * so one {@code sow} over the blotter rebuilds the entries, the ClOrdID map
 * from 9021 and the OrderID map from 37. What is not recovered is the ExecID
 * ring behind the last ExecID, so duplicate detection is depth one for the
 * first reports after a restart; the stale and late guards still hold.
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
 * <p>Run after logon, so the old instance's bytes cannot still be in flight
 * behind the snapshot (docs/04 §5).
 */
public final class SowCacheLoader {

    private static final Logger log = LoggerFactory.getLogger(SowCacheLoader.class);

    /** What a load did. */
    public record Report(Recovery recovery, int loaded, int tombstones, int defaulted, int unrecovered,
                         boolean complete) {
    }

    private final OrderStateProperties properties;

    public SowCacheLoader(OrderStateProperties properties) {
        this.properties = properties;
    }

    public Report load(Client client, OrderStateMachine machine) throws Exception {
        OrderStateProperties.OrderState settings = properties.orderState();
        String topic = settings.topic();
        long timeoutMs = properties.amps().timeoutMs();
        if (settings.recovery() == Recovery.NONE) {
            boolean empty = isEmpty(client, topic, timeoutMs);
            if (!empty && !settings.allowIncompleteCache()) {
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

        int loaded = 0;
        int tombstones = 0;
        int defaulted = 0;
        int unrecovered = 0;
        Command command = new Command("sow").setTopic(topic).setTimeout(timeoutMs);
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() != Message.Command.SOW || message.isDataNull()) {
                    continue;
                }
                RecordReader.Result read = RecordReader.read(FixMessage.parse(message.getData()),
                        settings.schemaVersion());
                machine.countLoaded(read);
                defaulted += read.defaulted() ? 1 : 0;
                unrecovered += read.unrecovered() ? 1 : 0;
                OrderState state = read.state();
                if (settings.loadOpenOrdersOnly() && state.terminal()) {
                    machine.tombstone(state.orderKey(), state.orderIds());
                    tombstones++;
                } else {
                    machine.load(state);
                    loaded++;
                }
            }
        }
        boolean complete = !settings.loadOpenOrdersOnly() || tombstones == 0;
        if (!complete) {
            machine.markCacheIncomplete();
        }
        if (unrecovered > 0) {
            log.warn("{} record(s) of {} did not parse and were loaded UNRECOVERED with an empty pending set",
                    unrecovered, topic);
        }
        log.info("recovery SOW: {} entries loaded, {} tombstones, {} with defaults, {} unrecovered, from {}",
                loaded, tombstones, defaulted, unrecovered, topic);
        return new Report(Recovery.SOW, loaded, tombstones, defaulted, unrecovered, complete);
    }

    private static boolean isEmpty(Client client, String topic, long timeoutMs) throws Exception {
        Command command = new Command("sow").setTopic(topic).setTopN(1).setTimeout(timeoutMs);
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW) {
                    return false;
                }
            }
        }
        return true;
    }
}
