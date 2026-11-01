package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.it.OrderStateConfigurations;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live heap a day's orders take, measured at scale rather than
 * extrapolated from a thousand: {@code ./gradlew
 * :fix42-order-state-publisher:scaleMemory}. Not a test: it runs in a JVM of
 * its own, since the test workers' heap is too small (README "Heap for a day's
 * flow").
 *
 * <p>Each scenario pushes {@code scale.orders} orders (default one million)
 * through a fresh machine, then reads the heap after full collections. The
 * figure covers everything the cache holds: entries, both index maps and their
 * tables, the key set, and every string. Ids are a desk's length: 21-character
 * ClOrdIDs, 14-character OrderIDs, 24-character ExecIDs. Run it again with
 * other JVM flags to compare, for example
 * {@code -PscaleJvmArgs=-XX:-UseCompressedOops} for a heap above 32 GB.
 */
final class ScaleMemoryMeasurement {

    private static final MachineOptions OPTIONS =
            OrderStateConfigurations.shipped("tcp://127.0.0.1:9007/amps/fix", "demo").machineOptions();
    private static final int ORDERS = Integer.getInteger("scale.orders", 1_000_000);

    private final FixFrame frame = new FixFrame();
    private final FixWriter out = new FixWriter(2048);
    private final RecordWriter writer = new RecordWriter(OPTIONS);

    public static void main(String[] args) throws Exception {
        System.out.printf("%n  %,d orders per scenario; JVM flags %s; max heap %,d MB%n", ORDERS,
                ManagementFactory.getRuntimeMXBean().getInputArguments(), Runtime.getRuntime().maxMemory() >> 20);
        ScaleMemoryMeasurement measurement = new ScaleMemoryMeasurement();
        measurement.scenario("finished order, 1 ClOrdID, kept in the cache", 0, false);
        measurement.scenario("finished order, 5 ClOrdIDs (4 amends), kept", 4, false);
        measurement.scenario("open order, 1 ClOrdID", -1, false);
        measurement.scenario("finished order, evicted: its tombstone", 0, true);
    }

    private void scenario(String label, int amends, boolean evict) throws Exception {
        AtomicLong now = new AtomicLong(0);
        long base = liveHeap();
        OrderStateMachine machine = new OrderStateMachine(OPTIONS);
        if (evict) {
            machine.setTerminalRetention(Duration.ofMillis(1), now::get);
        }
        long ids = 0;
        for (int i = 0; i < ORDERS; i++) {
            ids += order(machine, i, amends);
        }
        if (evict) {
            now.set(Long.MAX_VALUE / 2);
            machine.evictExpired();
        }
        long live = liveHeap() - base;
        System.out.printf("  %-46s %,7.0f bytes per order  %,7.0f per ClOrdID  %,6.1f GB per 50M ClOrdIDs%n",
                label, (double) live / ORDERS, (double) live / ids, live / (double) ids * 50e6 / 1e9);
        if (machine.size() < 0) {
            throw new AssertionError("keeps the machine reachable until it has been measured");
        }
    }

    /** One order's messages; returns its ClOrdIDs. {@code amends} -1 leaves it open after a partial fill. */
    private long order(OrderStateMachine machine, int n, int amends) {
        String id = String.format("ORD-20261101-%08d", n);
        String venue = String.format("VEN-%010d", n);
        apply(machine, "35=D|11=" + id + "|1=ACC-INSTL-01|55=AAPL|54=1|38=1000|40=2|44=50|59=0|21=1"
                + "|60=20261101-13:30:00.000|");
        apply(machine, "35=8|150=0|39=0|11=" + id + "|37=" + venue + "|17=" + id + "-E0|38=1000|44=50|14=0"
                + "|151=1000|6=0|60=20261101-13:30:00.100|");
        String working = id;
        long qty = 1000;
        for (int a = 1; a <= amends; a++) {
            String next = id + "-" + a;
            qty += 100;
            apply(machine, "35=G|11=" + next + "|41=" + working + "|38=" + qty + "|44=50|60=20261101-13:30:01.000|");
            apply(machine, "35=8|150=5|39=0|11=" + next + "|41=" + working + "|37=" + venue + "|17=" + id + "-A" + a
                    + "|38=" + qty + "|44=50|14=0|151=" + qty + "|6=0|60=20261101-13:30:01.100|");
            working = next;
        }
        apply(machine, "35=8|150=1|39=1|11=" + working + "|37=" + venue + "|17=" + id + "-E1|38=" + qty
                + "|44=50|14=400|151=" + (qty - 400) + "|6=50|32=400|31=50|30=XNAS|60=20261101-13:30:02.000|");
        if (amends >= 0) {
            apply(machine, "35=8|150=2|39=2|11=" + working + "|37=" + venue + "|17=" + id + "-E2|38=" + qty
                    + "|44=50|14=" + qty + "|151=0|6=50|32=" + (qty - 400) + "|31=50|30=XNAS"
                    + "|60=20261101-13:30:03.000|");
        }
        return Math.max(amends, 0) + 1;
    }

    private void apply(OrderStateMachine machine, String raw) {
        byte[] bytes = raw.replace('|', '\u0001').getBytes(StandardCharsets.ISO_8859_1);
        frame.wrap(bytes, 0, bytes.length);
        Outcome outcome = machine.plan(frame);
        if (outcome.kind() == Outcome.Kind.IGNORED) {
            throw new IllegalStateException(outcome + " for " + raw);
        }
        out.clear();
        writer.write(outcome, frame, out);
        machine.commit(outcome);
    }

    private static long liveHeap() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(200);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
