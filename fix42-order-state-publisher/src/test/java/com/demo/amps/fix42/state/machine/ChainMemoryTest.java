package com.demo.amps.fix42.state.machine;

import static com.demo.amps.fix42.state.machine.MachineTester.t;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.it.OrderStateConfigurations;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

/**
 * docs/05 phase 5: what the cache holds per order, and what a long chain
 * costs, measured with JOL rather than estimated.
 *
 * <p>The record publishes at most 50 ClOrdIDs in 12021, but the cache keeps
 * every one: the entry's id list and one index binding per id, so a lookup by
 * any of them resolves (docs/03 §3). And every message plans on a copy of
 * its order's entry (the two-phase apply, docs/04 §6), id list included, so a
 * long chain costs memory to hold and allocation on every later message.
 *
 * <p>Sizes are what the machine's object graph grows by, measured from the
 * machine itself, so the index, the entries and every string they share are
 * counted once. Each figure is printed and pinned about 25% above what was
 * measured (README "What it costs").
 */
class ChainMemoryTest {

    private static final MachineOptions OPTIONS =
            OrderStateConfigurations.shipped("tcp://127.0.0.1:9007/amps/fix", "demo").machineOptions();

    /**
     * About 25% above what was measured on 2026-11-01: 1,545 bytes per
     * typical order, 102,888 for the 1,000-ClOrdID chain, and 7,808 per fill
     * on it (3,840 on a one-id order).
     */
    static final long TYPICAL_ORDER_CEILING = 1_950;
    static final long LONG_CHAIN_CEILING = 129_000;
    static final long FILL_ON_LONG_CHAIN_CEILING = 9_800;
    /** About 25% above the 238 bytes per evicted order measured on 2026-11-01 (docs/11 F10). */
    static final long TOMBSTONE_CEILING = 300;

    private final FixFrame frame = new FixFrame();
    private final FixWriter out = new FixWriter(2048);
    private final RecordWriter writer = new RecordWriter(OPTIONS);

    @Test
    @DisplayName("a typical order (D, ack, partial, fill) retains a few KB in the cache")
    void typicalOrder() {
        OrderStateMachine machine = new OrderStateMachine(OPTIONS);
        long empty = retained(machine);
        int orders = 1_000;
        for (int i = 0; i < orders; i++) {
            applyAll(machine, typicalOrder(String.format("ORD-20261101-%06d", i)));
        }
        long perOrder = (retained(machine) - empty) / orders;
        report("a typical order retains", perOrder, TYPICAL_ORDER_CEILING);
    }

    @Test
    @DisplayName("an evicted order still holds a tombstone (its key and OrderIDs) until the process restarts")
    void tombstone() {
        AtomicLong now = new AtomicLong(0);
        OrderStateMachine machine = new OrderStateMachine(OPTIONS);
        machine.setTerminalRetention(Duration.ofMillis(1), now::get);
        long empty = retained(machine);
        int orders = 1_000;
        for (int i = 0; i < orders; i++) {
            applyAll(machine, typicalOrder(String.format("ORD-20261101-%06d", i)));
        }
        now.set(1_000_000);
        assertThat(machine.evictExpired()).isEqualTo(orders);
        assertThat(machine.size()).as("every body is gone").isZero();
        assertThat(machine.index().keyForOrderId("VORD-20261101-000000")).as("the OrderID still resolves")
                .isEqualTo("ORD-20261101-000000");
        long perTombstone = (retained(machine) - empty) / orders;
        report("an evicted order's tombstone retains", perTombstone, TOMBSTONE_CEILING);
    }

    @Test
    @DisplayName("an order amended 999 times (1,000 ClOrdIDs) retains every id, and makes each later message dearer")
    void thousandClOrdIdChain() {
        OrderStateMachine machine = new OrderStateMachine(OPTIONS);
        long empty = retained(machine);
        applyAll(machine, longChain("LONG", 1_000));
        String key = machine.keyForClOrdId("LONG-0000");
        assertThat(machine.state(key).clOrdIds()).hasSize(1_000);
        assertThat(machine.keyForClOrdId("LONG-0999")).as("the newest id resolves").isEqualTo(key);
        assertThat(machine.keyForClOrdId("LONG-0001")).as("so does an old one").isEqualTo(key);
        long chain = retained(machine) - empty;
        report("a 1,000-ClOrdID chain retains", chain, LONG_CHAIN_CEILING);

        // The same fills on a short order and on the long one: the difference
        // is the per-message copy of the id list.
        OrderStateMachine shortMachine = new OrderStateMachine(OPTIONS);
        applyAll(shortMachine, longChain("SHORT", 1));
        List<byte[]> warm = fills("SHORT-0000", "ORD-SHORT", 1, 300);
        applyAll(shortMachine, warm);
        applyAll(machine, fills("LONG-0999", "ORD-LONG", 1, 300));
        long onShort = allocatedPerMessage(shortMachine, fills("SHORT-0000", "ORD-SHORT", 301, 300));
        long onLong = allocatedPerMessage(machine, fills("LONG-0999", "ORD-LONG", 301, 300));
        System.out.printf("  a fill allocates %,d bytes on a 1-id order and %,d on a 1,000-id one%n",
                onShort, onLong);
        assertThat(onLong).as("the copy of 1,000 ids is visible per message").isGreaterThan(onShort + 3_000);
        report("a fill on the 1,000-ClOrdID chain allocates", onLong, FILL_ON_LONG_CHAIN_CEILING);
    }

