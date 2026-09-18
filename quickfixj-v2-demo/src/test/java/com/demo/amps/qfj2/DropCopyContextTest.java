package com.demo.amps.qfj2;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.qfj2.amps.AmpsPublisher;
import com.demo.amps.qfj2.config.QfjProperties;
import com.demo.amps.qfj2.engine.FixEngine;
import com.demo.amps.qfj2.flow.AmpsDestination;
import com.demo.amps.qfj2.flow.DestinationDispatcher;
import com.demo.amps.qfj2.flow.RuleChain;
import com.demo.amps.qfj2.mock.MockExecutionFeed;
import com.demo.amps.qfj2.seqno.AmpsReplicatedFileStoreFactory;
import com.demo.amps.qfj2.seqno.SeqnoAdmin;
import com.demo.amps.qfj2.seqno.SeqnoReplicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import quickfix.MessageStoreFactory;

/**
 * The shipped dropcopy role, wired for real by Spring: every bean the flow
 * needs exists, the rules and destinations are what the YAML says, and the
 * engine is built but (test profile) not started. The two AMPS connections
 * are mocked out: nothing here needs a server.
 */
@SpringBootTest(properties = "spring.config.additional-location=file:config/dropcopy/dropcopy.yml")
@ActiveProfiles("test")
class DropCopyContextTest {

    @MockitoBean
    private AmpsPublisher ampsFixPublisher;

    @MockitoBean
    private SeqnoReplicator seqnoReplicator;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private QfjProperties properties;

    @Autowired
    private FixEngine engine;

    @Autowired
    private RuleChain ruleChain;

    @Autowired
    private DestinationDispatcher dispatcher;

    @Autowired
    private MessageChannel fixInboundChannel;

    @Autowired
    private MessageStoreFactory messageStoreFactory;

    @Test
    @DisplayName("the shipped dropcopy configuration binds, validates and wires")
    void shippedConfigurationWires() {
        assertThat(properties.role()).isEqualTo("dropcopy");
        assertThat(engine.connectionType()).isEqualTo(FixEngine.ConnectionType.INITIATOR);
        assertThat(engine.isRunning()).as("test profile: built, not started").isFalse();
        assertThat(engine.isAutoStartup()).isFalse();

        assertThat(ruleChain.rules()).hasSize(4);
        assertThat(ruleChain.describe()).contains("set-tag 5001=VENUE-DROPCOPY").contains("copy-tag 49 -> 5002");

        assertThat(dispatcher.destinations()).hasSize(2).allMatch(d -> d instanceof AmpsDestination);
        assertThat(((AmpsDestination) dispatcher.destinations().get(0)).topic()).isEqualTo("sow/dropcopy/fix42/execs");
        assertThat(dispatcher.destinations().get(0).accepts("8")).isTrue();
        assertThat(dispatcher.destinations().get(0).accepts("D")).isFalse();
        assertThat(dispatcher.destinations().get(1).accepts("D")).isTrue();

        assertThat(fixInboundChannel).isInstanceOf(DirectChannel.class);
        assertThat(context.getBean("dropCopyFlow")).isInstanceOf(IntegrationFlow.class);
        assertThat(messageStoreFactory).isInstanceOf(AmpsReplicatedFileStoreFactory.class);
        assertThat(context.getBean(SeqnoAdmin.class)).isNotNull();
        assertThat(context.getBeanProvider(MockExecutionFeed.class).getIfAvailable())
                .as("the mock feed is the venue's, not the consumer's").isNull();
    }
}
