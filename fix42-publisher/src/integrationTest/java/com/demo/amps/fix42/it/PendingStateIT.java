package com.demo.amps.fix42.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.fix42.config.Fix42Properties;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.mock.FixEvent;
import com.demo.amps.fix42.mock.Instrument;
import com.demo.amps.fix42.mock.OrderChain;
import com.demo.amps.fix42.publish.AmpsDeltaPublisher;
import com.demo.amps.fix42.publish.PublishPlanner;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The in-flight window: what the blotter says while a request is outstanding.
 *
 * <p>{@link Fix42DeltaPublishIT} reads end state, where every request has been
 * answered. This class stops in the middle, because the moment between sending
 * a 35=G and hearing back is the one a single tag 38 can never represent -- the
 * venue is still working the old quantity while the desk has asked for a new
 * one, and both are true at once.
 *
 * <p>Its own server, so the partial chains here cannot perturb the record
 * counts the scripted-flow suite asserts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PendingStateIT {

    private static final String PARENT_ORDERS = "sow/parent/orders";

    private AmpsTestServer server;
    private Client client;
    private AmpsDeltaPublisher publisher;
    private SowReader sow;

    @BeforeAll
    void startServer() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason();
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        server = AmpsTestServer.start();
        Fix42Properties properties = Fix42Configurations.shipped(server.uri());
        properties.validate();

        client = new Client("pending-state-it");
        client.connect(properties.amps().uri());
        client.logon(properties.amps().timeoutMs());
        publisher = new AmpsDeltaPublisher(client, new PublishPlanner(properties), properties);
        sow = new SowReader(client, properties.amps().timeoutMs());
    }

    @AfterAll
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("an amend in flight shows the acked terms AND the proposed ones")
    void amendInFlightShowsBothTruths() throws Exception {
        FixMessage record = publishAndRead(OrderChain.forTest("INFLIGHT", Instrument.NVDA,
                        9_000, 55.25)
                .newOrder()
                .ack()
                .partialFill(2_000, 55.20)
                .amend(15_000, 55.80));

        // What the venue is actually working:
        assertThat(record.value(FixTags.ORDER_QTY)).isEqualTo("9000");
        assertThat(record.value(FixTags.PRICE)).isEqualTo("55.25");
        assertThat(record.value(FixTags.WORKING_CL_ORD_ID)).isEqualTo("INFLIGHT-1");
        // ...and simultaneously, what it has been asked to become:
        assertThat(record.value(FixTags.PENDING_ACTION))
                .isEqualTo(FixTags.PendingAction.REPLACE);
        assertThat(record.value(FixTags.PENDING_ORDER_QTY)).isEqualTo("15000");
        assertThat(record.value(FixTags.PENDING_PRICE)).isEqualTo("55.8");
        assertThat(record.value(FixTags.PENDING_CL_ORD_ID)).isEqualTo("INFLIGHT-2");

        // One record for this chain, not two: the module bound 11=INFLIGHT-2 to
        // it via 41=INFLIGHT-1. This is why the projection leaves 11 and 41
        // alone -- rewriting either hides the linkage and the order splits.
        assertThat(sow.records(PARENT_ORDERS).stream()
                .filter(r -> r.value(FixTags.CL_ORD_ID).startsWith("INFLIGHT")
                        || r.value(FixTags.WORKING_CL_ORD_ID).startsWith("INFLIGHT"))
                .toList())
                .as("both ClOrdIDs of the chain resolve to one record")
                .hasSize(1);
    }

    @Test
    @DisplayName("a cancel in flight is visible without altering a single order term")
    void cancelInFlightShowsPendingOnly() throws Exception {
        FixMessage record = publishAndRead(OrderChain.forTest("PULLING", Instrument.MSFT,
                        3_000, 410.10)
                .newOrder()
                .ack()
                .cancelRequest());

        assertThat(record.value(FixTags.PENDING_ACTION))
                .isEqualTo(FixTags.PendingAction.CANCEL);
        assertThat(record.value(FixTags.PENDING_CL_ORD_ID)).isEqualTo("PULLING-2");
        // A cancel proposes no terms, so the proposed-term fields stay clear...
        assertThat(record.value(FixTags.PENDING_ORDER_QTY)).isEqualTo("0");
        // ...and the order itself is untouched.
        assertThat(record.value(FixTags.ORDER_QTY)).isEqualTo("3000");
        assertThat(record.value(FixTags.PRICE)).isEqualTo("410.1");
        assertThat(record.value(FixTags.WORKING_CL_ORD_ID)).isEqualTo("PULLING-1");
    }

    @Test
    @DisplayName("a 35=D before its ack shows as pending NEW")
    void unackedOrderShowsPendingNew() throws Exception {
        FixMessage record = publishAndRead(
                OrderChain.forTest("UNACKED", Instrument.GOOG, 500, 174.50).newOrder());

        assertThat(record.value(FixTags.PENDING_ACTION)).isEqualTo(FixTags.PendingAction.NEW);
        assertThat(record.value(FixTags.WORKING_CL_ORD_ID)).isEqualTo("UNACKED-1");
        // Terms are known from the request even though nothing has acked them.
        assertThat(record.value(FixTags.ORDER_QTY)).isEqualTo("500");
    }

    @Test
    @DisplayName("a pending-replace acknowledgement (150=E) does not clear the pending state")
    void pendingAcknowledgementDoesNotResolve() throws Exception {
        // 150=E says "I have your amend", not "I have applied it". Clearing on
        // it would drop the proposal while the venue is still deciding.
        FixMessage record = publishAndRead(OrderChain.forTest("ACKPENDING", Instrument.AAPL,
                        7_000, 186.00)
                .newOrder()
                .ack()
                .amend(8_000, 186.25)
                .pendingReplace());

        assertThat(record.value(FixTags.PENDING_ACTION))
                .isEqualTo(FixTags.PendingAction.REPLACE);
        assertThat(record.value(FixTags.PENDING_ORDER_QTY)).isEqualTo("8000");
        assertThat(record.value(FixTags.ORDER_QTY)).isEqualTo("7000");
    }

    /** Publishes a chain into its own fresh topic state and returns its record. */
    private FixMessage publishAndRead(OrderChain chain) throws Exception {
        for (FixEvent event : chain.events()) {
            publisher.send(event.message());
        }
        publisher.flush();

        String working = chain.chainId() + "-1";
        List<FixMessage> matching = List.of();
        for (int attempt = 0; attempt < 40 && matching.isEmpty(); attempt++) {
            matching = sow.records(PARENT_ORDERS).stream()
                    .filter(record -> record.value(FixTags.WORKING_CL_ORD_ID).equals(working)
                            || record.value(FixTags.CL_ORD_ID).startsWith(chain.chainId()))
                    .toList();
            if (matching.isEmpty()) {
                Thread.sleep(200);
            }
        }
        assertThat(matching).as("one record for chain %s", chain.chainId()).hasSize(1);
        return matching.getFirst();
    }
}
