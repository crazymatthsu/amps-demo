package com.demo.amps.fix42.state.machine;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Every id the feed has used, to the OrderKey of the order it belongs to.
 *
 * <p><b>Two maps, not one</b> (docs/03 §3.1, S1). ClOrdIDs and OrderIDs are
 * different namespaces that often share a format -- numeric ids are common on
 * both sides -- so a report {@code 8 … 37=123456} must never resolve to the
 * order whose D said {@code 11=123456}. The "37 →" resolution step consults
 * only the OrderID map, the "11 →" and "41 →" steps only the ClOrdID map, and
 * the set of keys spans both, which is what lets creation detect a collision.
 *
 * <p>Bindings are never moved: an id bound to one key and seen again on
 * another order's message keeps its first binding, and the caller counts the
 * conflict ({@code idConflicts}, R10). The OrderID map doubles as the
 * tombstone of an evicted order (phase 4): its ClOrdIDs go, its OrderIDs stay.
 */
public final class OrderKeyIndex {

    private final Map<String, String> byClOrdId = new HashMap<>();
    private final Map<String, String> byOrderId = new HashMap<>();
    private final Set<String> keys = new HashSet<>();

    /** The key {@code clOrdId} is bound to, or null. */
    public String keyForClOrdId(String clOrdId) {
        return byClOrdId.get(clOrdId);
    }

    /** The key {@code orderId} is bound to, or null. */
    public String keyForOrderId(String orderId) {
        return byOrderId.get(orderId);
    }

    /** Whether {@code id} is some chain's key, of either kind. */
    public boolean isKey(String id) {
        return keys.contains(id);
    }

    void addKey(String key) {
        keys.add(key);
    }

    /** Binds unless already bound elsewhere; returns whether the id is now bound to {@code key}. */
    boolean bindClOrdId(String clOrdId, String key) {
        String existing = byClOrdId.putIfAbsent(clOrdId, key);
        return existing == null || existing.equals(key);
    }

    /** As {@link #bindClOrdId}, in the OrderID map. */
    boolean bindOrderId(String orderId, String key) {
        String existing = byOrderId.putIfAbsent(orderId, key);
        return existing == null || existing.equals(key);
    }

    /** Eviction: an order's ClOrdIDs leave the index; its OrderIDs stay as the tombstone. */
    void forgetClOrdIds(Collection<String> clOrdIds, String key) {
        for (String clOrdId : clOrdIds) {
            byClOrdId.remove(clOrdId, key);
        }
    }

    public int clOrdIdCount() {
        return byClOrdId.size();
    }

    public int orderIdCount() {
        return byOrderId.size();
    }

    public int keyCount() {
        return keys.size();
    }
}
