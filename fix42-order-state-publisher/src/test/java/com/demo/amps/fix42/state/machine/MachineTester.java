package com.demo.amps.fix42.state.machine;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.spi.Decision;
import com.demo.amps.fix42.state.spi.OrderStateHook;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the machine the way the publisher will -- plan, write the blotter
 * payload, commit -- and keeps what a subscriber of the record would see.
 *
 * <p>The SOW is simulated the way AMPS merges: a FULL publish replaces the
 * record, a delta overwrites the tags it carries and leaves the rest. So
 * {@link #record(String)} is the merged record a {@code sow} query would
 * return, built from the payloads alone -- which is what makes these tests
 * a check of the wire as well as of the cache.
 *
 * <p>Messages are written as {@code "8 150=0 39=0 11=X-1 …"}: the type, then
 * {@code tag=value} pairs separated by spaces.
 */
final class MachineTester {

    final OrderStateMachine machine;
    final RecordWriter writer;
    private final FixWriter source = new FixWriter(512);
    private final FixWriter out = new FixWriter(512);
    private final FixFrame frame = new FixFrame();
    private final Map<String, Map<Integer, String>> sow = new LinkedHashMap<>();
    private final List<OrderStateHook> hooks = new ArrayList<>();
    private FixMessage lastWire;
    private Outcome last;

    MachineTester() {
        this(MachineOptions.defaults());
    }

    MachineTester(MachineOptions options) {
        this.machine = new OrderStateMachine(options);
        this.writer = new RecordWriter(options);
    }

    /**
     * Runs {@code hook}'s {@code beforeApply} on every later message, before
     * the plan, the way the publisher does (docs/07 §2); a normalised frame is
     * what the machine plans and the writer passes through.
     */
    MachineTester withHook(OrderStateHook hook) {
        hooks.add(hook);
        return this;
    }

    /** Plans, publishes and commits one message. */
    Outcome send(String spec) {
        return send(parse(spec));
    }

    Outcome send(FixMessage message) {
        source.clear();
        message.writeTo(source);
        frame.wrap(source);
        FixFrame applied = frame;
        boolean vetoed = false;
        if (!hooks.isEmpty()) {
            String key = machine.resolveKey(frame);
            OrderState before = key == null ? null : machine.state(key);
            for (OrderStateHook hook : hooks) {
                Decision decision = hook.beforeApply(applied, before);
                if (decision.ignored()) {
                    vetoed = true;
                    break;
                }
                if (decision.replacement() != null) {
                    applied = decision.replacement();
                }
            }
        }
        last = vetoed ? machine.planVeto(frame, null) : machine.plan(applied);
        out.clear();
        if (writer.write(last, applied, out)) {
            lastWire = FixMessage.parse(out.asString());
            Map<Integer, String> record = sow.computeIfAbsent(last.key(), k -> new LinkedHashMap<>());
            if (last.publish() == Outcome.Publish.FULL) {
                record.clear();
            }
            lastWire.asMap().forEach(record::put);
        } else {
            lastWire = null;
        }
        machine.commit(last);
        return last;
    }

    /** The merged record as a sow query would return it, or null. */
    FixMessage record(String key) {
        Map<Integer, String> record = sow.get(key);
        if (record == null) {
            return null;
        }
        FixMessage.Builder builder = new FixMessage.Builder();
        record.forEach(builder::set);
        return builder.build();
    }

    /** The cache's whole record for {@code key}, as {@link RecordWriter#writeAll} encodes it. */
    FixMessage cached(String key) {
        FixWriter whole = new FixWriter(512);
        writer.writeAll(machine.state(key), whole);
        return FixMessage.parse(whole.asString());
    }

    /** The last blotter payload, or null when nothing was published. */
    FixMessage wire() {
        return lastWire;
    }

    Outcome last() {
        return last;
    }

    OrderState state(String key) {
        return machine.state(key);
    }

    long count(Counter counter) {
        return machine.counters().get(counter);
    }

    /**
     * Asserts the merged record carries each {@code tag=value} in
     * {@code spec}; {@code tag=-} asserts the tag is absent.
     */
    void expect(String key, String spec) {
        FixMessage record = record(key);
        org.assertj.core.api.Assertions.assertThat(record).as("record %s", key).isNotNull();
        java.util.List<String> wrong = new java.util.ArrayList<>();
        for (String token : spec.trim().split("\\s+")) {
            int eq = token.indexOf('=');
            int tag = Integer.parseInt(token.substring(0, eq));
            String expected = token.substring(eq + 1);
            String actual = record.value(tag);
            if (expected.equals("-") ? record.has(tag) : !expected.equals(actual)) {
                wrong.add(tag + "=" + (record.has(tag) ? actual : "<absent>") + " (expected " + expected + ")");
            }
        }
        org.assertj.core.api.Assertions.assertThat(wrong)
                .as("record %s after %s: %s", key, last, record.printable())
                .isEmpty();
    }

    /** Asserts the last payload carried each of {@code tags} ({@code -tag}: did not). */
    void expectWire(int... tags) {
        org.assertj.core.api.Assertions.assertThat(lastWire).as("a payload after %s", last).isNotNull();
        for (int tag : tags) {
            org.assertj.core.api.Assertions.assertThat(lastWire.has(Math.abs(tag)))
                    .as("payload %s carries %d", lastWire.printable(), Math.abs(tag))
                    .isEqualTo(tag > 0);
        }
    }

    /** {@code value(key, 39)}: one tag of the merged record. */
    String value(String key, int tag) {
        FixMessage record = record(key);
        return record == null ? "" : record.value(tag);
    }

    static FixMessage parse(String spec) {
        String[] tokens = spec.trim().split("\\s+");
        FixMessage.Builder builder = FixMessage.ofType(tokens[0]);
        for (int i = 1; i < tokens.length; i++) {
            int eq = tokens[i].indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("not tag=value: " + tokens[i]);
            }
            builder.set(Integer.parseInt(tokens[i].substring(0, eq)), tokens[i].substring(eq + 1));
        }
        return builder.build();
    }

    /** A TransactTime {@code seconds} past 10:00 on the test day, with milliseconds. */
    static String t(int seconds) {
        return String.format("20261031-10:%02d:%02d.000", seconds / 60, seconds % 60);
    }

    /** The record tags most assertions name. */
    static final int PENDING_ACTION = FixTags.Record.PENDING_ACTION;
    static final int PENDING_CL_ORD_ID = FixTags.Record.PENDING_CL_ORD_ID;
    static final int PENDING_REQUESTS = FixTags.Record.PENDING_REQUESTS;
    static final int PENDING_COUNT = FixTags.Record.PENDING_COUNT;
    static final int PENDING_QTY = FixTags.Record.PENDING_ORDER_QTY;
    static final int PENDING_PX = FixTags.Record.PENDING_PRICE;
    static final int TERMINAL = FixTags.Record.TERMINAL;
    static final int WORKING = FixTags.Record.WORKING_CL_ORD_ID;
}
