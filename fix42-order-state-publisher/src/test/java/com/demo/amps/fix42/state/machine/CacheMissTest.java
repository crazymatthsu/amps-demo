package com.demo.amps.fix42.state.machine;

import static com.demo.amps.fix42.state.machine.MachineTester.t;
import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.spi.IgnoreReason;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * docs/04 §3-4 without a server: a {@link MissLookup} over the tester's own
 * simulated SOW, and audit topics as a map of ClOrdID to key.
 */
class CacheMissTest {

    /** The lookups, answered from a tester's merged records and a ClOrdID → key map. */
    private static final class FakeLookup implements MissLookup {
        final Map<String, String> audit = new HashMap<>();
        final Map<String, FixMessage> blotter = new HashMap<>();
        int recordReads;

        @Override
        public String keyForClOrdId(String clOrdId) {
            return audit.get(clOrdId);
        }

        @Override
        public FixMessage record(String key) {
            recordReads++;
            return blotter.get(key);
        }
    }

    /** A first publisher's run, whose SOW and audit the second publisher's lookups read. */
    private static FakeLookup firstRun(String... messages) {
        MachineTester first = new MachineTester();
        FakeLookup lookup = new FakeLookup();
        for (String message : messages) {
            Outcome outcome = first.send(message);
            FixMessage parsed = MachineTester.parse(message);
            if (outcome.key() != null && java.util.List.of("D", "G", "F", "9").contains(parsed.msgType())) {
                lookup.audit.put(parsed.value(11), outcome.key());
            }
        }
        for (OrderState state : first.machine.states()) {
            lookup.blotter.put(state.orderKey(), first.record(state.orderKey()));
        }
        return lookup;
    }

    private static final String[] FILLED_THEN_CANCELED = {
        "D 11=X-1 38=1000 44=50 55=SYM 1=ACC 60=" + t(0),
        "8 150=0 39=0 11=X-1 37=ORD 17=E-1 38=1000 44=50 14=0 151=1000 6=0 60=" + t(1),
        "8 150=1 39=1 11=X-1 37=ORD 17=E-2 38=1000 44=50 14=300 151=700 6=50 32=300 31=50 60=" + t(2),
        "F 11=X-2 41=X-1 60=" + t(3),
        "8 150=4 39=4 11=X-2 41=X-1 37=ORD 17=E-3 14=300 151=0 6=50 60=" + t(4),
    };

