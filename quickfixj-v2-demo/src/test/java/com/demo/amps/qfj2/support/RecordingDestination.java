package com.demo.amps.qfj2.support;

import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.flow.Destination;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import quickfix.Message;

/** A destination that keeps what it was given, and can be told to fail. */
public final class RecordingDestination implements Destination {

    /** One delivery: the message as rendered at delivery time (later mutations do not show). */
    public record Delivery(FixContext context, Message message, String wire) {
    }

    private final String name;
    private final Set<String> msgTypes;
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresToInject = new AtomicInteger();

    public RecordingDestination(String name) {
        this(name, Set.of());
    }

    public RecordingDestination(String name, Set<String> msgTypes) {
        this.name = name;
        this.msgTypes = Set.copyOf(msgTypes);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean accepts(String msgType) {
        return msgTypes.isEmpty() || msgTypes.contains(msgType);
    }

    @Override
    public void deliver(Message message, FixContext context) {
        if (failuresToInject.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("injected delivery failure at " + name);
        }
        deliveries.add(new Delivery(context, (Message) message.clone(), message.toString()));
    }

    /** The next {@code count} deliveries throw. */
    public void failNext(int count) {
        failuresToInject.set(count);
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    public int count() {
        return deliveries.size();
    }

    @Override
    public String toString() {
        return "recording:" + name;
    }
}
