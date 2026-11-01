package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.fix.FixMessage;

/**
 * The keyed reads a cache miss is resolved by (docs/04 §3), behind an
 * interface so the machine stays free of AMPS: {@code MissResolver} is the
 * implementation over the audit topics and the blotter.
 *
 * <p>Each method is one keyed read. A failure is thrown unchecked: it
 * propagates out of {@code plan}, nothing is committed, and the message can
 * be redelivered -- as for a publish that throws.
 */
public interface MissLookup {

    /** No lookups: every miss becomes a {@code MIDSTREAM} entry under a freshly minted key. */
    MissLookup NONE = new MissLookup() {
        @Override
        public String keyForClOrdId(String clOrdId) {
            return null;
        }

        @Override
        public FixMessage record(String key) {
            return null;
        }
    };

    /**
     * The OrderKey the audit topics filed {@code clOrdId} under --
     * {@code orders_audit} by its SOW key, then {@code rejects} -- or null.
     */
    String keyForClOrdId(String clOrdId);

    /**
     * The key of the blotter record whose latest OrderID is {@code orderId},
     * or null: {@code miss-lookup-by-order-id}, off by default, because
     * without a server-side index on 37 it is a scan of the blotter rather
     * than a lookup (docs/04 §3).
     */
    default String keyForOrderId(String orderId) {
        return null;
    }

    /**
     * The blotter record under {@code key}, read only after everything
     * published so far is acknowledged, so the SOW cannot lag a delta still in
     * the store; null when there is none.
     */
    FixMessage record(String key);
}
