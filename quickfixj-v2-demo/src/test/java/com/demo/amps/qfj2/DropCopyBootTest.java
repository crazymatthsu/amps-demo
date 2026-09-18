package com.demo.amps.qfj2;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.qfj2.engine.FixEngine;
import com.demo.amps.qfj2.mock.ExecutionReports;
import com.demo.amps.qfj2.support.EngineHarness;
import com.demo.amps.qfj2.support.RecordingDestination;
import com.demo.amps.qfj2.support.TestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import quickfix.SessionID;

/**
 * The real Spring wiring, end to end: the context starts an acceptor, a
 * hand-built initiator logs on to it, and a message it sends comes out of
 * the {@code IntegrationFlow} enriched, at a destination bean. No AMPS: the
 * store is the plain file store and the destination is a recorder.
 */
@SpringBootTest(properties = {
        "qfj.engine.auto-start=true",
        "qfj.seqno.enabled=false",
        "qfj.flow.rules[0].type=set-tag",
        "qfj.flow.rules[0].tag=5001",
        "qfj.flow.rules[0].value=BOOT",
        "qfj.flow.rules[1].type=source-session",
        "qfj.flow.rules[1].tag=5003",
})
@ActiveProfiles("test")
class DropCopyBootTest {

    private static Path work;
    private static int port;

    @DynamicPropertySource
    static void acceptorSettings(DynamicPropertyRegistry registry) throws Exception {
        work = TestPaths.freshWorkDir("boot");
        port = EngineHarness.freePort();
        Path cfg = work.resolve("acceptor.cfg");
        Files.writeString(cfg, """
                [default]
                ConnectionType=acceptor
                SocketAcceptPort=%d
                SocketAcceptAddress=127.0.0.1
                FileStorePath=%s
                UseDataDictionary=Y
                DataDictionary=%s
                ValidateUserDefinedFields=N
                AllowUnknownMsgFields=Y
                NonStopSession=Y
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=1

                [session]
                BeginString=FIX.4.2
                SenderCompID=VENUE
                TargetCompID=DROPCOPY
                """.formatted(port, work.resolve("store"), TestPaths.dictionary()));
        registry.add("qfj.engine.settings", cfg::toString);
    }

    @TestConfiguration
    static class Recorder {
        @Bean
        RecordingDestination recording() {
            return new RecordingDestination("recording");
        }
    }

    @Autowired
    private FixEngine engine;

    @Autowired
    private RecordingDestination recording;

    @Test
    @DisplayName("a message received by the Spring-wired engine comes out of the flow enriched")
    void messageFlowsThroughTheSpringWiring() throws Exception {
        SessionID venueSide = new SessionID("FIX.4.2", "VENUE", "DROPCOPY");
        assertThat(engine.isRunning()).isTrue();
        assertThat(engine.connectionType()).isEqualTo(FixEngine.ConnectionType.ACCEPTOR);

        try (EngineHarness consumer = EngineHarness.initiator(work.resolve("consumer"), "127.0.0.1", port)
                .session("DROPCOPY", "VENUE")
                .build()
                .start()) {
            consumer.awaitLogon();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> engine.isLoggedOn(venueSide));

            consumer.send(ExecutionReports.sample("BOOT", 1, "AAPL"));

            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> recording.count() == 1);
            RecordingDestination.Delivery delivery = recording.deliveries().get(0);
            assertThat(delivery.context().sessionId()).isEqualTo(venueSide);
            assertThat(delivery.wire())
                    .contains("17=EXEC-BOOT-1")
                    .contains("5001=BOOT")
                    .contains("5003=FIX.4.2:VENUE->DROPCOPY");
        }
    }
}
