package com.demo.amps.fix42.state.amps;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.machine.MissLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cache-miss lookups of docs/04 §3 over AMPS: each one keyed read.
 *
 * <ul>
 *   <li>{@code sow/fix42/orders_audit}, then {@code sow/fix42/rejects}, by
 *       tag 11 -- the audit topics hold every request whole with its 13011
 *       trailer (decision 3), so any ClOrdID an order ever used names its key;</li>
 *   <li>{@code sow/fix42/order_state} by 13011, after a {@code publishFlush}
 *       so the SOW cannot lag a delta still in the publish store.</li>
 * </ul>
 *
 * <p>Runs on the publishing thread, inside {@code plan}, through the
 * publisher's own client. A failure is thrown unchecked: nothing is committed
 * and the message can be redelivered.
 *
 * <p>The core's {@link MissLookup} over AMPS; a sink that writes somewhere
 * else implements that interface over its own store (docs/07 §3.1).
 */
public final class MissResolver implements MissLookup {

    private static final Logger log = LoggerFactory.getLogger(MissResolver.class);

    /** The audit topics the shipped flow files every request under, by tag 11. */
    public static final String ORDERS_AUDIT = "sow/fix42/orders_audit";
    public static final String REJECTS = "sow/fix42/rejects";

    /**
     * Where the lookups read, and how long each may take.
     *
     * @param blotter     the record topic, keyed by 13011
     * @param ordersAudit every D, F and G whole, keyed by tag 11, with the 13011 trailer
     * @param rejects     every 35=9 whole, keyed by tag 11, with the 13011 trailer
     * @param timeoutMs   for each query and the {@code publishFlush} before a blotter read
     * @param byOrderId   {@code miss-lookup-by-order-id}: also look a 37 up on the blotter
     */
    public record Options(String blotter, String ordersAudit, String rejects, long timeoutMs, boolean byOrderId) {

        /** The shipped flow's audit topics. */
        public Options(String blotter, long timeoutMs, boolean byOrderId) {
            this(blotter, ORDERS_AUDIT, REJECTS, timeoutMs, byOrderId);
        }
    }

    private final Client client;
    private final String blotter;
    private final String ordersAudit;
    private final String rejects;
    private final long timeoutMs;
    private final boolean byOrderId;

    public MissResolver(Client client, Options options) {
        this.client = client;
        this.blotter = options.blotter();
        this.ordersAudit = options.ordersAudit();
        this.rejects = options.rejects();
        this.timeoutMs = options.timeoutMs();
        this.byOrderId = options.byOrderId();
    }

    /** {@code miss-lookup-by-order-id}: the blotter filtered by its latest 37, after a flush. */
    @Override
    public String keyForOrderId(String orderId) {
        if (!byOrderId) {
            return null;
        }
        try {
            client.publishFlush(timeoutMs);
        } catch (Exception e) {
            throw new IllegalStateException("could not flush before a lookup by 37=" + orderId, e);
        }
        FixMessage record = first(blotter, "/" + FixTags.ORDER_ID + " = " + quote(orderId));
        String key = record == null ? "" : record.value(FixTags.Record.ROOT_CL_ORD_ID);
        return key.isEmpty() ? null : key;
    }

    /**
     * The key the audit topics filed {@code clOrdId} under -- accepted only
     * when it is a chain's key (docs/11 F7). An audit copy is the message as
     * received, so a message that carried its own 13011 kept it, without the
     * publisher's trailer ({@code auditKeyTagConflict}); trusted blindly, that
     * venue value would become a second record. So the key must name itself
     * (the request is the chain's root), have its own root on
     * {@code orders_audit}, or have a blotter record.
     */
    @Override
    public String keyForClOrdId(String clOrdId) {
        for (String topic : new String[] {ordersAudit, rejects}) {
            FixMessage audit = first(topic, "/" + FixTags.CL_ORD_ID + " = " + quote(clOrdId));
            if (audit == null) {
                continue;
            }
            String key = audit.value(FixTags.Record.ROOT_CL_ORD_ID);
            if (key.isEmpty()) {
                continue;
            }
            if (isChainKey(key, audit)) {
                return key;
            }
            log.warn("{} names {} for {}, which is no chain's key (a message carrying its own 13011?); "
                    + "treated as a miss", topic, key, clOrdId);
        }
        return null;
    }

    private boolean isChainKey(String key, FixMessage audit) {
        if (key.equals(audit.value(FixTags.CL_ORD_ID))) {
            return true;
        }
        FixMessage root = first(ordersAudit, "/" + FixTags.CL_ORD_ID + " = " + quote(key));
        if (root != null && key.equals(root.value(FixTags.Record.ROOT_CL_ORD_ID))) {
            return true;
        }
        return record(key) != null;
    }

    @Override
    public FixMessage record(String key) {
        try {
            client.publishFlush(timeoutMs);
        } catch (Exception e) {
            throw new IllegalStateException("could not flush before reading " + key + " from " + blotter, e);
        }
        return first(blotter, "/" + FixTags.Record.ROOT_CL_ORD_ID + " = " + quote(key));
    }

    private FixMessage first(String topic, String filter) {
        Command command = new Command("sow").setTopic(topic).setFilter(filter).setTimeout(timeoutMs);
        try (MessageStream stream = client.execute(command)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    return FixMessage.parse(message.getData());
                }
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("lookup on " + topic + " " + filter + " failed", e);
        }
    }

    /** A filter literal: single quotes, with an embedded quote escaped. */
    static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
