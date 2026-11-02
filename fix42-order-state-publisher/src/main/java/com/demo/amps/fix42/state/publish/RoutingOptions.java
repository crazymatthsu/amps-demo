package com.demo.amps.fix42.state.publish;

import com.demo.amps.fix42.state.config.Mode;
import java.util.List;
import java.util.Map;

/**
 * The {@link AuditRouter}'s constructor options: the pass-through routing,
 * plain, so the router needs no configuration binder.
 * {@code OrderStateProperties.routingOptions()} maps the shipped
 * {@code application.yml} onto it.
 *
 * <p>{@code topicKeys} and the routes' {@code key-guard} are SOW ideas: they
 * skip a topic whose key a message lacks, or whose key is a sentinel that would
 * collapse records. A sink that keys nothing may declare every topic with no
 * keys (docs/07 §3).
 *
 * @param mode      {@code STRICT} raises on an unrouted type or a missing topic
 *                  key; {@code LENIENT} sends it to the catch-all route
 * @param keyTag    appended to every pass-through publish as a trailer; 0 disables
 * @param topicKeys the key tags of every topic; a topic with none is unkeyed
 * @param routes    first match wins; a catch-all route, if any, is last
 */
public record RoutingOptions(Mode mode, int keyTag, Map<String, List<Integer>> topicKeys, List<Route> routes) {

    public RoutingOptions {
        topicKeys = topicKeys == null ? Map.of() : topicKeys;
        routes = List.copyOf(routes);
    }

    /** The key tags of {@code topic}; empty for a non-SOW topic. */
    public List<Integer> keysOf(String topic) {
        return topicKeys.getOrDefault(topic, List.of());
    }
}
