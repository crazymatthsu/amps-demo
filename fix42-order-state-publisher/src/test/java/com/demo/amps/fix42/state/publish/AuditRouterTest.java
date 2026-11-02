package com.demo.amps.fix42.state.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.fix42.state.fix.FixFrame;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixWriter;
import com.demo.amps.fix42.state.it.OrderStateConfigurations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditRouterTest {

    private static final String URI = "tcp://127.0.0.1:9007/amps/fix";

    private static FixFrame frame(String raw) {
        byte[] bytes = raw.replace('|', '\u0001').getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return new FixFrame().wrap(bytes, 0, bytes.length);
    }

    private static String text(AuditRouter.Publish publish) {
        return publish.topic() + " " + publish.printable();
    }

    @Test
    @DisplayName("the message whole, plus 13011 as a trailer after any tag 10")
    void wholePlusTrailer() {
        AuditRouter router = new AuditRouter(OrderStateConfigurations.shipped(URI, null).routingOptions());
        FixFrame d = frame("8=FIX.4.2|9=40|35=D|11=X-1|38=100|10=123|");
        AuditRouter.Plan plan = router.plan(d, router.match("D"), "X-1");
        assertThat(plan.publishes()).extracting(AuditRouterTest::text)
                .containsExactly("sow/fix42/orders_audit 8=FIX.4.2|9=40|35=D|11=X-1|38=100|10=123|13011=X-1|");
    }

    @Test
    @DisplayName("no trailer when nothing resolved; none added when the message already carries 13011")
    void trailerRules() {
        AuditRouter router = new AuditRouter(OrderStateConfigurations.shipped(URI, null).routingOptions());
        AuditRouter.Plan unmatched = router.plan(frame("35=9|11=Z|41=Y|434=1|"), router.match("9"), null);
        assertThat(unmatched.publishes()).extracting(AuditRouterTest::text)
                .containsExactly("sow/fix42/rejects 35=9|11=Z|41=Y|434=1|");
        AuditRouter.Plan conflict = router.plan(frame("35=D|11=X|13011=OTHER|"), router.match("D"), "X");
        assertThat(conflict.keyTagConflict()).isTrue();
        assertThat(conflict.publishes().getFirst().printable()).isEqualTo("35=D|11=X|13011=OTHER|");
    }

    @Test
    @DisplayName("a report goes to execs and execs_audit; 37=NONE skips execs, a missing 17 goes to unrouted")
    void perTopicKeyCheck() {
        AuditRouter router = new AuditRouter(OrderStateConfigurations.shipped(URI, null).routingOptions());
        assertThat(router.plan(frame("35=8|37=O|17=E|150=0|"), router.match("8"), "K").publishes())
                .extracting(AuditRouter.Publish::topic).containsExactly("sow/fix42/execs", "sow/fix42/execs_audit");
        assertThat(router.plan(frame("35=8|37=NONE|17=E|150=8|"), router.match("8"), "K").publishes())
                .extracting(AuditRouter.Publish::topic).containsExactly("sow/fix42/execs_audit");
        AuditRouter.Plan statusNo17 = router.plan(frame("35=8|20=3|37=O|150=1|"), router.match("8"), "K");
        assertThat(statusNo17.publishes()).extracting(AuditRouter.Publish::topic)
                .containsExactly("sow/fix42/execs", "fix42/unrouted");
        assertThat(router.plan(frame("35=8|20=3|37=O|17=0|150=1|"), router.match("8"), "K").publishes())
                .extracting(AuditRouter.Publish::topic).containsExactly("sow/fix42/execs");
    }

    @Test
    @DisplayName("lenient: an unrouted type goes whole to fix42/unrouted; strict: it raises")
    void unrouted() {
        AuditRouter lenient = new AuditRouter(OrderStateConfigurations.shipped(URI, null).routingOptions());
        assertThat(lenient.plan(frame("35=Q|17=E|"), lenient.match("Q"), null).publishes())
                .extracting(AuditRouterTest::text).containsExactly("fix42/unrouted 35=Q|17=E|");
        AuditRouter strict = new AuditRouter(OrderStateConfigurations.shipped(URI, "demo").routingOptions());
        assertThatThrownBy(() -> strict.match("Q")).isInstanceOf(UnroutedMessageException.class);
        assertThatThrownBy(() -> strict.plan(frame("35=8|20=3|37=O|150=1|"), strict.match("8"), "K"))
                .isInstanceOf(UnroutedMessageException.class).hasMessageContaining("execs_audit");
    }

    @Test
    @DisplayName("docs/07 §5: routes split a 35=8 on 150 and 20, first match wins; strict names what has no route")
    void execTypeRouting() {
        RoutingOptions options = OrderStateConfigurations.withYaml(URI, "demo", "projection-routes.yml")
                .routingOptions();
        AuditRouter router = new AuditRouter(options);
        assertThat(router.match(frame("35=8|150=1|37=O|17=E|")).name()).isEqualTo("exec-fill");
        assertThat(router.match(frame("35=8|150=E|37=O|17=E|")).name()).isEqualTo("exec-ack");
        assertThat(router.match(frame("35=8|150=4|37=O|17=E|")).name()).isEqualTo("execution-report");
        assertThat(router.match("8").name()).as("on 35 alone: the first route for the type").isEqualTo("exec-fill");

        Route fills = new Route("fills", java.util.List.of("8"), java.util.List.of("1", "2"),
                java.util.List.of("0"), java.util.List.of("sow/fix42/execs"), java.util.List.of(), null, true, null);
        AuditRouter strict = new AuditRouter(new RoutingOptions(com.demo.amps.fix42.state.config.Mode.STRICT, 13011,
                options.topicKeys(), java.util.List.of(fills)));
        assertThat(strict.match(frame("35=8|150=1|20=0|37=O|")).name()).isEqualTo("fills");
        assertThatThrownBy(() -> strict.match(frame("35=8|150=1|20=1|37=O|")))
                .isInstanceOf(UnroutedMessageException.class).hasMessageContaining("35=8 150=1 20=1");
        assertThatThrownBy(() -> strict.match(frame("35=8|150=4|20=0|37=O|")))
                .hasMessageContaining("35=8 150=4 20=0");
    }

    @Test
    @DisplayName("docs/07 §5: a selecting route sends its tags in rule order, plus the trailer")
    void selection() {
        AuditRouter router = new AuditRouter(OrderStateConfigurations.withYaml(URI, null, "projection-routes.yml")
                .routingOptions());
        FixFrame g = frame("8=FIX.4.2|9=60|35=G|11=X-2|41=X|21=1|59=1|38=200|44=10|58=t|60=T|10=001|");
        assertThat(router.plan(g, router.match(g), "X").publishes()).extracting(AuditRouterTest::text)
                .containsExactly("sow/fix42/orders_audit 35=G|11=X-2|41=X|60=T|38=200|44=10|59=1|13011=X|");
        FixFrame noKey = frame("35=8|150=1|20=3|37=O|39=1|");
        assertThat(router.plan(noKey, router.match(noKey), "X").publishes()).extracting(AuditRouterTest::text)
                .as("the catch-all copy of a message without a topic's key stays whole")
                .containsExactly("sow/fix42/execs 35=8|37=O|39=1|150=1|13011=X|",
                        "fix42/unrouted 35=8|150=1|20=3|37=O|39=1|13011=X|");
    }

    @Test
    @DisplayName("an unterminated payload gets its separator before the trailer")
    void unterminated() {
        AuditRouter router = new AuditRouter(OrderStateConfigurations.shipped(URI, null).routingOptions());
        FixWriter writer = new FixWriter(64);
        FixMessage.parse("35=F\u000111=X-2\u000141=X\u0001").writeTo(writer);
        byte[] bytes = java.util.Arrays.copyOf(writer.buffer(), writer.size() - 1);
        AuditRouter.Plan plan = router.plan(new FixFrame().wrap(bytes, 0, bytes.length), router.match("F"), "X");
        assertThat(plan.publishes().getFirst().printable()).isEqualTo("35=F|11=X-2|41=X|13011=X|");
    }

    @Test
    @DisplayName("malformed bytes wrap as 12032 base64 and 12033 reason")
    void malformed() {
        byte[] junk = "not=fix|=".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        AuditRouter.Publish wrapped = AuditRouter.malformed("fix42/malformed", junk, 0, junk.length, "no tag");
        FixMessage message = FixMessage.parse(wrapped.payload(), 0, wrapped.length());
        assertThat(java.util.Base64.getDecoder().decode(message.value(12032))).isEqualTo(junk);
        assertThat(message.value(12033)).isEqualTo("no tag");
    }
}
