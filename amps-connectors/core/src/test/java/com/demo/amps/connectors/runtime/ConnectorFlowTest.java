package com.demo.amps.connectors.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.amps.AmpsPublisherFactory;
import com.demo.amps.connectors.amps.RecordingAmpsPublisher;
import com.demo.amps.connectors.source.FakeRecordSource;
import com.demo.amps.connectors.source.FakeSourceFactory;
import com.demo.amps.connectors.source.SourceRecord;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The whole flow in a real application context: two connectors, a source the test pushes
 * records into, and a publisher that records instead of connecting.
 *
 * <p>This is the test that covers what the unit tests cannot -- that Spring Integration is
 * wired the way the design says. One connector releases its batches by <em>size</em> and the
 * other by <em>idle time</em>, which are the two halves of the batching contract, and both are
 * asserted through the same publisher the production code uses: the commands it received, the
 * single flush per batch, and the acknowledgments that followed it.
 *
 * <p>The last case is the one that is easy to get wrong and expensive to discover later: when
 * the application stops, the records already read but not yet batched have to be published,
 * not dropped. That is what {@code ConnectorManager.stop()} asserts here.
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "amps-connectors.status-interval=1h",
        // Released by size: three records make a batch, and the idle timer never fires.
        "amps-connectors.connectors[0].name=bysize",
        "amps-connectors.connectors[0].format=JSON",
        "amps-connectors.connectors[0].source.tcp.mode=LISTEN",
        "amps-connectors.connectors[0].source.tcp.port=15001",
        "amps-connectors.connectors[0].amps.topic=sow/test/bysize",
        "amps-connectors.connectors[0].amps.message-type=json",
        "amps-connectors.connectors[0].amps.key.mode=PUBLISHER",
        "amps-connectors.connectors[0].amps.key.fields[0]=id",
        "amps-connectors.connectors[0].amps.batch.max-messages=3",
        "amps-connectors.connectors[0].amps.batch.flush-interval=1h",
        // Released by the idle timer: the batch is nowhere near full when it goes.
        "amps-connectors.connectors[1].name=bytimeout",
        "amps-connectors.connectors[1].format=JSON",
        "amps-connectors.connectors[1].source.tcp.mode=LISTEN",
        "amps-connectors.connectors[1].source.tcp.port=15002",
        "amps-connectors.connectors[1].amps.topic=sow/test/bytimeout",
        "amps-connectors.connectors[1].amps.message-type=json",
        "amps-connectors.connectors[1].amps.key.mode=PUBLISHER",
        "amps-connectors.connectors[1].amps.key.fields[0]=id",
        "amps-connectors.connectors[1].amps.batch.max-messages=500",
        "amps-connectors.connectors[1].amps.batch.flush-interval=150ms"
})
// Each case drives the connectors and one of them stops them, so every method gets its own
// context rather than inheriting whatever the last one left running.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConnectorFlowTest {

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @Autowired
    private Fakes fakes;

    @Autowired
    private ConnectorManager manager;

    /** The test doubles, wired in place of the real source modules and the real AMPS client. */
    @TestConfiguration
    static class Fakes {

        private final FakeRecordSource bySize = new FakeRecordSource();
        private final FakeRecordSource byTimeout = new FakeRecordSource();
        private final Map<String, RecordingAmpsPublisher> publishers = new ConcurrentHashMap<>();

        @Bean
        FakeSourceFactory bySizeFactory() {
            return new FakeSourceFactory(bySize, connector -> "bysize".equals(connector.getName()));
        }

        @Bean
        FakeSourceFactory byTimeoutFactory() {
            return new FakeSourceFactory(
                    byTimeout, connector -> "bytimeout".equals(connector.getName()));
        }

        /** One recording publisher per connector, exactly as the real factory does it. */
        @Bean
        AmpsPublisherFactory recordingPublishers() {
            return connector -> publishers.computeIfAbsent(
                    connector.getName(), name -> new RecordingAmpsPublisher());
        }

        RecordingAmpsPublisher publisher(String connector) {
            return publishers.get(connector);
        }

        void clearAll() {
            publishers.values().forEach(RecordingAmpsPublisher::clear);
        }
    }

    /**
     * Every test starts from a running manager and empty recordings: the tests assert exact
     * counts, and one of them stops the manager, so the order JUnit happens to pick must not
     * be what keeps them green.
     */
    @BeforeEach
    void freshStart() {
        if (!manager.isRunning()) {
            manager.start();
        }
        fakes.clearAll();
    }

    private static SourceRecord record(String id, AtomicInteger acks) {
        return SourceRecord.of("{\"id\":\"" + id + "\"}", id).withAck(acks::incrementAndGet);
    }

    @Test
    @DisplayName("every configured connector starts, connects and subscribes")
    void startsEveryConnector() {
        assertThat(manager.connectors()).extracting(Connector::name)
                .containsExactly("bysize", "bytimeout");
        assertThat(manager.connectors()).allMatch(Connector::isStarted);
        assertThat(fakes.publisher("bysize").isConnected()).isTrue();
        assertThat(fakes.bySize.startCount()).isEqualTo(1);
        assertThat(manager.status()).contains("bysize", "sow/test/bysize", "RUNNING");
    }

    @Test
    @DisplayName("a full batch is published on the source's own thread, with one flush")
    void releasesABatchBySize() {
        AtomicInteger acks = new AtomicInteger();
        RecordingAmpsPublisher publisher = fakes.publisher("bysize");

        fakes.bySize.emit(record("K-1", acks));
        fakes.bySize.emit(record("K-2", acks));
        assertThat(publisher.calls()).isEmpty();
        assertThat(acks.get()).isZero();

        // The third record fills the batch, and the publish happens before emit() returns:
        // that synchronous hand-off is what back-pressures a fast source.
        fakes.bySize.emit(record("K-3", acks));
        assertThat(publisher.calls()).hasSize(3);
        assertThat(publisher.flushCount()).isEqualTo(1);
        assertThat(acks.get()).isEqualTo(3);

        fakes.bySize.emit(record("K-4", acks));
        fakes.bySize.emit(record("K-5", acks));
        fakes.bySize.emit(record("K-6", acks));
        assertThat(publisher.calls()).hasSize(6);
        assertThat(publisher.flushCount()).isEqualTo(2);
        assertThat(acks.get()).isEqualTo(6);

        assertThat(publisher.calls()).extracting(RecordingAmpsPublisher.Call::sowKeyOrFilter)
                .containsExactly("K-1", "K-2", "K-3", "K-4", "K-5", "K-6");
        assertThat(publisher.calls().get(0).topic()).isEqualTo("sow/test/bysize");
        assertThat(publisher.calls().get(0).data()).isEqualTo("{\"id\":\"K-1\"}");
    }

    @Test
    @DisplayName("a partial batch goes out at the flush-interval deadline, so a quiet feed is not stuck")
    void releasesAPartialBatchOnTheTimer() {
        AtomicInteger acks = new AtomicInteger();
        RecordingAmpsPublisher publisher = fakes.publisher("bytimeout");

        fakes.byTimeout.emit(record("T-1", acks));
        fakes.byTimeout.emit(record("T-2", acks));

        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(publisher.calls()).hasSize(2);
            assertThat(publisher.flushCount()).isEqualTo(1);
            assertThat(acks.get()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a steady feed slower than the batch size is still released every flush-interval")
    void releasesOnTheDeadlineWhileRecordsKeepArriving() throws InterruptedException {
        AtomicInteger acks = new AtomicInteger();
        RecordingAmpsPublisher publisher = fakes.publisher("bytimeout");

        // 20 records 40ms apart on a 150ms flush-interval with max-messages 500: an idle
        // timeout would never fire until the feed paused, and the batch would sit until the
        // 500th record. An absolute deadline releases a batch roughly every 150ms.
        for (int i = 0; i < 20; i++) {
            fakes.byTimeout.emit(record("S-" + i, acks));
            Thread.sleep(40);
        }

        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(publisher.calls()).hasSize(20);
            assertThat(acks.get()).isEqualTo(20);
        });
        // ~800ms of records at 150ms per batch: at least four batches, and never one of 20.
        assertThat(publisher.flushCount()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("stopping publishes what was read but not yet batched")
    void stopReleasesThePartialBatch() {
        AtomicInteger acks = new AtomicInteger();
        RecordingAmpsPublisher publisher = fakes.publisher("bysize");

        fakes.bySize.emit(record("K-1", acks));
        fakes.bySize.emit(record("K-2", acks));
        assertThat(publisher.calls()).isEmpty();

        manager.stop();

        assertThat(publisher.calls()).hasSize(2);
        assertThat(publisher.flushCount()).isEqualTo(1);
        assertThat(acks.get()).isEqualTo(2);
        assertThat(fakes.bySize.closeCount()).isEqualTo(1);
        assertThat(publisher.isConnected()).isFalse();
    }

    @Test
    @DisplayName("a record the pipeline rejects is counted, and the feed carries on")
    void survivesARejectedRecord() {
        AtomicInteger acks = new AtomicInteger();
        Connector connector = manager.connectors().get(0);

        fakes.bySize.emit(SourceRecord.of("not json at all"));
        fakes.bySize.emit(record("K-1", acks));
        fakes.bySize.emit(record("K-2", acks));
        fakes.bySize.emit(record("K-3", acks));

        assertThat(connector.rejected()).isEqualTo(1);
        assertThat(connector.received()).isEqualTo(4);
        assertThat(fakes.publisher("bysize").calls()).hasSize(3);
        assertThat(acks.get()).isEqualTo(3);
    }
}
