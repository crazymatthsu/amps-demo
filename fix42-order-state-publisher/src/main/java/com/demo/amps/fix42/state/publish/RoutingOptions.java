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
 * @param mode              {@code STRICT} raises on an unrouted type or a
 *                          missing topic key; {@code LENIENT} sends it to the
 *                          catch-all route
 * @param keyTag            appended to every pass-through publish as a
 *                          trailer; 0 disables
 * @param topicKeys         the key tags of every topic; a topic with none is
 *                          unkeyed
 * @param routes            first match wins; a catch-all route, if any, is last
 * @param fullPublishTopics topic-name patterns ({@code *} matches anything)
 *                          that always receive the message whole, as a
 *                          {@code publish}, whatever a route selects: the
 *                          audit trail records what was received
 */
public record RoutingOptions(Mode mode, int keyTag, Map<String, List<Integer>> topicKeys, List<Route> routes,
                             List<String> fullPublishTopics) {

    /** The shipped rule: every {@code *_audit} topic is a full publish. */
    public static final List<String> DEFAULT_FULL_PUBLISH_TOPICS = List.of("*_audit");

    public RoutingOptions {
        topicKeys = topicKeys == null ? Map.of() : topicKeys;
        routes = List.copyOf(routes);
        fullPublishTopics = fullPublishTopics == null ? DEFAULT_FULL_PUBLISH_TOPICS : List.copyOf(fullPublishTopics);
    }

    /** With {@link #DEFAULT_FULL_PUBLISH_TOPICS}. */
    public RoutingOptions(Mode mode, int keyTag, Map<String, List<Integer>> topicKeys, List<Route> routes) {
        this(mode, keyTag, topicKeys, routes, DEFAULT_FULL_PUBLISH_TOPICS);
    }

    /** The key tags of {@code topic}; empty for a non-SOW topic. */
    public List<Integer> keysOf(String topic) {
        return topicKeys.getOrDefault(topic, List.of());
    }

    /** Whether {@code topic} always receives the message whole: it matches a {@code fullPublishTopics} pattern. */
    public boolean fullPublish(String topic) {
        return fullPublish(fullPublishTopics, topic);
    }

    /** Whether {@code topic} matches one of {@code patterns}, where {@code *} matches any run of characters. */
    public static boolean fullPublish(List<String> patterns, String topic) {
        for (String pattern : patterns) {
            if (globMatches(pattern, topic)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatches(String pattern, String text) {
        String[] parts = pattern.split("\\*", -1);
        if (parts.length == 1) {
            return pattern.equals(text);
        }
        if (!text.startsWith(parts[0])) {
            return false;
        }
        int at = parts[0].length();
        for (int i = 1; i < parts.length - 1; i++) {
            int found = text.indexOf(parts[i], at);
            if (found < 0) {
                return false;
            }
            at = found + parts[i].length();
        }
        String last = parts[parts.length - 1];
        return text.length() - at >= last.length() && text.endsWith(last);
    }
}