    private static void report(String what, long bytes, long ceiling) {
        System.out.printf("  %s %,d bytes (ceiling %,d)%n", what, bytes, ceiling);
        assertThat(bytes).as(what).isLessThanOrEqualTo(ceiling);
    }

    private static long retained(OrderStateMachine machine) {
        return GraphLayout.parseInstance(machine).totalSize();
    }

    private long allocatedPerMessage(OrderStateMachine machine, List<byte[]> messages) {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "needs HotSpot's thread MXBean");
        com.sun.management.ThreadMXBean memory = (com.sun.management.ThreadMXBean) bean;
        long before = memory.getCurrentThreadAllocatedBytes();
        applyAll(machine, messages);
        return (memory.getCurrentThreadAllocatedBytes() - before) / messages.size();
    }

    private void applyAll(OrderStateMachine machine, List<byte[]> messages) {
        for (byte[] message : messages) {
            frame.wrap(message, 0, message.length);
            Outcome outcome = machine.plan(frame);
            assertThat(outcome.kind()).as("%s", outcome).isNotEqualTo(Outcome.Kind.IGNORED);
            out.clear();
            writer.write(outcome, frame, out);
            machine.commit(outcome);
        }
    }

    /** D for 1,000 @ 50, the ack, a fill of 400 and one of 600, under ids like a desk's. */
    private static List<byte[]> typicalOrder(String id) {
        String o = "V" + id;
        return wire(
                "D 11=" + id + " 38=1000 44=50 1=ACC-INSTL-01 55=AAPL 54=1 40=2 59=0 21=1 60=" + t(0),
                "8 150=0 39=0 11=" + id + " 37=" + o + " 17=" + id + "-E1 38=1000 44=50 14=0 151=1000 6=0 60=" + t(1),
                "8 150=1 39=1 11=" + id + " 37=" + o + " 17=" + id + "-E2 38=1000 44=50 14=400 151=600 6=50 32=400"
                        + " 31=50 30=XNAS 60=" + t(2),
                "8 150=2 39=2 11=" + id + " 37=" + o + " 17=" + id + "-E3 38=1000 44=50 14=1000 151=0 6=50 32=600"
                        + " 31=50 30=XNAS 60=" + t(3));
    }

    /** D, the ack, then {@code ids - 1} amends each acknowledged: ClOrdIDs {@code prefix-0000} on. */
    private static List<byte[]> longChain(String prefix, int ids) {
        List<String> specs = new ArrayList<>();
        String o = "ORD-" + prefix;
        specs.add("D 11=" + prefix + "-0000 38=1000000 44=50 1=ACC-INSTL-01 55=AAPL 54=1 40=2 59=0 60=" + t(0));
        specs.add("8 150=0 39=0 11=" + prefix + "-0000 37=" + o + " 17=" + prefix + "-A0 38=1000000 44=50 14=0"
                + " 151=1000000 6=0 60=" + t(1));
        for (int i = 1; i < ids; i++) {
            String id = String.format("%s-%04d", prefix, i);
            String previous = String.format("%s-%04d", prefix, i - 1);
            long qty = 1_000_000 + i;
            specs.add("G 11=" + id + " 41=" + previous + " 38=" + qty + " 44=50 60=" + t(1));
            specs.add("8 150=5 39=0 11=" + id + " 41=" + previous + " 37=" + o + " 17=" + prefix + "-A" + i
                    + " 38=" + qty + " 44=50 14=0 151=" + qty + " 6=0 60=" + t(1));
        }
        return wire(specs.toArray(String[]::new));
    }

    /** {@code count} fills of 1 under the working id, ExecIDs from {@code from}. */
    private static List<byte[]> fills(String workingId, String orderId, int from, int count) {
        String[] specs = new String[count];
        for (int i = 0; i < count; i++) {
            int n = from + i;
            specs[i] = "8 150=1 39=1 11=" + workingId + " 37=" + orderId + " 17=" + workingId + "-F" + n
                    + " 14=" + n + " 151=" + (999_000 - n) + " 6=50 32=1 31=50 60=" + t(2);
        }
        return wire(specs);
    }

    private static List<byte[]> wire(String... specs) {
        List<byte[]> messages = new ArrayList<>(specs.length);
        for (String spec : specs) {
            messages.add(MachineTester.parse(spec).render().getBytes(StandardCharsets.ISO_8859_1));
        }
        return messages;
    }
}