    @Test
    @DisplayName("E53: an empty cache resolves a report through orders_audit, rebuilds the entry from its record")
    void reportResolvedThroughTheAudit() {
        FakeLookup lookup = firstRun(FILLED_THEN_CANCELED[0], FILLED_THEN_CANCELED[1], FILLED_THEN_CANCELED[2]);
        MachineTester second = new MachineTester();
        second.machine.setMissLookup(lookup);
        second.machine.markCacheIncomplete();
        Outcome outcome = second.send(FILLED_THEN_CANCELED[3]);
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.CHANGED);
        assertThat(outcome.key()).isEqualTo("X-1");
        OrderState rebuilt = second.state("X-1");
        assertThat(rebuilt.cumQty()).isEqualTo("300");
        assertThat(rebuilt.msgCount()).isEqualTo(4);
        assertThat(rebuilt.pending()).extracting(PendingRequest::clOrdId).containsExactly("X-2");
        assertThat(second.count(Counter.MISS_LOOKUPS)).isEqualTo(1);
        assertThat(second.count(Counter.REBUILT_FROM_RECORD)).isEqualTo(1);
        assertThat(second.machine.index().keyForOrderId("ORD")).isEqualTo("X-1");
        // Bound now: the next report resolves without a lookup.
        second.send(FILLED_THEN_CANCELED[4]);
        assertThat(second.count(Counter.MISS_LOOKUPS)).isEqualTo(1);
        assertThat(second.state("X-1").venueStatus()).isEqualTo("4");
        assertThat(second.count(Counter.MIDSTREAM_CHAINS)).isZero();
    }

    @Test
    @DisplayName("E55: a resent D against a populated SOW with an empty cache is found by key and ignored")
    void resentDAgainstAnIncompleteCache() {
        FakeLookup lookup = firstRun(FILLED_THEN_CANCELED);
        MachineTester second = new MachineTester();
        second.machine.setMissLookup(lookup);
        second.machine.markCacheIncomplete();
        Outcome resent = second.send(FILLED_THEN_CANCELED[0]);
        assertThat(resent.reason()).isEqualTo(IgnoreReason.DUPLICATE_REQUEST);
        assertThat(resent.publish()).as("never a FULL publish over the record").isNotEqualTo(Outcome.Publish.FULL);
        assertThat(second.state("X-1").venueStatus()).isEqualTo("4");
        assertThat(second.count(Counter.RESENT_D_FOUND_BY_KEY)).isEqualTo(1);
    }

    @Test
    @DisplayName("a D for a new order against an incomplete cache: one keyed read, then created")
    void newDAgainstAnIncompleteCache() {
        FakeLookup lookup = new FakeLookup();
        MachineTester m = new MachineTester();
        m.machine.setMissLookup(lookup);
        m.machine.markCacheIncomplete();
        assertThat(m.send("D 11=N-1 38=100 44=10 60=" + t(0)).publish()).isEqualTo(Outcome.Publish.FULL);
        assertThat(lookup.recordReads).isEqualTo(1);
    }

    @Test
    @DisplayName("an order first seen mid-stream, then a restart with an empty cache: rebuilt from its record, not re-created")
    void midstreamOrderAfterAnIncompleteRestart() {
        FakeLookup lookup = firstRun(
                "8 150=0 39=0 11=M-1 37=ORD-M 17=E-1 38=500 44=10 55=SYM 14=0 151=500 6=0 60=" + t(1),
                "8 150=1 39=1 11=M-1 37=ORD-M 17=E-2 38=500 44=10 14=100 151=400 6=10 32=100 31=10 60=" + t(2));
        MachineTester m = new MachineTester();
        m.machine.setMissLookup(lookup);
        m.machine.markCacheIncomplete();
        Outcome fill = m.send("8 150=1 39=1 11=M-1 37=ORD-M 17=E-3 38=500 44=10 14=200 151=300 6=10 32=100 31=10 60="
                + t(3));
        assertThat(fill.kind()).isEqualTo(Outcome.Kind.CHANGED);
        assertThat(m.state("M-1").msgCount()).as("counting on from the record").isEqualTo(3);
        assertThat(m.state("M-1").origin()).isEqualTo(Origin.MIDSTREAM);
        assertThat(m.count(Counter.MIDSTREAM_CHAINS)).isZero();
        assertThat(m.count(Counter.REBUILT_FROM_RECORD)).isEqualTo(1);
    }

    @Test
    @DisplayName("04 §3: a report known only by its 37 is a MIDSTREAM twin unless miss-lookup-by-order-id finds it")
    void knownOnlyByOrderId() {
        FakeLookup lookup = firstRun(FILLED_THEN_CANCELED[0], FILLED_THEN_CANCELED[1]);
        String confirm = "8 150=5 39=0 11=X-3 41=X-2 37=ORD 17=E-9 38=1200 44=50 14=0 151=1200 6=0 60=" + t(5);

        MachineTester twin = new MachineTester();
        twin.machine.setMissLookup(lookup);
        twin.machine.markCacheIncomplete();
        assertThat(twin.send(confirm).key()).isEqualTo("X-2");
        assertThat(twin.count(Counter.MIDSTREAM_WITH_KNOWN_ORDER_ID)).isEqualTo(1);

        MachineTester found = new MachineTester();
        found.machine.setMissLookup(new MissLookup() {
            @Override
            public String keyForClOrdId(String clOrdId) {
                return lookup.keyForClOrdId(clOrdId);
            }

            @Override
            public FixMessage record(String key) {
                return lookup.record(key);
            }

            @Override
            public String keyForOrderId(String orderId) {
                return "ORD".equals(orderId) ? "X-1" : null;
            }
        });
        found.machine.markCacheIncomplete();
        assertThat(found.send(confirm).key()).isEqualTo("X-1");
        assertThat(found.state("X-1").ackedQty()).isEqualTo("1200");
        assertThat(found.count(Counter.MIDSTREAM_CHAINS)).isZero();
    }

    @Test
    @DisplayName("docs/11 F8: with an incomplete cache, a G's 41 is looked up in its turn, before its 37")
    void lookupsInResolutionOrder() {
        FakeLookup lookup = firstRun(
                "D 11=A-1 38=1000 44=50 60=" + t(0),
                "8 150=0 39=0 11=A-1 37=ORD-A 17=A-E1 14=0 151=1000 6=0 60=" + t(1),
                "D 11=B-1 38=100 44=10 60=" + t(2),
                "8 150=0 39=0 11=B-1 37=ORD-B 17=B-E1 14=0 151=100 6=0 60=" + t(3));
        MachineTester m = new MachineTester();
        m.machine.setMissLookup(lookup);
        m.machine.markCacheIncomplete();
        // B is looked up first, so ORD-B is in the index when A's G arrives.
        m.send("8 150=1 39=1 11=B-1 37=ORD-B 17=B-E2 14=10 151=90 6=10 32=10 31=10 60=" + t(4));
        Outcome g = m.send("G 11=A-2 41=A-1 37=ORD-B 38=1200 60=" + t(5));
        assertThat(g.key()).as("the G's 41 names A; its 37 is B's").isEqualTo("A-1");
        assertThat(m.state("A-1").pending()).extracting(PendingRequest::clOrdId).containsExactly("A-2");
        assertThat(m.state("B-1").pending()).isEmpty();
    }

    @Test
    @DisplayName("S8: the audit topics know the key but the record is gone: a MIDSTREAM chain under that key")
    void orphanUnderTheFoundKey() {
        FakeLookup lookup = firstRun(FILLED_THEN_CANCELED);
        lookup.blotter.clear();
        MachineTester m = new MachineTester();
        m.machine.setMissLookup(lookup);
        Outcome bust = m.send("8 20=1 19=E-2 17=E-5 150=4 39=4 11=X-2 41=X-1 37=ORD 14=0 151=0 6=0 60=" + t(5));
        assertThat(bust.kind()).isEqualTo(Outcome.Kind.CREATED);
        assertThat(bust.key()).isEqualTo("X-1");
        m.expect("X-1", "9018=MIDSTREAM 39=4 14=0");
        assertThat(m.count(Counter.MISS_LOOKUP_ORPHANS)).isEqualTo(1);
    }

    @Test
    @DisplayName("E47: an evicted order's late bust lands on its record via the tombstone, 9021/9023 intact (S9)")
    void evictionAndTheTombstone() {
        AtomicLong now = new AtomicLong(1_000);
        MachineTester m = new MachineTester();
        m.machine.setTerminalRetention(Duration.ofSeconds(1), now::get);
        FakeLookup lookup = new FakeLookup();
        m.machine.setMissLookup(lookup);
        for (String message : FILLED_THEN_CANCELED) {
            m.send(message);
        }
        assertThat(m.state("X-1")).isNotNull();
        now.addAndGet(1_500);
        assertThat(m.machine.evictExpired()).isEqualTo(1);
        assertThat(m.state("X-1")).isNull();
        assertThat(m.machine.keyForClOrdId("X-2")).as("ClOrdIDs leave the index").isNull();
        assertThat(m.machine.index().keyForOrderId("ORD")).as("OrderIDs are the tombstone").isEqualTo("X-1");

        lookup.blotter.put("X-1", m.record("X-1"));
        m.send("8 20=1 19=E-2 17=E-5 150=4 39=4 11=X-2 41=X-1 37=ORD 14=0 151=0 6=0 60=" + t(5));
        m.expect("X-1", "14=0 39=4 9021=X-1,X-2 9030=2 9023=6 9018=D 55=SYM");
        assertThat(m.count(Counter.EVICTIONS)).isEqualTo(1);
        assertThat(m.count(Counter.REBUILT_FROM_RECORD)).isEqualTo(1);
    }

    @Test
    @DisplayName("docs/11 F12: an order loaded finished at startup is evicted one retention after the load")
    void loadedTerminalOrdersAreEvicted() {
        MachineTester first = new MachineTester();
        for (String message : FILLED_THEN_CANCELED) {
            first.send(message);
        }
        first.send("D 11=Y-1 38=500 44=20 55=SYM 60=" + t(0));
        first.send("8 150=0 39=0 11=Y-1 37=ORD-Y 17=Y-E1 38=500 44=20 14=0 151=500 6=0 60=" + t(1));

        // A restart: the cache rebuilt from the two records, as SowCacheLoader does.
        AtomicLong now = new AtomicLong(10_000);
        MachineTester m = new MachineTester();
        m.machine.setTerminalRetention(Duration.ofSeconds(1), now::get);
        m.machine.load(RecordReader.read(first.record("X-1"), 1).state());
        m.machine.load(RecordReader.read(first.record("Y-1"), 1).state());

        now.addAndGet(500);
        assertThat(m.machine.evictExpired()).as("not before its retention has run").isZero();
        now.addAndGet(600);
        assertThat(m.machine.evictExpired()).as("the finished order, and only it").isEqualTo(1);
        assertThat(m.state("X-1")).isNull();
        assertThat(m.state("Y-1")).as("an open order is never evicted").isNotNull();
        assertThat(m.machine.keyForClOrdId("X-2")).as("its ClOrdIDs leave the index").isNull();
        assertThat(m.machine.index().keyForOrderId("ORD")).as("its OrderID is the tombstone").isEqualTo("X-1");
        assertThat(m.count(Counter.EVICTIONS)).isEqualTo(1);
    }

    @Test
    @DisplayName("E48: a late request under a new ClOrdID for an evicted order resolves through orders_audit")
    void lateRequestAfterEviction() {
        AtomicLong now = new AtomicLong(1_000);
        MachineTester m = new MachineTester();
        m.machine.setTerminalRetention(Duration.ofSeconds(1), now::get);
        FakeLookup lookup = new FakeLookup();
        m.machine.setMissLookup(lookup);
        for (String message : FILLED_THEN_CANCELED) {
            m.send(message);
        }
        now.addAndGet(1_500);
        m.machine.evictExpired();
        lookup.blotter.put("X-1", m.record("X-1"));
        lookup.audit.put("X-2", "X-1");
        Outcome late = m.send("F 11=X-9 41=X-2 60=" + t(6));
        assertThat(late.key()).isEqualTo("X-1");
        m.expect("X-1", "39=4 9016=1 15011=X-9 9021=X-1,X-2,X-9");
        assertThat(m.count(Counter.MIDSTREAM_CHAINS)).isZero();
    }

    @Test
    @DisplayName("S21: an old request resent after a lookup is an answered id, not re-added as pending")
    void resentOldRequestIsAnswered() {
        FakeLookup lookup = firstRun(FILLED_THEN_CANCELED);
        MachineTester m = new MachineTester();
        m.machine.setMissLookup(lookup);
        m.machine.markCacheIncomplete();
        Outcome resent = m.send("F 11=X-2 41=X-1 43=Y 60=" + t(3));
        assertThat(resent.reason()).isEqualTo(IgnoreReason.LATE_REQUEST);
        assertThat(m.state("X-1").pending()).isEmpty();
    }
}
