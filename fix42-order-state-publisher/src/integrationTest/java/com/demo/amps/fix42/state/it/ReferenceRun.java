package com.demo.amps.fix42.state.it;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.machine.MachineOptions;
import com.demo.amps.fix42.state.machine.OrderStateMachine;
import com.demo.amps.fix42.state.machine.Outcome;
import com.demo.amps.fix42.state.machine.RecordWriter;
import com.demo.amps.fix42.state.mock.FixEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The uninterrupted run an interrupted one is compared with: the same events
 * through one machine, in process, with AMPS's merge simulated -- a publish
 * replaces the record, a delta overwrites the tags it carries.
 */
final class ReferenceRun {

    final Map<String, Map<Integer, String>> records = new LinkedHashMap<>();

    static ReferenceRun of(List<FixEvent> events, MachineOptions options) {
        ReferenceRun run = new ReferenceRun();
        OrderStateMachine machine = new OrderStateMachine(options);
        RecordWriter writer = new RecordWriter(options);
        FixWriter source = new FixWriter(512);
        FixWriter out = new FixWriter(1024);
        for (FixEvent event : events) {
            source.clear();
            event.message().writeTo(source);
            FixFrame frame = new FixFrame().wrap(source);
            Outcome outcome = machine.plan(frame);
            out.clear();
            if (writer.write(outcome, frame, out)) {
                Map<Integer, String> record = run.records.computeIfAbsent(outcome.key(), k -> new LinkedHashMap<>());
                if (outcome.publish() == Outcome.Publish.FULL) {
                    record.clear();
                }
                record.putAll(FixMessage.parse(out.asString()).asMap());
            }
            machine.commit(outcome);
        }
        return run;
    }

    /**
     * Every tag on which {@code actual} -- the blotter as AMPS holds it --
     * differs from this run's records, as {@code key tag=actual (expected)}.
     */
    List<String> differences(Map<String, FixMessage> actual) {
        List<String> differences = new ArrayList<>();
        for (String key : new TreeSet<>(records.keySet())) {
            FixMessage record = actual.get(key);
            if (record == null) {
                differences.add(key + ": no record");
                continue;
            }
            Map<Integer, String> expected = records.get(key);
            for (int tag : new TreeSet<>(union(expected.keySet(), record.asMap().keySet()))) {
                String want = expected.getOrDefault(tag, "<absent>");
                String got = record.has(tag) ? record.value(tag) : "<absent>";
                if (!want.equals(got)) {
                    differences.add(key + " " + tag + "=" + got + " (" + want + ")");
                }
            }
        }
        for (String key : actual.keySet()) {
            if (!records.containsKey(key)) {
                differences.add(key + ": an extra record " + actual.get(key).value(FixTags.Record.CHAIN_ORIGIN));
            }
        }
        return differences;
    }

    private static <T> List<T> union(java.util.Collection<T> a, java.util.Collection<T> b) {
        List<T> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** Every chain's first half of events, then every chain's second half: a restart between them. */
    static List<List<FixEvent>> halves(List<com.demo.amps.fix42.state.mock.OrderChain> chains) {
        List<FixEvent> first = new ArrayList<>();
        List<FixEvent> second = new ArrayList<>();
        for (com.demo.amps.fix42.state.mock.OrderChain chain : chains) {
            List<FixEvent> events = chain.events();
            int half = (events.size() + 1) / 2;
            first.addAll(events.subList(0, half));
            second.addAll(events.subList(half, events.size()));
        }
        return List.of(first, second);
    }
}
