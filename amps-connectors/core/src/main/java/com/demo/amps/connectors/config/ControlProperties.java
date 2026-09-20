package com.demo.amps.connectors.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * The control channel: a topic the application listens on for commands -- {@code reload},
 * {@code status}, and whatever {@code CommandHandler} beans the application adds.
 *
 * <pre>{@code
 * amps-connectors:
 *   control:
 *     enabled: true
 *     target: instrument-enricher       # blank -> spring.application.name
 *     accept-targets: [all]
 *     source:                           # the SAME block a connector's source: takes
 *       amps: { topic: connectors/control, mode: SUBSCRIBE }
 *       # kafka: { bootstrap-servers: kafka:9092, topic: connectors.control,
 *       #          group-id: instrument-enricher-control-${HOSTNAME:local}, from: LATEST }
 * }</pre>
 *
 * <p>Off by default: a control channel is a way in, and an application should opt into one
 * knowingly. The listener is a {@link SourceProperties source} exactly like a connector's,
 * resolved by the same driver modules, so "commands from Kafka" and "commands from AMPS" are
 * the same six lines under {@code source:} that a feed would be -- and a driver that can read
 * a feed can carry commands with no further work.
 *
 * <p>Addressing: a command's {@code to} field names the instance it is for. One with no
 * {@code to}, or {@code all}, is for everyone; otherwise it is acted on when {@code to}
 * equals this instance's {@link #getTarget() target} or is in {@link #getAcceptTargets()
 * accept-targets} -- the group names an instance answers to besides its own, so a fleet can
 * be told {@code to: enrichers} without listing every host. Anything else is ignored and
 * counted, not failed: a command for a different instance is not this one's mistake.
 *
 * <p>A Kafka control topic wants one consumer group <em>per instance</em> and
 * {@code from: LATEST}: a shared group delivers each command to one member, and a broadcast
 * that one instance receives is not a broadcast.
 */
public class ControlProperties {

    /** The name every broadcast command carries, and the one every instance answers to. */
    public static final String ALL = "all";

    /** {@code true} subscribes to the control source; the default is no control channel. */
    private boolean enabled = false;

    /**
     * The name this instance answers to in a command's {@code to}. Blank falls back to
     * {@code spring.application.name} (resolved in code, like the alerts' application
     * name, so the configuration tree never needs a placeholder for it).
     */
    private String target;

    /** Further names this instance answers to; {@code all} is answered to regardless. */
    @NotNull
    private List<String> acceptTargets = new ArrayList<>(List.of(ALL));

    /** Where the commands come from: one transport block, as a connector's {@code source:}. */
    @Valid
    @NotNull
    private SourceProperties source = new SourceProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public List<String> getAcceptTargets() {
        return acceptTargets;
    }

    public void setAcceptTargets(List<String> acceptTargets) {
        this.acceptTargets = acceptTargets == null
                ? new ArrayList<>()
                : new ArrayList<>(acceptTargets);
    }

    public SourceProperties getSource() {
        return source;
    }

    public void setSource(SourceProperties source) {
        this.source = source == null ? new SourceProperties() : source;
    }
}
