package com.demo.amps.fix42.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Startup validation of the rulebook.
 *
 * <p>Each case here is a mistake that would otherwise surface as a message
 * AMPS quietly refuses to store, hours later, with nothing in the publisher's
 * log to explain it.
 */
class Fix42PropertiesTest {

    private static final Fix42Properties.Amps AMPS =
            new Fix42Properties.Amps("tcp://127.0.0.1:9007/amps/fix", "test", 10_000);

    private static Fix42Properties properties(Map<String, List<Integer>> keys,
                                              Fix42Properties.Route... routes) {
        return new Fix42Properties(AMPS, keys, List.of(routes));
    }

    private static Fix42Properties.Route route(String name, List<String> msgTypes,
                                               PublishMode mode, List<Integer> tags,
                                               List<Integer> changeable, List<String> topics) {
        return new Fix42Properties.Route(name, msgTypes, List.of(), List.of(), mode, tags,
                changeable, topics, List.of(), null);
    }

    @Test
    @DisplayName("accepts a rulebook whose delta routes all carry their topic keys")
    void acceptsValidConfiguration() {
        Fix42Properties valid = properties(
                Map.of("sow/parent/orders", List.of(11), "sow/parent/orders_audit", List.of(11)),
                route("amend", List.of("G"), PublishMode.DELTA, List.of(35, 11, 41, 60),
                        List.of(38, 44), List.of("sow/parent/orders", "sow/parent/orders_audit")));

        assertThatCode(valid::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a delta route that omits its destination's SOW key")
    void rejectsRouteMissingTopicKey() {
        // Publishing to an execs topic keyed /37 without sending tag 37: AMPS
        // would reject every one of these, silently from the publisher's side.
        Fix42Properties broken = properties(
                Map.of("sow/parent/execs", List.of(37)),
                route("exec", List.of("8"), PublishMode.DELTA, List.of(35, 11, 39, 150),
                        List.of(), List.of("sow/parent/execs")));

        assertThatThrownBy(broken::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sow/parent/execs")
                .hasMessageContaining("[37]");
    }

    @Test
    @DisplayName("expands {scope} and checks both concrete topics")
    void checksBothScopesOfAPattern() {
        Fix42Properties broken = properties(
                Map.of("sow/parent/orders", List.of(11), "sow/child/orders", List.of(11)),
                route("amend", List.of("G"), PublishMode.DELTA, List.of(35, 41, 60),
                        List.of(38), List.of("sow/{scope}/orders")));

        assertThatThrownBy(broken::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sow/parent/orders")
                .hasMessageContaining("sow/child/orders");
    }

    @Test
    @DisplayName("rejects a topic with no declared key, since nothing can be checked")
    void rejectsUnknownTopic() {
        Fix42Properties broken = properties(
                Map.of("sow/parent/orders", List.of(11)),
                route("amend", List.of("G"), PublishMode.DELTA, List.of(35, 11),
                        List.of(), List.of("sow/parent/typo")));

        assertThatThrownBy(broken::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sow/parent/typo")
                .hasMessageContaining("topic-keys");
    }

    @Test
    @DisplayName("rejects an empty rulebook rather than dropping every message")
    void rejectsEmptyRoutes() {
        Fix42Properties empty = new Fix42Properties(AMPS, Map.of(), List.of());

        assertThatThrownBy(empty::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fix42.routes is empty");
    }

    @Test
    @DisplayName("rejects a delta route with no tags: it would publish nothing")
    void rejectsDeltaRouteWithNoTags() {
        Fix42Properties broken = properties(
                Map.of("sow/parent/orders", List.of(11)),
                route("empty", List.of("G"), PublishMode.DELTA, List.of(), List.of(),
                        List.of("sow/parent/orders")));

        assertThatThrownBy(broken::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty message");
    }

    @Test
    @DisplayName("rejects a FULL route that also lists tags, which are ignored")
    void rejectsFullRouteWithTags() {
        Fix42Properties confused = properties(
                Map.of("sow/parent/orders", List.of(11)),
                route("new-order", List.of("D"), PublishMode.FULL, List.of(35, 11), List.of(),
                        List.of("sow/parent/orders")));

        assertThatThrownBy(confused::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mode FULL publishes the whole message");
    }

    @Test
    @DisplayName("rejects a route that names no topics")
    void rejectsRouteWithNoTopics() {
        Fix42Properties broken = properties(Map.of(),
                route("nowhere", List.of("G"), PublishMode.DELTA, List.of(35, 11), List.of(),
                        List.of()));

        assertThatThrownBy(broken::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("would go nowhere");
    }

    @Test
    @DisplayName("rejects duplicate route names, which make logs ambiguous")
    void rejectsDuplicateNames() {
        Fix42Properties broken = properties(
                Map.of("sow/parent/orders", List.of(11)),
                route("amend", List.of("G"), PublishMode.DELTA, List.of(35, 11), List.of(),
                        List.of("sow/parent/orders")),
                route("amend", List.of("F"), PublishMode.DELTA, List.of(35, 11), List.of(),
                        List.of("sow/parent/orders")));

        assertThatThrownBy(broken::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate route name");
    }

    @Test
    @DisplayName("reports every problem at once, not just the first")
    void reportsAllProblems() {
        Fix42Properties broken = properties(Map.of(),
                route("a", List.of(), PublishMode.DELTA, List.of(35), List.of(),
                        List.of("sow/parent/orders")),
                route("b", List.of("F"), PublishMode.DELTA, List.of(35), List.of(), List.of()));

        assertThatThrownBy(broken::validate)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> assertThat(error.getMessage().lines().count())
                        .as("one header line plus one line per problem")
                        .isGreaterThanOrEqualTo(4));
    }

    @Test
    @DisplayName("selectedTags is identity tags then changeable tags, de-duplicated")
    void selectedTagsPreservesOrderAndDeduplicates() {
        Fix42Properties.Route route = route("amend", List.of("G"), PublishMode.DELTA,
                List.of(35, 11, 41, 60), List.of(38, 44, 60), List.of("sow/parent/orders"));

        assertThat(route.selectedTags()).containsExactly(35, 11, 41, 60, 38, 44);
    }

    @Test
    @DisplayName("a route with exec-types matches only those, and one without matches any")
    void matchesOnMsgTypeAndExecType() {
        Fix42Properties.Route fills = new Fix42Properties.Route("fills", List.of("8"),
                List.of("1", "2"), List.of(), PublishMode.DELTA, List.of(35), List.of(),
                List.of("sow/parent/execs"), List.of(), null);
        Fix42Properties.Route any = route("any", List.of("8"), PublishMode.DELTA, List.of(35),
                List.of(), List.of("sow/parent/execs"));

        assertThat(fills.matches("8", "1", "0")).isTrue();
        assertThat(fills.matches("8", "0", "0")).isFalse();
        assertThat(fills.matches("D", "", "")).isFalse();
        assertThat(any.matches("8", "0", "0")).isTrue();
        assertThat(any.matches("8", "", "")).isTrue();
    }

    @Test
    @DisplayName("a route with exec-trans-types matches only those tag 20 values")
    void matchesOnExecTransType() {
        // The bust rule: any 150, but only 20=1. A fill's 20=0 -- or a message
        // that omits tag 20 entirely -- must fall through to the fill rules.
        Fix42Properties.Route bust = new Fix42Properties.Route("exec-bust", List.of("8"),
                List.of(), List.of("1"), PublishMode.DELTA, List.of(35), List.of(),
                List.of("sow/parent/execs"), List.of(), null);

        assertThat(bust.matches("8", "1", "1")).isTrue();
        assertThat(bust.matches("8", "2", "1")).isTrue();
        assertThat(bust.matches("8", "1", "0")).isFalse();
        assertThat(bust.matches("8", "1", "")).isFalse();
    }

    @Test
    @DisplayName("rejects exec-trans-types on anything but an execution-report route")
    void rejectsExecTransTypesOnNonExecutionRoute() {
        // Tag 20 exists only on a 35=8, so this rule could never match as
        // written -- and a rule that never matches is a rule someone believes
        // is doing something.
        Fix42Properties broken = properties(
                Map.of("sow/parent/orders", List.of(11)),
                new Fix42Properties.Route("amend", List.of("G"), List.of(), List.of("1"),
                        PublishMode.DELTA, List.of(35, 11), List.of(),
                        List.of("sow/parent/orders"), List.of(), null));

        assertThatThrownBy(broken::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exec-trans-types")
                .hasMessageContaining("[\"8\"]");
    }
}
