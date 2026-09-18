package com.demo.amps.qfj2.flow;

import com.demo.amps.qfj2.amps.AmpsPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import quickfix.SessionID;

/**
 * Turns {@link DestinationSpec}s into destinations. The AMPS publisher is a
 * supplier so it is only opened if an AMPS destination exists; the known
 * sessions let a fix destination that names a session this engine does not
 * have fail at startup instead of on the first message.
 */
public final class DestinationFactory {

    private DestinationFactory() {
    }

    public static List<Destination> createAll(List<DestinationSpec> specs, Supplier<AmpsPublisher> publisher,
                                              boolean flushEach, Set<SessionID> knownSessions) {
        List<Destination> destinations = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            try {
                destinations.add(create(specs.get(i), publisher, flushEach, knownSessions));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("qfj.flow.destinations[" + i + "]: " + e.getMessage(), e);
            }
        }
        return destinations;
    }

    public static Destination create(DestinationSpec spec, Supplier<AmpsPublisher> publisher, boolean flushEach,
                                     Set<SessionID> knownSessions) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("a destination needs a name");
        }
        if (spec.type() == null) {
            throw new IllegalArgumentException("destination '" + spec.name() + "' needs a type (amps or fix)");
        }
        Set<String> msgTypes = spec.msgTypes() == null ? Set.of() : Set.copyOf(spec.msgTypes());
        return switch (spec.type()) {
            case AMPS -> {
                if (spec.topic() == null || spec.topic().isBlank()) {
                    throw new IllegalArgumentException("amps destination '" + spec.name() + "' needs a topic");
                }
                yield new AmpsDestination(spec.name(), publisher.get(), spec.topic(), msgTypes, flushEach);
            }
            case FIX -> {
                if (spec.session() == null || spec.session().isBlank()) {
                    throw new IllegalArgumentException("fix destination '" + spec.name() + "' needs a session");
                }
                SessionID target;
                try {
                    target = new SessionID(spec.session());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("fix destination '" + spec.name() + "' session '"
                            + spec.session() + "' is not of the form BeginString:Sender->Target", e);
                }
                if (!knownSessions.contains(target)) {
                    throw new IllegalArgumentException("fix destination '" + spec.name() + "' names session " + target
                            + ", which is not in the QuickFIX/J settings; known sessions: " + knownSessions);
                }
                yield new FixSessionDestination(spec.name(), target, msgTypes);
            }
        };
    }
}
