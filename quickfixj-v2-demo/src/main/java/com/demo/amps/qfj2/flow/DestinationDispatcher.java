package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.engine.FixMessages;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;

/**
 * Hands an enriched message to every destination that accepts its type, in
 * order, stopping at the first failure. Counts what went where.
 *
 * <p>Stopping at the first failure is deliberate: the message is going to be
 * redelivered, and a destination that already took it will take it again.
 * The AMPS topics are built for that (a SOW keyed on ExecID overwrites, the
 * journal simply holds both); a FIX destination will forward it twice, so a
 * fix destination is best listed first.
 */
public final class DestinationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DestinationDispatcher.class);

    private final List<Destination> destinations;
    private final Map<String, LongAdder> delivered = new ConcurrentHashMap<>();
    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong unrouted = new AtomicLong();

    public DestinationDispatcher(List<Destination> destinations) {
        this.destinations = List.copyOf(destinations);
    }

    public void dispatch(Message message, FixContext context) {
        String msgType = FixMessages.msgType(message);
        dispatched.incrementAndGet();
        int matched = 0;
        for (Destination destination : destinations) {
            if (!destination.accepts(msgType)) {
                continue;
            }
            matched++;
            try {
                destination.deliver(message, context);
            } catch (Exception e) {
                throw new DeliveryException(destination.name(), message, e);
            }
            delivered.computeIfAbsent(destination.name(), name -> new LongAdder()).increment();
        }
        if (matched == 0) {
            unrouted.incrementAndGet();
            log.debug("no destination accepts 35={} from {}", msgType, context.sessionId());
        }
    }

    public List<Destination> destinations() {
        return destinations;
    }

    /** Successful deliveries to the named destination. */
    public long deliveredCount(String destination) {
        LongAdder count = delivered.get(destination);
        return count == null ? 0 : count.sum();
    }

    /** Messages that reached the dispatcher. */
    public long dispatchedCount() {
        return dispatched.get();
    }

    /** Messages no destination accepted. */
    public long unroutedCount() {
        return unrouted.get();
    }

    public String describe() {
        return destinations.isEmpty()
                ? "(no destinations)"
                : destinations.stream().map(Object::toString).collect(Collectors.joining("; "));
    }
}
